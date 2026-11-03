package ixdar.geometry.mesh.data.ops;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.CornerUvField;
import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.bundle.SlotCarry;
import ixdar.geometry.mesh.data.paths.query.TriangleBvh;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.EdgeKey;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.TriangleGeometry;
import ixdar.nodegraph.field.UvField;
import ixdar.numeric.graph.UnionFind;

/**
 * Topological repair of a triangle mesh over one working state built once and edited in place:
 * weld, orient, split, degenerates, shells, holes, and pruning of exposed faces.
 */
public final class MeshRepair {

    public static final int TRIANGLE_CORNERS = 3;

    private static final int KEY_SHIFT = 32;

    private static final long KEY_MASK = 0xFFFFFFFFL;

    private static final int GROWTH = 2;

    private static final int CONTAINMENT_RAYS = 3;

    private static final int DETACH_ROUNDS = 4;

    private static final int SMALL_BUCKET = 16;

    private static final int CLUSTER_FACES = 32;

    private static final int CLUSTER_SAMPLES = 2;

    private static final int SLOTS_PER_EDGE = 2;

    private static final int MAX_PRUNE_ROUNDS = 16;

    private static final int FIRST_PRUNE_RINGS = 2;

    private static final int FACES_PER_RAY_TASK = 512;

    private static final int RAY_LEAF_TRIANGLES = 4;

    private static final int CORNER_FACES_TRIED_FIRST = 48;

    // The debris floods give up for the whole-surface pass past this fraction of the vertices.
    private static final int DEBRIS_FLOOD_SHARE = 8;

    private static final double SLIVER_RATIO = 1e-4;

    private static final float FOLDED_BACK_NORMAL_COSINE = -0.5f;

    private static final double TINY_FACE_FRACTION = 0.01;

    private static final double PARITY_CROWD_MARGIN = 1e-4;

    private static final int UNVOUCHED = 0;

    private static final int VOUCHED_BY_RAYS = 1;

    private static final int VOUCHED_BY_PARITY = 2;

    private static final int HEMISPHERE_BLOCKED = 3;

    /** Distance below which the weld fuses two vertices; not positive means no weld runs. */
    public double weldEpsilon;

    /** Longest boundary loop the hole filling attempts. */
    public int maxHoleEdges;

    /** Shells with fewer faces than this are debris and are dropped. */
    public int minShellFaces;

    /** Whether the last stage prunes the faces that show their back to the outside. */
    public boolean pruneExposed = true;

    /** What the repair found and did, filled by {@link #build()}. */
    public final MeshRepairReport report = new MeshRepairReport();

    /** The repaired bundle, set by {@link #build()}. */
    public GeometryBundle output;

    /** The bundle handed in. */
    public final GeometryBundle input;

    /** The welded mesh the later stages read, or null when the weld stage did not run. */
    public MeshTopology weldedMesh;

    /** Input face index per welded face, or null without a weld. */
    private int[] weldedSourceFace;

    /** Input vertex index of each welded vertex's first member, or null without a weld. */
    private int[] weldedFirstVertex;

    /**
     * The vertices the non-manifold split shared out or copied, where a sheet the split cut loose
     * may hang inside the body; {@link #splitVertexCount} of them are used.
     */
    private int[] splitVertices = new int[SMALL_BUCKET];

    private int splitVertexCount;

    /** Working vertex positions, xyz triples; only ever appended to, so no vertex moves. */
    private float[] positions = new float[0];

    /** Live working vertices. */
    private int vertexCount;

    /** Input vertex each working vertex descends from, or {@code -1} when the repair minted it. */
    private int[] originalOfVertex = new int[0];

    /** Working face corners, three vertex indices per face. */
    private int[] faceCorners = new int[0];

    /** Whether each working face survives. */
    private boolean[] faceAlive = new boolean[0];

    /** Working faces, alive or not. */
    private int faceCount;

    /** First working face the hole filling appended; every later face is a fill triangle. */
    private int firstFillFace;

    /** Per-corner {@code u}, or null when the input carried no UV field. */
    private double[] cornerU;

    /** Per-corner {@code v}, parallel to {@link #cornerU}. */
    private double[] cornerV;

    /** Shell index of each alive face, {@code -1} on a dead face. */
    private int[] shellOfFace = new int[0];

    /** The alive faces' edges, built once after reading and re-filed face by face since. */
    private RepairEdges edges;

    /** Per vertex plus a tail, where the faces filed round it start in {@link #aroundFaces}. */
    private int[] aroundStart = new int[1];

    /** The faces round each vertex as the edges were built. */
    private int[] aroundFaces = new int[0];

    /** Per vertex the latest face that took it as a corner since the filing, or -1. */
    private int[] aroundHead = new int[0];

    /** Per chained entry the next older one, or -1. */
    private int[] aroundNext = new int[0];

    /** Per chained entry its face. */
    private int[] aroundChained = new int[0];

    private int aroundChainCount;

    /** Scratch the faces round one vertex are gathered into. */
    private int[] aroundScratch = new int[SMALL_BUCKET];

    /** Faces changed since the edges last saw them, the first {@link #touchedCount} entries. */
    private int[] touchedFaces = new int[0];

    private int touchedCount;

    /** Per face, whether it is listed in {@link #touchedFaces}. */
    private boolean[] faceTouched = new boolean[0];

    /**
     * Orientation's soup: the faces as read, the boundary loops fanned shut, and its column grid,
     * which every later parity question and cell lookup reads.
     */
    private TriangleBvh columnSoup;

    /** Faces orientation counted; every later face is new since. */
    private int orientedFaces;

    /** Per face orientation counted, its parity facing as the turns left it; null when unused. */
    private int[] orientedFacing;

    /** Per face orientation counted, whether another sheet crowded its parity line. */
    private boolean[] orientedCrowded;

    /** Per face orientation counted, its area then, zero on a face dead then. */
    private double[] orientedArea;

    private double orientedTotalArea;

    private int orientedAreaFaces;

    /** Per face orientation counted, whether it has died or moved a corner since. */
    private boolean[] changedSinceOrientation;

    /** The faces {@link #changedSinceOrientation} marks, in the order touched. */
    private int[] changedSince = new int[0];

    private int changedSinceCount;

    /** The alive faces of zero area orientation met, ascending. */
    private int[] flatFaces = new int[0];

    /** Faces the last refill's crease check judged, for the next pruning round to re-judge. */
    private int[] creaseJudged = new int[0];

    /** Per working vertex, the vertex before it on its hole, or -1; kept between fills. */
    private int[] holeNext = new int[0];

    /** Per working vertex, whether two holes run into it; kept between fills. */
    private boolean[] holeAmbiguous = new boolean[0];

    /** Per working vertex, whether a hole walk has passed it; kept between fills. */
    private boolean[] holeWalked = new boolean[0];

    /** Per working vertex, the mean incident edge length a fill last wrote. */
    private double[] sigmaScratch = new double[0];

    /** Per working vertex plus a tail, the start of its row of neighbours; boundary rows only. */
    private int[] neighbourScratch = new int[0];

    /** Local index of each working vertex on the loop being filled, or {@code -1}. */
    private int[] loopLocalOfVertex = new int[0];

    /** Local index of each working vertex on the filled loop's one-ring, or {@code -1}. */
    private int[] ringLocalOfVertex = new int[0];

    /** Per thread, the node stack the pruning's ray tasks reuse. */
    private final ThreadLocal<int[]> rayStacks = ThreadLocal.withInitial(() -> new int[0]);

    /** Scratch position reader. */
    private final Vector3f scratchPosition = new Vector3f();

    /**
     * Prepare a repair of one bundle; the bundle is not modified.
     *
     * @param input bundle holding the triangle mesh to repair
     */
    public MeshRepair(GeometryBundle input) {
        this.input = input;
    }

    /**
     * Runs every stage in order and leaves the repaired bundle in {@link #output}.
     *
     * @throws IllegalArgumentException when the input mesh is not made of triangles
     */
    public void build() {
        report.inputVertexCount = input.mesh().vertexCount();
        report.inputFaceCount = input.mesh().faceCount();
        weld();
        readInput();
        dropSlitFaces();
        edges = RepairEdges.build(faceCorners, faceAlive, faceCount, vertexCount);
        aroundStart = edges.facesAroundStartAsBuilt();
        aroundFaces = edges.facesAroundAsBuilt();
        aroundHead = new int[vertexCount];
        Arrays.fill(aroundHead, -1);
        orient();
        splitNonManifold();
        fixDegenerates();
        classifyShells();
        firstFillFace = faceCount;
        // The pruning's ray tree and crease flags need only the faces as they stand, so they are
        // made while the holes fill; the fills join them afterwards.
        TriangleBvh pruneTree = pruneExposed ? rayTree() : null;
        CompletableFuture<Void> treeBuilt = null;
        CompletableFuture<boolean[]> pruneCreases = null;
        CompletableFuture<int[]> cellsChangedBeforeFill = null;
        if (pruneTree != null && orientedFacing != null) {
            TriangleBvh tree = pruneTree;
            int vertices = vertexCount;
            treeBuilt = CompletableFuture.runAsync(tree::build);
            pruneCreases = CompletableFuture.supplyAsync(() -> creasedFaces(tree.triangleCorners, tree.positions,
                    tree.triangleCount, vertices, tree.triangleGroup));
            // The fill only adds faces, so the cells the faces changed so far reach are known now.
            float[] changedCorners = changedSinceOrientationCorners();
            cellsChangedBeforeFill = CompletableFuture.supplyAsync(() -> columnSoup.cellsUnder(changedCorners));
        }
        fillHoles(false);
        if (treeBuilt != null) {
            treeBuilt.join();
            pruneExposedFaces(pruneTree, pruneCreases.join(), cellsChangedBeforeFill.join());
        }
        assemble();
    }

    // ---------------------------------------------------------------------------------------
    // Read

    /**
     * Fuses vertices closer than {@link #weldEpsilon}, keeping the weld's maps so corner UVs and
     * the final slot carry see through it; a non-positive epsilon skips it.
     */
    private void weld() {
        if (weldEpsilon <= 0 || input.mesh().vertexCount() == 0) {
            return;
        }
        // The scan may be non-manifold, so the weld stays in its indexed form for the stages below.
        MeshMergeByDistance welder = new MeshMergeByDistance();
        welder.weldArrays(input.mesh(), (float) weldEpsilon);
        if (welder.weldedFaceCorners.length != welder.weldedFaceSizes.length * TRIANGLE_CORNERS) {
            throw new IllegalArgumentException("repair_mesh needs a triangle mesh");
        }
        weldedMesh = new ArrayMesh(welder.weldedPositions, null, welder.weldedFaceCorners,
                TRIANGLE_CORNERS);
        report.weldedVertexCount = input.mesh().vertexCount() - weldedMesh.vertexCount();
        int slitFaces = input.mesh().faceCount() - welder.sourceFace.length;
        report.degenerateFaceCount += slitFaces;
        report.degenerateDeletedCount += slitFaces;
        weldedSourceFace = welder.sourceFace;
        weldedFirstVertex = new int[weldedMesh.vertexCount()];
        for (int vertex = welder.weldedVertex.length - 1; vertex >= 0; vertex--) {
            weldedFirstVertex[welder.weldedVertex[vertex]] = vertex;
        }
    }

    /** Copies the input mesh and its per-corner UVs into the working arrays. */
    private void readInput() {
        MeshTopology mesh = weldedMesh != null ? weldedMesh : input.mesh();
        int meshVertexCount = mesh.vertexCount();
        int maxVertexId = 0;
        for (int index = 0; index < meshVertexCount; index++) {
            maxVertexId = Math.max(maxVertexId, mesh.vertexIdAt(index));
        }
        int[] denseOfVertexId = new int[maxVertexId + 1];
        Arrays.fill(denseOfVertexId, -1);
        positions = new float[meshVertexCount * TRIANGLE_CORNERS];
        originalOfVertex = new int[meshVertexCount];
        for (int index = 0; index < meshVertexCount; index++) {
            int vertexId = mesh.vertexIdAt(index);
            denseOfVertexId[vertexId] = index;
            mesh.vertexPosition(vertexId, scratchPosition);
            positions[index * TRIANGLE_CORNERS] = scratchPosition.x;
            positions[index * TRIANGLE_CORNERS + 1] = scratchPosition.y;
            positions[index * TRIANGLE_CORNERS + 2] = scratchPosition.z;
            originalOfVertex[index] = index;
        }
        vertexCount = meshVertexCount;
        faceCount = mesh.faceCount();
        faceCorners = new int[faceCount * TRIANGLE_CORNERS];
        faceAlive = new boolean[faceCount];
        Arrays.fill(faceAlive, true);
        UvField uv = input.slots().get(CornerUvField.SLOT) instanceof UvField field ? field : null;
        if (uv != null) {
            cornerU = new double[faceCount * TRIANGLE_CORNERS];
            cornerV = new double[faceCount * TRIANGLE_CORNERS];
        }
        for (int index = 0; index < faceCount; index++) {
            int faceId = mesh.faceIdAt(index);
            if (mesh.faceVertexCount(faceId) != TRIANGLE_CORNERS) {
                throw new IllegalArgumentException("repair_mesh needs a triangle mesh; face "
                        + faceId + " has " + mesh.faceVertexCount(faceId) + " corners");
            }
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int slot = index * TRIANGLE_CORNERS + corner;
                faceCorners[slot] = denseOfVertexId[mesh.faceVertexAt(faceId, corner)];
                if (uv != null) {
                    int uvFace = weldedSourceFace == null ? faceId
                            : input.mesh().faceIdAt(weldedSourceFace[index]);
                    cornerU[slot] = uv.u(uvFace, corner);
                    cornerV[slot] = uv.v(uvFace, corner);
                }
            }
        }
    }

    /**
     * Drops faces with a repeated corner: they have no well-formed edges, so they take no part in
     * the edges, and the slit each one leaves is a hole.
     */
    private void dropSlitFaces() {
        for (int face = 0; face < faceCount; face++) {
            int cornerA = faceCorners[face * TRIANGLE_CORNERS];
            int cornerB = faceCorners[face * TRIANGLE_CORNERS + 1];
            int cornerC = faceCorners[face * TRIANGLE_CORNERS + 2];
            if (cornerA == cornerB || cornerB == cornerC || cornerA == cornerC) {
                faceAlive[face] = false;
                report.degenerateFaceCount++;
                report.degenerateDeletedCount++;
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Orient

    /**
     * Makes the winding consistent and outward by two-colouring patches of consistently wound faces
     * that agree on which side is inside. An edge left contested is a Mobius contradiction,
     * reported rather than resolved. Leaves each face's parity, as turned, for the pruning.
     */
    private void orient() {
        TriangleBvh soup = closedRaySoup();
        CompletableFuture<Void> columns = CompletableFuture.runAsync(soup::buildColumns);
        // Parity is cast from the first and last face of each small cluster of consistently wound
        // faces and given to the whole cluster when they agree: it changes only across a shadow,
        // an intersection or a fold. Clusters where it may change are cast face by face below.
        int[] partner = edges.partnersAsBuilt();
        int[] clusterNumber = new int[faceCount];
        int[] clusterSamples = new int[faceCount * CLUSTER_SAMPLES];
        int[] clusterFaces = new int[faceCount];
        int[] clusterStart = new int[SMALL_BUCKET];
        int listed = 0;
        int[] grown = new int[CLUSTER_FACES];
        int clusters = 0;
        for (int seed = 0; seed < faceCount; seed++) {
            if (!faceAlive[seed] || clusterNumber[seed] != 0) {
                continue;
            }
            clusterNumber[seed] = clusters + 1;
            grown[0] = seed;
            int size = 1;
            for (int next = 0; next < size && size < CLUSTER_FACES; next++) {
                for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                    int across = partner[grown[next] * TRIANGLE_CORNERS + corner] - 1;
                    if (across >= 0 && clusterNumber[across / TRIANGLE_CORNERS] == 0 && size < CLUSTER_FACES) {
                        clusterNumber[across / TRIANGLE_CORNERS] = clusters + 1;
                        grown[size++] = across / TRIANGLE_CORNERS;
                    }
                }
            }
            for (int sample = 0; sample < CLUSTER_SAMPLES; sample++) {
                clusterSamples[clusters * CLUSTER_SAMPLES + sample] = grown[(size - 1) * sample
                        / (CLUSTER_SAMPLES - 1)];
            }
            if (clusters + 1 >= clusterStart.length) {
                clusterStart = Arrays.copyOf(clusterStart, clusterStart.length * GROWTH);
            }
            clusterStart[clusters] = listed;
            System.arraycopy(grown, 0, clusterFaces, listed, size);
            listed += size;
            clusters++;
        }
        clusterStart[clusters] = listed;
        boolean[] sampleAsked = new boolean[soup.triangleCount];
        for (int index = 0; index < clusters * CLUSTER_SAMPLES; index++) {
            sampleAsked[clusterSamples[index]] = true;
        }
        columns.join();
        columnSoup = soup;
        // Crowding is read with the parity: a cluster whose sample lies on a sheet nearly touching
        // another is cast face by face, so every face's crowding the pruning reads is known.
        soup.crowdMargin = pruneExposed ? PARITY_CROWD_MARGIN : 0;
        int[] sampled = soup.facing(sampleAsked);
        int[] sampledExposed = soup.exposedSide;
        boolean[] sampledCrowded = soup.crowded;
        int[] clusterFacing = new int[clusters];
        int[] clusterExposed = new int[clusters];
        boolean[] mixed = new boolean[clusters];
        for (int cluster = 0; cluster < clusters; cluster++) {
            int head = clusterSamples[cluster * CLUSTER_SAMPLES];
            clusterFacing[cluster] = sampled[head];
            clusterExposed[cluster] = sampledExposed[head];
            for (int sample = 0; sample < CLUSTER_SAMPLES; sample++) {
                int face = clusterSamples[cluster * CLUSTER_SAMPLES + sample];
                mixed[cluster] |= sampled[face] != clusterFacing[cluster]
                        || sampledCrowded != null && sampledCrowded[face];
                if (sampledExposed[face] != clusterExposed[cluster]) {
                    clusterExposed[cluster] = TriangleBvh.FACING_UNDECIDED;
                }
            }
        }
        // A cluster that borders one facing the other way, or sits on a contested edge, where the
        // winding and with it the side the parity finds inside turns over, may straddle a change.
        for (int edge = 0; edge < edges.edgeCount(); edge++) {
            if (edges.useCount(edge) != 2) {
                continue;
            }
            int first = clusterNumber[edges.use(edge, 0) / TRIANGLE_CORNERS] - 1;
            int second = clusterNumber[edges.use(edge, 1) / TRIANGLE_CORNERS] - 1;
            if (!edges.manifold(edge) || clusterFacing[first] != clusterFacing[second]) {
                mixed[first] = true;
                mixed[second] = true;
            }
        }
        // Their neighbours are cast too, since a change can run along a cluster's border.
        boolean[] near = Arrays.copyOf(mixed, clusters);
        for (int edge = 0; edge < edges.edgeCount(); edge++) {
            if (edges.manifold(edge)) {
                int first = clusterNumber[edges.use(edge, 0) / TRIANGLE_CORNERS] - 1;
                int second = clusterNumber[edges.use(edge, 1) / TRIANGLE_CORNERS] - 1;
                near[first] |= mixed[second];
                near[second] |= mixed[first];
            }
        }
        boolean[] asked = new boolean[soup.triangleCount];
        for (int cluster = 0; cluster < clusters; cluster++) {
            if (near[cluster]) {
                for (int index = clusterStart[cluster]; index < clusterStart[cluster + 1]; index++) {
                    asked[clusterFaces[index]] = true;
                }
            }
        }
        // Counted in face order over the flags: in the clusters' own order the faces scatter
        // through memory.
        int[] refined = soup.facing(asked);
        int[] refinedExposed = soup.exposedSide;
        boolean[] refinedCrowded = soup.crowded;
        soup.crowdMargin = 0;
        // Each face's facing: its own count where asked, else its cluster's.
        int[] facing = new int[faceCount];
        for (int face = 0; face < faceCount; face++) {
            if (faceAlive[face]) {
                facing[face] = asked[face] ? refined[face] : clusterFacing[clusterNumber[face] - 1];
            }
        }
        // The patches: a union-find over faces whose parents are kept one more than their face,
        // zero at a root, joined across consistently wound edges where both faces agree on facing.
        int[] parentPlusOne = new int[faceCount];
        int[] seamSlots = new int[0];
        int seamCount = 0;
        for (int edge = 0; edge < edges.edgeCount(); edge++) {
            if (edges.useCount(edge) != 2) {
                continue;
            }
            int firstSlot = edges.use(edge, 0);
            int secondSlot = edges.use(edge, 1);
            int firstFace = firstSlot / TRIANGLE_CORNERS;
            int secondFace = secondSlot / TRIANGLE_CORNERS;
            if (edges.forward(firstSlot) != edges.forward(secondSlot) && facing[firstFace] == facing[secondFace]) {
                int firstRoot = rootPlusOne(parentPlusOne, firstFace);
                int secondRoot = rootPlusOne(parentPlusOne, secondFace);
                if (firstRoot != secondRoot) {
                    parentPlusOne[secondRoot] = firstRoot + 1;
                }
                continue;
            }
            if ((seamCount + 1) * SLOTS_PER_EDGE > seamSlots.length) {
                seamSlots = Arrays.copyOf(seamSlots, Math.max(SLOTS_PER_EDGE, seamSlots.length * GROWTH));
            }
            seamSlots[seamCount * SLOTS_PER_EDGE] = firstSlot;
            seamSlots[seamCount * SLOTS_PER_EDGE + 1] = secondSlot;
            seamCount++;
        }
        // One pass numbers the patches in the order of their roots and gathers each one's exposure
        // balance, its largest face and its areas: faces whose front alone sees out count for it,
        // faces whose back alone sees out count against.
        int[] componentOf = new int[faceCount];
        int[] metPatchOfRoot = new int[faceCount];
        int[] rootOfMet = new int[SMALL_BUCKET];
        int[] metBalance = new int[SMALL_BUCKET];
        long[] metSeed = new long[SMALL_BUCKET];
        double[] faceAreas = new double[faceCount];
        int[] flat = new int[SMALL_BUCKET];
        int flatCount = 0;
        int components = 0;
        for (int face = 0; face < faceCount; face++) {
            if (!faceAlive[face]) {
                continue;
            }
            int root = rootPlusOne(parentPlusOne, face);
            if (metPatchOfRoot[root] == 0) {
                if (components == rootOfMet.length) {
                    rootOfMet = Arrays.copyOf(rootOfMet, components * GROWTH);
                    metBalance = Arrays.copyOf(metBalance, components * GROWTH);
                    metSeed = Arrays.copyOf(metSeed, components * GROWTH);
                }
                rootOfMet[components] = root;
                metSeed[components] = -1;
                metPatchOfRoot[root] = ++components;
            }
            int met = metPatchOfRoot[root] - 1;
            componentOf[face] = met;
            metBalance[met] += asked[face] ? refinedExposed[face] : clusterExposed[clusterNumber[face] - 1];
            double area = faceArea(face);
            faceAreas[face] = area;
            orientedTotalArea += area;
            orientedAreaFaces++;
            long key = ((long) Float.floatToIntBits((float) area) << KEY_SHIFT) | face;
            metSeed[met] = Math.max(metSeed[met], key);
            if (area == 0) {
                if (flatCount == flat.length) {
                    flat = Arrays.copyOf(flat, flatCount * GROWTH);
                }
                flat[flatCount++] = face;
            }
        }
        // No vertex moves before the degenerate stage but by its own collapses, so these are the
        // only faces it can find flat that no collapse touched.
        flatFaces = Arrays.copyOf(flat, flatCount);
        long[] byRoot = new long[components];
        for (int met = 0; met < components; met++) {
            byRoot[met] = ((long) rootOfMet[met] << KEY_SHIFT) | met;
        }
        Arrays.sort(byRoot);
        int[] patchOfMet = new int[components];
        int[] exposedBalance = new int[components];
        long[] seedOrder = new long[components];
        for (int patch = 0; patch < components; patch++) {
            int met = (int) (byRoot[patch] & KEY_MASK);
            patchOfMet[met] = patch;
            exposedBalance[patch] = metBalance[met];
            seedOrder[patch] = metSeed[met];
        }
        Arrays.sort(seedOrder);
        boolean[] componentFlip = twoColourPatches(seamSlots, seamCount, componentOf, patchOfMet,
                components, seedOrder, exposedBalance, facing, faceAreas);
        // Read before the turns, while the edge table still holds the windings as read.
        for (int index = 0; index < seamCount; index++) {
            int firstSlot = seamSlots[index * SLOTS_PER_EDGE];
            int secondSlot = seamSlots[index * SLOTS_PER_EDGE + 1];
            boolean firstFlips = componentFlip[patchOfMet[componentOf[firstSlot / TRIANGLE_CORNERS]]];
            boolean secondFlips = componentFlip[patchOfMet[componentOf[secondSlot / TRIANGLE_CORNERS]]];
            if ((edges.forward(firstSlot) == edges.forward(secondSlot)) == (firstFlips == secondFlips)) {
                report.mobiusEdgeCount++;
            }
        }
        // Turned in place; the edge table turns each face's slots in place too.
        boolean[] crowded = pruneExposed ? new boolean[faceCount] : null;
        for (int face = 0; face < faceCount; face++) {
            if (!faceAlive[face]) {
                continue;
            }
            if (crowded != null) {
                crowded[face] = asked[face] && refinedCrowded != null && refinedCrowded[face];
            }
            if (!componentFlip[patchOfMet[componentOf[face]]]) {
                continue;
            }
            int swap = faceCorners[face * TRIANGLE_CORNERS + 1];
            faceCorners[face * TRIANGLE_CORNERS + 1] = faceCorners[face * TRIANGLE_CORNERS + 2];
            faceCorners[face * TRIANGLE_CORNERS + 2] = swap;
            edges.flipFace(face, faceCorners);
            // The crossings do not depend on any face's winding, so a turned face's facing is its
            // count negated.
            facing[face] = -facing[face];
            if (cornerU != null) {
                double swapU = cornerU[face * TRIANGLE_CORNERS + 1];
                cornerU[face * TRIANGLE_CORNERS + 1] = cornerU[face * TRIANGLE_CORNERS + 2];
                cornerU[face * TRIANGLE_CORNERS + 2] = swapU;
                double swapV = cornerV[face * TRIANGLE_CORNERS + 1];
                cornerV[face * TRIANGLE_CORNERS + 1] = cornerV[face * TRIANGLE_CORNERS + 2];
                cornerV[face * TRIANGLE_CORNERS + 2] = swapV;
            }
            report.orientedFaceCount++;
        }
        if (pruneExposed) {
            orientedFaces = faceCount;
            orientedFacing = facing;
            orientedCrowded = crowded;
            orientedArea = faceAreas;
            changedSinceOrientation = new boolean[faceCount];
        }
    }

    /**
     * Decides which patches turn: two-colours them along a maximum spanning tree of seam votes,
     * then turns a patch whose faces see out against its seams.
     *
     * @param seamSlots      two slots per seam edge
     * @param seamCount      seam edges
     * @param componentOf    per face, its patch as met
     * @param patchOfMet     per met patch, its patch number
     * @param components     patches
     * @param seedOrder      per patch its largest face key, ascending
     * @param exposedBalance per patch, faces seeing out by their front minus by their back
     * @param facing         per face its parity facing as wound now
     * @param faceAreas      per face its area
     * @return per patch whether it turns
     */
    private boolean[] twoColourPatches(int[] seamSlots, int seamCount, int[] componentOf, int[] patchOfMet,
            int components, long[] seedOrder, int[] exposedBalance, int[] facing, double[] faceAreas) {
        long[] oppositePairs = new long[seamCount];
        long[] samePairs = new long[seamCount];
        int opposites = 0;
        int sames = 0;
        for (int index = 0; index < seamCount; index++) {
            int firstSlot = seamSlots[index * SLOTS_PER_EDGE];
            int secondSlot = seamSlots[index * SLOTS_PER_EDGE + 1];
            int first = patchOfMet[componentOf[firstSlot / TRIANGLE_CORNERS]];
            int second = patchOfMet[componentOf[secondSlot / TRIANGLE_CORNERS]];
            if (first == second) {
                continue;
            }
            if (edges.forward(firstSlot) == edges.forward(secondSlot)) {
                oppositePairs[opposites++] = EdgeKey.undirected(first, second);
            } else {
                samePairs[sames++] = EdgeKey.undirected(first, second);
            }
        }
        Arrays.sort(oppositePairs, 0, opposites);
        Arrays.sort(samePairs, 0, sames);
        long[] pairs = new long[opposites + sames];
        int[] vote = new int[opposites + sames];
        int pairCount = 0;
        int nextOpposite = 0;
        int nextSame = 0;
        while (nextOpposite < opposites || nextSame < sames) {
            boolean takeOpposite = nextSame == sames
                    || nextOpposite < opposites && oppositePairs[nextOpposite] <= samePairs[nextSame];
            long pair = takeOpposite ? oppositePairs[nextOpposite++] : samePairs[nextSame++];
            if (pairCount == 0 || pairs[pairCount - 1] != pair) {
                pairs[pairCount++] = pair;
            }
            vote[pairCount - 1] += takeOpposite ? 1 : -1;
        }
        int[] linkStart = new int[components + 1];
        for (int pair = 0; pair < pairCount; pair++) {
            linkStart[EdgeKey.minVertex(pairs[pair])]++;
            linkStart[EdgeKey.maxVertex(pairs[pair])]++;
        }
        int running = 0;
        for (int component = 0; component < components; component++) {
            int degree = linkStart[component];
            linkStart[component] = running;
            running += degree;
        }
        linkStart[components] = running;
        int[] cursor = Arrays.copyOf(linkStart, components);
        int[] links = new int[running];
        for (int pair = 0; pair < pairCount; pair++) {
            links[cursor[EdgeKey.minVertex(pairs[pair])]++] = pair;
            links[cursor[EdgeKey.maxVertex(pairs[pair])]++] = pair;
        }
        boolean[] componentFlip = new boolean[components];
        boolean[] componentVisited = new boolean[components];
        int[] treeOfComponent = new int[components];
        PriorityQueue<Long> frontier = new PriorityQueue<>(Comparator.reverseOrder());
        for (int index = seedOrder.length - 1; index >= 0; index--) {
            int root = patchOfMet[componentOf[(int) (seedOrder[index] & KEY_MASK)]];
            if (componentVisited[root]) {
                continue;
            }
            componentVisited[root] = true;
            treeOfComponent[root] = root;
            int reached = root;
            while (reached >= 0) {
                for (int link = linkStart[reached]; link < linkStart[reached + 1]; link++) {
                    if (vote[links[link]] != 0) {
                        frontier.add(((long) Math.abs(vote[links[link]]) << KEY_SHIFT) | links[link]);
                    }
                }
                reached = -1;
                while (reached < 0 && !frontier.isEmpty()) {
                    int pair = (int) (frontier.poll() & KEY_MASK);
                    int low = EdgeKey.minVertex(pairs[pair]);
                    int high = EdgeKey.maxVertex(pairs[pair]);
                    if (componentVisited[low] != componentVisited[high]) {
                        int from = componentVisited[low] ? low : high;
                        reached = from == low ? high : low;
                        componentVisited[reached] = true;
                        componentFlip[reached] = componentFlip[from] != (vote[pair] > 0);
                        treeOfComponent[reached] = root;
                    }
                }
            }
        }
        double[] inwardAreaOfTree = new double[components];
        for (int face = 0; face < faceCount; face++) {
            if (faceAlive[face]) {
                int component = patchOfMet[componentOf[face]];
                inwardAreaOfTree[treeOfComponent[component]] += faceAreas[face] * -facing[face]
                        * (componentFlip[component] ? -1 : 1);
            }
        }
        for (int component = 0; component < components; component++) {
            componentFlip[component] = componentFlip[component]
                    != (inwardAreaOfTree[treeOfComponent[component]] > 0);
        }
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        boolean[] queued = new boolean[components];
        for (int component = 0; component < components; component++) {
            if (exposedBalance[component] != 0) {
                pending.add(component);
                queued[component] = true;
            }
        }
        while (!pending.isEmpty()) {
            int component = pending.poll();
            queued[component] = false;
            int cost = componentFlip[component] ? -exposedBalance[component] : exposedBalance[component];
            for (int link = linkStart[component]; link < linkStart[component + 1]; link++) {
                int pair = links[link];
                int other = EdgeKey.minVertex(pairs[pair]) == component
                        ? EdgeKey.maxVertex(pairs[pair]) : EdgeKey.minVertex(pairs[pair]);
                boolean apart = componentFlip[component] != componentFlip[other];
                cost += apart == (vote[pair] > 0) ? Math.abs(vote[pair]) : -Math.abs(vote[pair]);
            }
            if (cost >= 0) {
                continue;
            }
            componentFlip[component] = !componentFlip[component];
            for (int link = linkStart[component]; link < linkStart[component + 1]; link++) {
                int other = EdgeKey.minVertex(pairs[links[link]]) == component
                        ? EdgeKey.maxVertex(pairs[links[link]]) : EdgeKey.minVertex(pairs[links[link]]);
                if (!queued[other]) {
                    pending.add(other);
                    queued[other] = true;
                }
            }
        }
        return componentFlip;
    }

    /**
     * The alive faces as a ray soup with every boundary loop fanned shut to a centre appended as
     * an unused working vertex, so the soup reads the working positions.
     *
     * @return the soup, its first {@link #faceCount} triangles the faces
     */
    private TriangleBvh closedRaySoup() {
        int[] open = edges.openEdgesAsBuilt();
        int boundaryEdges = open.length;
        // The loops are worked over the boundary vertices alone, numbered in vertex order.
        int[] boundary = new int[boundaryEdges * 2];
        for (int index = 0; index < boundaryEdges; index++) {
            boundary[index * 2] = EdgeKey.minVertex(edges.key(open[index]));
            boundary[index * 2 + 1] = EdgeKey.maxVertex(edges.key(open[index]));
        }
        boundary = Arrays.stream(boundary).sorted().distinct().toArray();
        int[] loopOf = UnionFind.singletons(boundary.length);
        for (int edge : open) {
            UnionFind.union(loopOf, Arrays.binarySearch(boundary, EdgeKey.minVertex(edges.key(edge))),
                    Arrays.binarySearch(boundary, EdgeKey.maxVertex(edges.key(edge))));
        }
        double[] centroid = new double[boundary.length * TRIANGLE_CORNERS];
        int[] loopVertices = new int[boundary.length];
        for (int edge : open) {
            int vertex = EdgeKey.minVertex(edges.key(edge));
            int loop = UnionFind.find(loopOf, Arrays.binarySearch(boundary, vertex));
            loopVertices[loop]++;
            for (int component = 0; component < TRIANGLE_CORNERS; component++) {
                centroid[loop * TRIANGLE_CORNERS + component] += positions[vertex * TRIANGLE_CORNERS + component];
            }
        }
        int[] centreOfLoop = new int[boundary.length];
        for (int loop = 0; loop < boundary.length; loop++) {
            centreOfLoop[loop] = loopVertices[loop] == 0 ? -1
                    : addVertex((float) (centroid[loop * TRIANGLE_CORNERS] / loopVertices[loop]),
                            (float) (centroid[loop * TRIANGLE_CORNERS + 1] / loopVertices[loop]),
                            (float) (centroid[loop * TRIANGLE_CORNERS + 2] / loopVertices[loop]));
        }
        TriangleBvh soup = new TriangleBvh();
        soup.positions = positions;
        soup.triangleCount = faceCount + boundaryEdges;
        soup.firstExposureOnly = faceCount;
        soup.triangleCorners = Arrays.copyOf(faceCorners, soup.triangleCount * TRIANGLE_CORNERS);
        soup.triangleGroup = new int[soup.triangleCount];
        for (int face = 0; face < faceCount; face++) {
            soup.triangleGroup[face] = faceAlive[face] ? face : -1;
        }
        int fan = faceCount;
        for (int edge : open) {
            int low = EdgeKey.minVertex(edges.key(edge));
            soup.triangleCorners[fan * TRIANGLE_CORNERS] = low;
            soup.triangleCorners[fan * TRIANGLE_CORNERS + 1] = EdgeKey.maxVertex(edges.key(edge));
            soup.triangleCorners[fan * TRIANGLE_CORNERS + 2] =
                    centreOfLoop[UnionFind.find(loopOf, Arrays.binarySearch(boundary, low))];
            soup.triangleGroup[fan] = fan;
            fan++;
        }
        return soup;
    }

    /**
     * {@link UnionFind#find} over a forest whose parents are kept one more than their element and
     * zero at a root, so a fresh array is already the singleton forest; it halves the path walked.
     *
     * @param parentPlusOne per element, one more than its parent, or zero at a root
     * @param element       the element
     * @return its root
     */
    private static int rootPlusOne(int[] parentPlusOne, int element) {
        int root = element;
        while (parentPlusOne[root] != 0) {
            int parent = parentPlusOne[root] - 1;
            if (parentPlusOne[parent] != 0) {
                parentPlusOne[root] = parentPlusOne[parent];
            }
            root = parent;
        }
        return root;
    }

    // ---------------------------------------------------------------------------------------
    // Split non-manifold

    /**
     * Pulls non-manifold edges apart by giving each edge-connected face fan its own copy of the
     * vertices, which duplicates the offending edge once per fan and deletes nothing.
     */
    private void splitNonManifold() {
        ensureEdges();
        edges.clearChanged();
        // Nearly every vertex has one fan, which a walk round it shows without grouping; only the
        // others are grouped.
        int[] uneven = IntStream.range(0, aroundStart.length - 1).parallel().filter(vertex -> !oneFan(vertex))
                .toArray();
        List<VertexFans> found = Arrays.stream(uneven).mapToObj(this::fansAt).filter(Objects::nonNull).toList();
        List<int[]> extraFans = new ArrayList<>();
        IntStream.Builder nonManifold = IntStream.builder();
        for (VertexFans fans : found) {
            report.nonManifoldEdgeCount += fans.nonManifoldEdges().length;
            for (int edge : fans.nonManifoldEdges()) {
                nonManifold.add(edge);
            }
            extraFans.addAll(fans.extraFans());
        }
        int[] candidates = nonManifold.build().toArray();
        // Each vertex's first fan, in face order, keeps the vertex; every other fan gets a copy.
        extraFans.sort(Comparator.comparingInt(fan -> fan[0]));
        IntStream.Builder moved = IntStream.builder();
        for (int[] fan : extraFans) {
            noteSplitVertex(faceCorners[fan[0]]);
            int copy = copyVertex(faceCorners[fan[0]]);
            noteSplitVertex(copy);
            report.splitVertexCount++;
            for (int slot : fan) {
                faceCorners[slot] = copy;
                noteCorner(slot / TRIANGLE_CORNERS, copy);
                moved.add(slot / TRIANGLE_CORNERS);
            }
        }
        moved.build().sorted().distinct().forEach(this::refile);
        for (int round = 0; round < DETACH_ROUNDS; round++) {
            ensureEdges();
            // A face can still share a side with two others only on a side that was non-manifold
            // or that a split has re-filed since.
            int[] changed = new int[edges.changedEdgeCount()];
            for (int index = 0; index < changed.length; index++) {
                changed[index] = edges.changedEdge(index);
            }
            edges.clearChanged();
            candidates = IntStream.concat(Arrays.stream(candidates), Arrays.stream(changed)).sorted().distinct()
                    .filter(edge -> !edges.manifold(edge) && edges.useCount(edge) >= 2).toArray();
            if (detachStubbornFaces(candidates) == 0) {
                return;
            }
        }
    }

    /**
     * One vertex's fans beyond its first and the non-manifold edges it is the lower end of.
     *
     * @param extraFans        per fan after the first, its corner slots at the vertex, ascending
     * @param nonManifoldEdges edges with two or more uses that are not one each way
     */
    private record VertexFans(List<int[]> extraFans, int[] nonManifoldEdges) {
    }

    /**
     * Whether the faces filed round a vertex make one fan with no non-manifold edge: walking from
     * the first face across manifold edges, one way and then the other, reaches every one of them.
     *
     * @param vertex working vertex
     * @return true when {@link #fansAt} would find nothing to do
     */
    private boolean oneFan(int vertex) {
        int start = aroundStart[vertex];
        int count = aroundStart[vertex + 1] - start;
        if (count <= 1) {
            return count == 0 || manifoldOrOpen(vertexSlot(aroundFaces[start], vertex))
                    && manifoldOrOpen(previousSlot(vertexSlot(aroundFaces[start], vertex)));
        }
        int first = vertexSlot(aroundFaces[start], vertex);
        int reached = 1;
        int slot = first;
        while (true) {
            int edge = edges.edgeOfSlot(slot);
            if (!edges.manifold(edge)) {
                if (edges.useCount(edge) > 1) {
                    return false;
                }
                break;
            }
            int other = edges.use(edge, 0) == slot ? edges.use(edge, 1) : edges.use(edge, 0);
            slot = other / TRIANGLE_CORNERS * TRIANGLE_CORNERS + (other % TRIANGLE_CORNERS + 1) % TRIANGLE_CORNERS;
            if (slot == first) {
                return reached == count;
            }
            if (++reached > count) {
                return false;
            }
        }
        slot = previousSlot(first);
        while (true) {
            int edge = edges.edgeOfSlot(slot);
            if (!edges.manifold(edge)) {
                return edges.useCount(edge) <= 1 && reached == count;
            }
            int other = edges.use(edge, 0) == slot ? edges.use(edge, 1) : edges.use(edge, 0);
            slot = previousSlot(other);
            if (++reached > count) {
                return false;
            }
        }
    }

    /**
     * Whether an edge is manifold or used once.
     *
     * @param slot a corner slot running the edge
     * @return true unless the edge is non-manifold
     */
    private boolean manifoldOrOpen(int slot) {
        int edge = edges.edgeOfSlot(slot);
        return edges.manifold(edge) || edges.useCount(edge) <= 1;
    }

    /**
     * The slot of a face's corner at a vertex, whose side leaves the vertex.
     *
     * @param face   working face
     * @param vertex one of its corners
     * @return the slot
     */
    private int vertexSlot(int face, int vertex) {
        int slot = face * TRIANGLE_CORNERS;
        return faceCorners[slot] == vertex ? slot : faceCorners[slot + 1] == vertex ? slot + 1 : slot + 2;
    }

    /**
     * The slot of the corner before one in its face, whose side arrives at that corner.
     *
     * @param slot a corner slot
     * @return the previous corner's slot
     */
    private static int previousSlot(int slot) {
        return slot / TRIANGLE_CORNERS * TRIANGLE_CORNERS + (slot % TRIANGLE_CORNERS + 2) % TRIANGLE_CORNERS;
    }

    /**
     * Groups the faces filed round a vertex into fans, linking two faces across each manifold edge
     * at the vertex.
     *
     * @param vertex working vertex
     * @return its extra fans and the non-manifold edges counted at it, or null when there are none
     */
    private VertexFans fansAt(int vertex) {
        int start = aroundStart[vertex];
        int count = aroundStart[vertex + 1] - start;
        if (count == 0) {
            return null;
        }
        int[] fanOf = UnionFind.singletons(count);
        int[] cornerSlot = new int[count];
        int[] nonManifold = new int[0];
        for (int index = 0; index < count; index++) {
            int face = aroundFaces[start + index];
            int corner = faceCorners[face * TRIANGLE_CORNERS] == vertex ? 0
                    : faceCorners[face * TRIANGLE_CORNERS + 1] == vertex ? 1 : 2;
            cornerSlot[index] = face * TRIANGLE_CORNERS + corner;
            for (int slot : new int[] {cornerSlot[index], face * TRIANGLE_CORNERS + (corner + 2) % TRIANGLE_CORNERS}) {
                int edge = edges.edgeOfSlot(slot);
                if (!edges.manifold(edge)) {
                    if (edges.useCount(edge) > 1 && edges.use(edge, 0) == slot
                            && EdgeKey.minVertex(edges.key(edge)) == vertex) {
                        nonManifold = Arrays.copyOf(nonManifold, nonManifold.length + 1);
                        nonManifold[nonManifold.length - 1] = edge;
                    }
                    continue;
                }
                int other = edges.use(edge, 0) == slot ? edges.use(edge, 1) : edges.use(edge, 0);
                int neighbour = Arrays.binarySearch(aroundFaces, start, start + count, other / TRIANGLE_CORNERS)
                        - start;
                UnionFind.union(fanOf, index, neighbour);
            }
        }
        int keeper = UnionFind.find(fanOf, 0);
        List<int[]> extra = new ArrayList<>();
        int[] fanSlots = new int[count];
        boolean[] gathered = new boolean[count];
        for (int index = 1; index < count; index++) {
            int fan = UnionFind.find(fanOf, index);
            if (fan == keeper || gathered[fan]) {
                continue;
            }
            gathered[fan] = true;
            int members = 0;
            for (int member = index; member < count; member++) {
                if (UnionFind.find(fanOf, member) == fan) {
                    fanSlots[members++] = cornerSlot[member];
                }
            }
            extra.add(Arrays.copyOf(fanSlots, members));
        }
        return extra.isEmpty() && nonManifold.length == 0 ? null : new VertexFans(extra, nonManifold);
    }

    /**
     * Adds a vertex to {@link #splitVertices}.
     *
     * @param vertex working vertex
     */
    private void noteSplitVertex(int vertex) {
        if (splitVertexCount == splitVertices.length) {
            splitVertices = Arrays.copyOf(splitVertices, splitVertexCount * GROWTH);
        }
        splitVertices[splitVertexCount++] = vertex;
    }

    /**
     * Detaches, on all three corners, any face still sharing an edge with two others after the fan
     * split, leaving it a tiny shell of its own for the shell stage to report.
     *
     * @param candidates the edges that may have more than one use each way, ascending
     * @return how many faces were detached
     */
    private int detachStubbornFaces(int[] candidates) {
        boolean[] detach = new boolean[faceCount];
        IntStream.Builder marked = IntStream.builder();
        int detached = 0;
        for (int edge : candidates) {
            int keptForward = -1;
            int keptBackward = -1;
            for (int use = 0; use < edges.useCount(edge); use++) {
                int slot = edges.use(edge, use);
                if (edges.forward(slot) && keptForward < 0) {
                    keptForward = slot;
                } else if (!edges.forward(slot) && keptBackward < 0) {
                    keptBackward = slot;
                } else if (!detach[slot / TRIANGLE_CORNERS]) {
                    detach[slot / TRIANGLE_CORNERS] = true;
                    marked.add(slot / TRIANGLE_CORNERS);
                    detached++;
                }
            }
        }
        if (detached == 0) {
            return 0;
        }
        for (int face : marked.build().sorted().toArray()) {
            if (!faceAlive[face]) {
                continue;
            }
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int slot = face * TRIANGLE_CORNERS + corner;
                noteSplitVertex(faceCorners[slot]);
                faceCorners[slot] = copyVertex(faceCorners[slot]);
                noteSplitVertex(faceCorners[slot]);
                noteCorner(face, faceCorners[slot]);
            }
            refile(face);
        }
        report.detachedFaceCount += detached;
        return detached;
    }

    // ---------------------------------------------------------------------------------------
    // Degenerates

    /**
     * Resolves faces of exactly zero area by collapsing their shortest edge when the link
     * condition holds, and by deletion otherwise. Only the faces orientation found flat and those a
     * collapse moves are visited, in ascending order.
     */
    private void fixDegenerates() {
        TreeSet<Integer> pending = new TreeSet<>();
        for (int face : flatFaces) {
            pending.add(face);
        }
        flatFaces = new int[0];
        while (!pending.isEmpty()) {
            int face = pending.pollFirst();
            if (!faceAlive[face] || faceArea(face) != 0) {
                continue;
            }
            report.degenerateFaceCount++;
            int shortest = shortestCorner(face);
            int keep = faceCorners[face * TRIANGLE_CORNERS + shortest];
            int drop = faceCorners[face * TRIANGLE_CORNERS + (shortest + 1) % TRIANGLE_CORNERS];
            if (linkConditionHolds(keep, drop)) {
                for (int moved : collapse(keep, drop)) {
                    if (moved > face) {
                        pending.add(moved);
                    }
                }
                report.degenerateCollapsedCount++;
            } else {
                faceAlive[face] = false;
                touch(face);
            }
        }
    }

    /**
     * Corner of the face whose outgoing edge is shortest.
     *
     * @param face working face index
     * @return corner index in {@code [0, 3)}
     */
    private int shortestCorner(int face) {
        int best = 0;
        double bestLength = Double.POSITIVE_INFINITY;
        for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
            double length = squaredDistance(faceCorners[face * TRIANGLE_CORNERS + corner],
                    faceCorners[face * TRIANGLE_CORNERS + (corner + 1) % TRIANGLE_CORNERS]);
            if (length < bestLength) {
                bestLength = length;
                best = corner;
            }
        }
        return best;
    }

    /**
     * Whether collapsing one vertex into the other leaves every neighbour a valid triangle: the
     * shared one-ring must be exactly the apexes of the faces on the edge, and no triangle of the
     * one ring may be shared (Dey et al.'s link condition).
     *
     * @param keep vertex that survives the collapse
     * @param drop vertex that folds into it
     * @return true when the collapse is safe
     */
    private boolean linkConditionHolds(int keep, int drop) {
        Set<Integer> neighbourOfKeep = new HashSet<>();
        Set<Integer> neighbourOfDrop = new HashSet<>();
        Set<Integer> apex = new HashSet<>();
        Set<Long> oppositeEdgeOfKeep = new HashSet<>();
        Set<Long> oppositeEdgeOfDrop = new HashSet<>();
        for (int face : facesAround(keep, drop)) {
            int cornerA = faceCorners[face * TRIANGLE_CORNERS];
            int cornerB = faceCorners[face * TRIANGLE_CORNERS + 1];
            int cornerC = faceCorners[face * TRIANGLE_CORNERS + 2];
            boolean hasKeep = cornerA == keep || cornerB == keep || cornerC == keep;
            boolean hasDrop = cornerA == drop || cornerB == drop || cornerC == drop;
            if (hasKeep) {
                neighbourOfKeep.addAll(List.of(cornerA, cornerB, cornerC));
                oppositeEdgeOfKeep.add(oppositeEdgeKey(cornerA, cornerB, cornerC, keep));
            }
            if (hasDrop) {
                neighbourOfDrop.addAll(List.of(cornerA, cornerB, cornerC));
                oppositeEdgeOfDrop.add(oppositeEdgeKey(cornerA, cornerB, cornerC, drop));
            }
            if (hasKeep && hasDrop) {
                apex.add(cornerA + cornerB + cornerC - keep - drop);
            }
        }
        if (apex.isEmpty() || apex.size() > 2) {
            return false;
        }
        oppositeEdgeOfKeep.retainAll(oppositeEdgeOfDrop);
        if (!oppositeEdgeOfKeep.isEmpty()) {
            return false;
        }
        neighbourOfKeep.retainAll(neighbourOfDrop);
        neighbourOfKeep.remove(keep);
        neighbourOfKeep.remove(drop);
        return apex.containsAll(neighbourOfKeep) && neighbourOfKeep.size() == apex.size();
    }

    /**
     * The alive faces that use either of two vertices, each once, ascending.
     *
     * @param first  one vertex
     * @param second the other
     * @return the faces
     */
    private int[] facesAround(int first, int second) {
        int firstCount = facesRound(first);
        int[] faces = Arrays.copyOf(aroundScratch, firstCount);
        int secondCount = facesRound(second);
        faces = Arrays.copyOf(faces, firstCount + secondCount);
        System.arraycopy(aroundScratch, 0, faces, firstCount, secondCount);
        return Arrays.stream(faces).sorted().distinct().toArray();
    }

    /**
     * Key of the edge of a face opposite one of its corners.
     *
     * @param cornerA first corner of the face
     * @param cornerB second corner of the face
     * @param cornerC third corner of the face
     * @param corner  the corner to leave out; must be one of the three
     * @return the packed key of the other two corners
     */
    private static long oppositeEdgeKey(int cornerA, int cornerB, int cornerC, int corner) {
        if (cornerA == corner) {
            return EdgeKey.undirected(cornerB, cornerC);
        }
        if (cornerB == corner) {
            return EdgeKey.undirected(cornerA, cornerC);
        }
        return EdgeKey.undirected(cornerA, cornerB);
    }

    /**
     * Folds one vertex into another: faces spanning both die, the rest re-index.
     *
     * @param keep vertex that survives
     * @param drop vertex that disappears
     * @return the faces moved onto the kept vertex, ascending
     */
    private int[] collapse(int keep, int drop) {
        int[] moved = new int[0];
        for (int face : facesAround(drop, drop)) {
            boolean hasKeep = false;
            boolean hasDrop = false;
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int vertex = faceCorners[face * TRIANGLE_CORNERS + corner];
                hasKeep |= vertex == keep;
                hasDrop |= vertex == drop;
            }
            if (!hasDrop) {
                continue;
            }
            touch(face);
            if (hasKeep) {
                faceAlive[face] = false;
                continue;
            }
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                if (faceCorners[face * TRIANGLE_CORNERS + corner] == drop) {
                    faceCorners[face * TRIANGLE_CORNERS + corner] = keep;
                }
            }
            noteCorner(face, keep);
            moved = Arrays.copyOf(moved, moved.length + 1);
            moved[moved.length - 1] = face;
        }
        return moved;
    }

    // ---------------------------------------------------------------------------------------
    // Shells

    /**
     * Groups the faces into edge-connected shells, decides which sit inside another by ray parity,
     * and drops only debris below {@link #minShellFaces} and enclosed bubbles.
     */
    private void classifyShells() {
        ensureEdges();
        int[] parentPlusOne = new int[faceCount];
        for (int edge = 0; edge < edges.edgeCount(); edge++) {
            int first = edges.use(edge, 0) / TRIANGLE_CORNERS;
            for (int use = 1; use < edges.useCount(edge); use++) {
                int firstRoot = rootPlusOne(parentPlusOne, first);
                int otherRoot = rootPlusOne(parentPlusOne, edges.use(edge, use) / TRIANGLE_CORNERS);
                if (firstRoot != otherRoot) {
                    parentPlusOne[otherRoot] = firstRoot + 1;
                }
            }
        }
        int[] shellOfRoot = new int[faceCount];
        shellOfFace = new int[faceCount];
        int[] shellFaceCount = new int[SMALL_BUCKET];
        int[] shellFirstFace = new int[SMALL_BUCKET];
        int shells = 0;
        for (int face = 0; face < faceCount; face++) {
            if (!faceAlive[face]) {
                shellOfFace[face] = -1;
                continue;
            }
            int root = rootPlusOne(parentPlusOne, face);
            if (shellOfRoot[root] == 0) {
                if (shells == shellFaceCount.length) {
                    shellFaceCount = Arrays.copyOf(shellFaceCount, shells * GROWTH);
                    shellFirstFace = Arrays.copyOf(shellFirstFace, shells * GROWTH);
                }
                shellFirstFace[shells] = face;
                shellOfRoot[root] = ++shells;
            }
            shellOfFace[face] = shellOfRoot[root] - 1;
            shellFaceCount[shellOfFace[face]]++;
        }
        report.shellCount = shells;
        if (shells == 0) {
            return;
        }
        shellFaceCount = Arrays.copyOf(shellFaceCount, shells);
        int largestShell = 0;
        for (int shell = 1; shell < shells; shell++) {
            if (shellFaceCount[shell] > shellFaceCount[largestShell]) {
                largestShell = shell;
            }
        }
        // Containment decides only a shell the debris threshold keeps.
        boolean[] contained = new boolean[shells];
        for (int shell = 0; shell < shells; shell++) {
            if (shell != largestShell && shellFaceCount[shell] >= minShellFaces) {
                contained[shell] = insideAnother(shell, shellFirstFace[shell]);
            }
        }
        boolean[] kept = new boolean[shells];
        for (int shell = 0; shell < shells; shell++) {
            kept[shell] = shell == largestShell || shellFaceCount[shell] >= minShellFaces && !contained[shell];
            if (!kept[shell]) {
                report.droppedShellCount++;
                report.droppedShellFaceCount += shellFaceCount[shell];
            }
        }
        for (int face = 0; face < faceCount; face++) {
            if (shellOfFace[face] >= 0 && !kept[shellOfFace[face]]) {
                faceAlive[face] = false;
                touch(face);
            }
        }
        long[] order = new long[shells];
        for (int shell = 0; shell < shells; shell++) {
            order[shell] = ((long) (Integer.MAX_VALUE - shellFaceCount[shell]) << KEY_SHIFT) | shell;
        }
        Arrays.sort(order);
        report.shellFaceCounts = new int[shells];
        report.shellContained = new boolean[shells];
        report.shellKept = new boolean[shells];
        for (int index = 0; index < shells; index++) {
            int shell = (int) (order[index] & KEY_MASK);
            report.shellFaceCounts[index] = shellFaceCount[shell];
            report.shellContained[index] = contained[shell];
            report.shellKept[index] = kept[shell];
        }
    }

    /**
     * Whether a shell lies inside another: the majority of three axis rays from one of its faces
     * cross the other shells an odd number of times. A ray reads one column of orientation's grid
     * and tests only the faces added since.
     *
     * @param shell     the shell
     * @param firstFace a face of it the rays start from
     * @return true when it sits inside another shell
     */
    private boolean insideAnother(int shell, int firstFace) {
        // The grid's copy of the faces stands for the faces it was filed over: the split and
        // collapses since moved none far enough to change a majority, and the dead carry no shell.
        int covered = Math.min(faceCount, columnSoup.firstExposureOnly);
        double[] origin = new double[TRIANGLE_CORNERS];
        for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
            int vertex = faceCorners[firstFace * TRIANGLE_CORNERS + corner];
            for (int axis = 0; axis < TRIANGLE_CORNERS; axis++) {
                origin[axis] += positions[vertex * TRIANGLE_CORNERS + axis] / (double) TRIANGLE_CORNERS;
            }
        }
        int odd = 0;
        for (int axis = 0; axis < CONTAINMENT_RAYS; axis++) {
            int crossings = columnSoup.columnRayCrossings(axis, origin, shellOfFace, shell);
            for (int face = covered; face < faceCount; face++) {
                if (shellOfFace[face] >= 0 && shellOfFace[face] != shell
                        && TriangleGeometry.positiveAxisRayCrosses(axis, origin[0], origin[1], origin[2],
                                positions, faceCorners[face * TRIANGLE_CORNERS],
                                faceCorners[face * TRIANGLE_CORNERS + 1], faceCorners[face * TRIANGLE_CORNERS + 2])) {
                    crossings++;
                }
            }
            odd += crossings % 2;
        }
        return odd * 2 > CONTAINMENT_RAYS;
    }

    /**
     * Drops the pieces the cuts cut loose when every one is under {@link #minShellFaces}, which the
     * shell stage would drop enclosed or not; components are counted by shared corners.
     *
     * @return false when a piece large enough to keep was cut loose, which needs the shell stage's
     *         containment test
     */
    private boolean dropLooseDebris() {
        int[] component = UnionFind.singletons(vertexCount);
        for (int face = 0; face < faceCount; face++) {
            if (faceAlive[face]) {
                int slot = face * TRIANGLE_CORNERS;
                UnionFind.union(component, faceCorners[slot], faceCorners[slot + 1]);
                UnionFind.union(component, faceCorners[slot + 1], faceCorners[slot + 2]);
            }
        }
        int[] facesOfComponent = new int[vertexCount];
        for (int face = 0; face < faceCount; face++) {
            if (faceAlive[face]) {
                facesOfComponent[UnionFind.find(component, faceCorners[face * TRIANGLE_CORNERS])]++;
            }
        }
        int large = 0;
        int largest = 0;
        for (int root = 0; root < vertexCount; root++) {
            large += facesOfComponent[root] >= minShellFaces ? 1 : 0;
            largest = facesOfComponent[root] > facesOfComponent[largest] ? root : largest;
        }
        int kept = 0;
        for (boolean shellKept : report.shellKept) {
            kept += shellKept ? 1 : 0;
        }
        if (large > Math.max(1, kept)) {
            return false;
        }
        for (int face = 0; face < faceCount; face++) {
            int root = faceAlive[face] ? UnionFind.find(component, faceCorners[face * TRIANGLE_CORNERS]) : -1;
            if (root >= 0 && root != largest && facesOfComponent[root] < minShellFaces) {
                faceAlive[face] = false;
                touch(face);
                report.droppedShellFaceCount++;
                if (facesOfComponent[root] > 0) {
                    report.droppedShellCount++;
                    facesOfComponent[root] = -facesOfComponent[root];
                }
            }
        }
        return true;
    }

    /**
     * {@link #dropLooseDebris} after cuts, flooding out from the cut corners instead of over every
     * face.
     *
     * @param seeds     the corners of the faces cut since the last check, three per face
     * @param seedCount how many of them
     * @return the faces dropped, ascending; null when the floods cannot settle it cheaply or a
     *         loose piece is large enough to need the shells' containment test
     */
    private int[] dropDebrisNear(int[] seeds, int seedCount) {
        // Each seed vertex starts a flood and floods that meet join. A piece the cuts cut loose
        // keeps a corner of a face they deleted, so every piece holds a flood; a flood that runs
        // out of vertices has closed round a whole piece. Two pieces joined before the cuts were
        // joined through a chain of cut regions whose floods meet unless a piece in the chain is
        // still growing; so once each region is down to one growing flood, that flood is the piece
        // that was there before and only the closed ones are new.
        int[] vertexFlood = new int[vertexCount];
        Arrays.fill(vertexFlood, -1);
        int[] queue = new int[Math.max(SMALL_BUCKET, seedCount)];
        int tail = 0;
        for (int index = 0; index < seedCount; index++) {
            int vertex = seeds[index];
            if (vertexFlood[vertex] < 0) {
                vertexFlood[vertex] = tail;
                queue[tail++] = vertex;
            }
        }
        int floods = tail;
        // The cut regions: floods whose seeds share a cut face, and regions whose floods have met.
        int[] regionParent = UnionFind.singletons(floods);
        for (int index = 0; index + 2 < seedCount; index += TRIANGLE_CORNERS) {
            UnionFind.union(regionParent, vertexFlood[seeds[index]], vertexFlood[seeds[index + 1]]);
            UnionFind.union(regionParent, vertexFlood[seeds[index]], vertexFlood[seeds[index + 2]]);
        }
        long[] byRegion = new long[floods];
        int[] floodParent = UnionFind.singletons(floods);
        int[] pending = new int[floods];
        Arrays.fill(pending, 1);
        int[] floodFaces = new int[floods];
        int[] stamp = new int[floods];
        int checks = 0;
        int budget = vertexCount / DEBRIS_FLOOD_SHARE;
        // Vertices waiting to be expanded, and those set aside while their flood's region is
        // settled, so that once few regions are unsettled only their floods grow.
        int[] work = Arrays.copyOf(queue, Math.max(SMALL_BUCKET, tail));
        int workHead = 0;
        int workTail = tail;
        int[] aside = new int[SMALL_BUCKET];
        int asideCount = 0;
        boolean[] unsettledRoot = null;
        int[] regionRoots = new int[SMALL_BUCKET];
        int expanded = 0;
        int sinceCheck = Integer.MAX_VALUE;
        while (true) {
            if (workHead == workTail || sinceCheck >= Math.max(SMALL_BUCKET, floods) || expanded == budget) {
                // A region is settled once it has at most one growing flood and that one already
                // counts more faces than debris can have.
                sinceCheck = 0;
                for (int flood = 0; flood < floods; flood++) {
                    byRegion[flood] = ((long) UnionFind.find(regionParent, flood) << KEY_SHIFT) | flood;
                }
                Arrays.sort(byRegion);
                unsettledRoot = new boolean[floods];
                boolean settled = true;
                for (int start = 0; start < floods; ) {
                    int end = start;
                    while (end < floods && byRegion[end] >>> KEY_SHIFT == byRegion[start] >>> KEY_SHIFT) {
                        end++;
                    }
                    checks++;
                    int rootCount = 0;
                    boolean regionSettled = true;
                    for (int index = start; index < end; index++) {
                        int root = UnionFind.find(floodParent, (int) (byRegion[index] & KEY_MASK));
                        if (pending[root] == 0 || stamp[root] == checks) {
                            continue;
                        }
                        stamp[root] = checks;
                        if (rootCount == regionRoots.length) {
                            regionRoots = Arrays.copyOf(regionRoots, rootCount * GROWTH);
                        }
                        regionRoots[rootCount++] = root;
                        regionSettled &= rootCount == 1 && floodFaces[root] > minShellFaces;
                    }
                    for (int index = 0; index < rootCount && !regionSettled; index++) {
                        unsettledRoot[regionRoots[index]] = true;
                    }
                    settled &= regionSettled;
                    start = end;
                }
                if (settled) {
                    break;
                }
                int keptAside = 0;
                for (int index = 0; index < asideCount; index++) {
                    int vertex = aside[index];
                    if (!unsettledRoot[UnionFind.find(floodParent, vertexFlood[vertex])]) {
                        aside[keptAside++] = vertex;
                        continue;
                    }
                    if (workTail == work.length) {
                        work = Arrays.copyOf(work, workTail * GROWTH);
                    }
                    work[workTail++] = vertex;
                }
                asideCount = keptAside;
                if (workHead == workTail) {
                    return null;
                }
            }
            if (expanded >= budget) {
                return null;
            }
            int vertex = work[workHead++];
            int flood = UnionFind.find(floodParent, vertexFlood[vertex]);
            if (!unsettledRoot[flood]) {
                if (asideCount == aside.length) {
                    aside = Arrays.copyOf(aside, asideCount * GROWTH);
                }
                aside[asideCount++] = vertex;
                continue;
            }
            expanded++;
            sinceCheck++;
            int around = facesRound(vertex);
            for (int near = 0; near < around; near++) {
                int slot = aroundScratch[near] * TRIANGLE_CORNERS;
                // A face counts once, toward the flood of its first corner.
                floodFaces[flood] += faceCorners[slot] == vertex ? 1 : 0;
                for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                    int other = faceCorners[slot + corner];
                    if (vertexFlood[other] < 0) {
                        vertexFlood[other] = flood;
                        if (tail == queue.length) {
                            queue = Arrays.copyOf(queue, tail * GROWTH);
                        }
                        queue[tail++] = other;
                        if (workTail == work.length) {
                            work = Arrays.copyOf(work, workTail * GROWTH);
                        }
                        work[workTail++] = other;
                        pending[flood]++;
                        continue;
                    }
                    int met = UnionFind.find(floodParent, vertexFlood[other]);
                    if (met != flood) {
                        UnionFind.union(regionParent, flood, met);
                        UnionFind.union(floodParent, flood, met);
                        int joined = UnionFind.find(floodParent, flood);
                        pending[joined] = pending[flood] + pending[met];
                        floodFaces[joined] = floodFaces[flood] + floodFaces[met];
                        unsettledRoot[joined] = true;
                        flood = joined;
                    }
                }
            }
            if (--pending[flood] == 0 && floodFaces[flood] >= minShellFaces) {
                // A loose piece too large for debris needs the containment test.
                return null;
            }
        }
        // A closed piece below the debris size goes; a larger one needs the containment test.
        for (int flood = 0; flood < floods; flood++) {
            if (UnionFind.find(floodParent, flood) == flood && pending[flood] == 0
                    && floodFaces[flood] >= minShellFaces) {
                return null;
            }
        }
        int[] dropped = new int[SMALL_BUCKET];
        int droppedCount = 0;
        boolean[] counted = new boolean[floods];
        for (int index = 0; index < tail; index++) {
            int vertex = queue[index];
            int flood = UnionFind.find(floodParent, vertexFlood[vertex]);
            if (pending[flood] > 0) {
                continue;
            }
            int around = facesRound(vertex);
            for (int near = 0; near < around; near++) {
                int face = aroundScratch[near];
                if (faceCorners[face * TRIANGLE_CORNERS] != vertex) {
                    continue;
                }
                if (droppedCount == dropped.length) {
                    dropped = Arrays.copyOf(dropped, droppedCount * GROWTH);
                }
                dropped[droppedCount++] = face;
                if (!counted[flood]) {
                    counted[flood] = true;
                    report.droppedShellCount++;
                }
            }
        }
        dropped = Arrays.copyOf(dropped, droppedCount);
        Arrays.sort(dropped);
        for (int face : dropped) {
            faceAlive[face] = false;
            touch(face);
            report.droppedShellFaceCount++;
        }
        return dropped;
    }

    // ---------------------------------------------------------------------------------------
    // Holes

    /**
     * Fills every boundary loop of at most {@link #maxHoleEdges} edges with
     * {@link MeshHoleFiller}, and reports the larger ones as still open.
     *
     * @param openedByCuts whether every hole is one a pruning cut opened since the last fill
     */
    private void fillHoles(boolean openedByCuts) {
        ensureEdges();
        // A hole a cut opened has its sides among the edges whose uses changed since the last fill.
        int candidates = openedByCuts ? edges.changedEdgeCount() : edges.edgeCount();
        if (holeNext.length < vertexCount) {
            int old = holeNext.length;
            int capacity = Math.max(vertexCount, old + old / GROWTH);
            holeNext = Arrays.copyOf(holeNext, capacity);
            Arrays.fill(holeNext, old, capacity, -1);
            holeAmbiguous = Arrays.copyOf(holeAmbiguous, capacity);
            holeWalked = Arrays.copyOf(holeWalked, capacity);
            loopLocalOfVertex = Arrays.copyOf(loopLocalOfVertex, capacity);
            ringLocalOfVertex = Arrays.copyOf(ringLocalOfVertex, capacity);
            Arrays.fill(loopLocalOfVertex, old, capacity, -1);
            Arrays.fill(ringLocalOfVertex, old, capacity, -1);
        }
        int[] boundary = new int[SMALL_BUCKET];
        int boundaryCount = 0;
        for (int index = 0; index < candidates; index++) {
            int edge = openedByCuts ? edges.changedEdge(index) : index;
            if (edges.useCount(edge) != 1) {
                continue;
            }
            int slot = edges.use(edge, 0);
            int face = slot / TRIANGLE_CORNERS;
            int from = faceCorners[slot];
            int to = faceCorners[face * TRIANGLE_CORNERS + (slot % TRIANGLE_CORNERS + 1) % TRIANGLE_CORNERS];
            if (holeNext[to] >= 0) {
                holeAmbiguous[to] = true;
            } else {
                holeNext[to] = from;
                if (boundaryCount == boundary.length) {
                    boundary = Arrays.copyOf(boundary, boundaryCount * GROWTH);
                }
                boundary[boundaryCount++] = to;
            }
        }
        edges.clearChanged();
        boundary = Arrays.copyOf(boundary, boundaryCount);
        Arrays.sort(boundary);
        double[] vertexSigma = meanIncidentEdgeLengthNear(boundary);
        int[] neighbourStart = new int[0];
        int[] neighbours = new int[0];
        // A loop also walks the start of a dangling side, which is not a boundary end itself.
        int walkCap = boundaryCount + 1;
        int[] walkedVertices = new int[SMALL_BUCKET];
        int walkedCount = 0;
        int[] loop = new int[walkCap];
        int[] holeEdgeCounts = new int[walkCap];
        boolean[] holeFilled = new boolean[walkCap];
        double[] holePerimeters = new double[walkCap];
        int[] holeFillFaceCounts = new int[walkCap];
        PreparedFill[] fills = new PreparedFill[walkCap];
        int holes = 0;
        for (int start : boundary) {
            if (holeWalked[start]) {
                continue;
            }
            int length = 0;
            boolean clean = true;
            int vertex = start;
            do {
                if (holeWalked[vertex] || holeAmbiguous[vertex] || length >= walkCap) {
                    clean = false;
                    break;
                }
                holeWalked[vertex] = true;
                if (walkedCount == walkedVertices.length) {
                    walkedVertices = Arrays.copyOf(walkedVertices, walkedCount * GROWTH);
                }
                walkedVertices[walkedCount++] = vertex;
                loop[length++] = vertex;
                vertex = holeNext[vertex];
            } while (vertex != start && vertex >= 0);
            if (vertex != start) {
                clean = false;
            }
            holeEdgeCounts[holes] = length;
            holePerimeters[holes] = loopPerimeter(loop, length);
            if (clean && length <= maxHoleEdges) {
                if (neighbourStart.length == 0) {
                    if (neighbourScratch.length < vertexCount + 1) {
                        neighbourScratch = new int[Math.max(vertexCount + 1,
                                neighbourScratch.length + neighbourScratch.length / GROWTH)];
                    }
                    neighbourStart = neighbourScratch;
                    neighbours = vertexNeighboursNear(neighbourStart, boundary);
                }
                fills[holes] = prepareFill(loop, length, vertexSigma, neighbourStart, neighbours);
            }
            holes++;
        }
        for (int vertex : boundary) {
            holeNext[vertex] = -1;
            holeAmbiguous[vertex] = false;
        }
        for (int index = 0; index < walkedCount; index++) {
            holeWalked[walkedVertices[index]] = false;
        }
        // Each loop fills from its own corners and ring alone; the results join in loop order.
        IntStream.range(0, holes).parallel().forEach(hole -> {
            if (fills[hole] != null) {
                fills[hole].filler().build();
            }
        });
        for (int hole = 0; hole < holes; hole++) {
            boolean withinCap = fills[hole] != null;
            if (withinCap) {
                int fillFacesBefore = report.fillFaceCount;
                holeFilled[hole] = appendFill(fills[hole]);
                holeFillFaceCounts[hole] = report.fillFaceCount - fillFacesBefore;
            }
            if (holeFilled[hole]) {
                report.filledHoleCount++;
            } else {
                report.openHoleCount++;
                if (withinCap) {
                    report.unfillableHoleCount++;
                } else {
                    report.oversizedHoleCount++;
                }
            }
        }
        report.holeCount = holes;
        long[] order = new long[holes];
        for (int hole = 0; hole < holes; hole++) {
            order[hole] = ((long) (Integer.MAX_VALUE - holeEdgeCounts[hole]) << KEY_SHIFT) | hole;
        }
        Arrays.sort(order);
        report.holeEdgeCounts = new int[holes];
        report.holeFilled = new boolean[holes];
        report.holePerimeters = new double[holes];
        report.holeFillFaceCounts = new int[holes];
        for (int index = 0; index < holes; index++) {
            int hole = (int) (order[index] & KEY_MASK);
            report.holeEdgeCounts[index] = holeEdgeCounts[hole];
            report.holeFilled[index] = holeFilled[hole];
            report.holePerimeters[index] = holePerimeters[hole];
            report.holeFillFaceCounts[index] = holeFillFaceCounts[hole];
        }
    }

    /**
     * Perimeter of one boundary loop, summed over its edges in traversal order.
     *
     * @param loop   loop vertices in hole-traversal order
     * @param length vertices in the loop
     * @return summed edge length of the loop
     */
    private double loopPerimeter(int[] loop, int length) {
        double perimeter = 0;
        for (int index = 0; index < length; index++) {
            perimeter += Math.sqrt(squaredDistance(loop[index], loop[(index + 1) % length]));
        }
        return perimeter;
    }

    /**
     * Gathers what filling one boundary loop needs: its corners, the one-ring round it and the
     * chords the mesh already carries. The fill itself reads nothing shared, so prepared loops can
     * be filled on any thread.
     *
     * @param loop           loop vertices in hole-traversal order
     * @param length         vertices in the loop
     * @param vertexSigma    mean incident edge length per working vertex
     * @param neighbourStart vertex adjacency offsets
     * @param neighbours     vertex adjacency entries
     * @return the unbuilt filler and, per fixed local vertex, its working vertex
     */
    private PreparedFill prepareFill(int[] loop, int length, double[] vertexSigma, int[] neighbourStart,
            int[] neighbours) {
        MeshHoleFiller filler = new MeshHoleFiller();
        filler.loopLength = length;
        filler.loopX = new double[length];
        filler.loopY = new double[length];
        filler.loopZ = new double[length];
        filler.loopSigma = new double[length];
        for (int index = 0; index < length; index++) {
            int vertex = loop[index];
            filler.loopX[index] = positions[vertex * TRIANGLE_CORNERS];
            filler.loopY[index] = positions[vertex * TRIANGLE_CORNERS + 1];
            filler.loopZ[index] = positions[vertex * TRIANGLE_CORNERS + 2];
            filler.loopSigma[index] = vertexSigma[vertex];
            loopLocalOfVertex[vertex] = index;
        }
        int incidence = 0;
        for (int index = 0; index < length; index++) {
            incidence += neighbourStart[loop[index] + 1] - neighbourStart[loop[index]];
        }
        long[] forbidden = new long[incidence];
        int forbiddenCount = 0;
        long[] surfaceEdges = new long[incidence];
        int surfaceEdgeCount = 0;
        int[] ringVertices = new int[incidence];
        double[] ringX = new double[incidence];
        double[] ringY = new double[incidence];
        double[] ringZ = new double[incidence];
        int ringCount = 0;
        for (int index = 0; index < length; index++) {
            int vertex = loop[index];
            for (int cursor = neighbourStart[vertex]; cursor < neighbourStart[vertex + 1]; cursor++) {
                int neighbour = neighbours[cursor];
                int other = loopLocalOfVertex[neighbour];
                if (other >= 0) {
                    int gap = Math.abs(other - index);
                    if (gap != 1 && gap != length - 1) {
                        forbidden[forbiddenCount++] = EdgeKey.undirected(index, other);
                        if (index < other) {
                            surfaceEdges[surfaceEdgeCount++] = EdgeKey.undirected(index, other);
                        }
                    }
                    continue;
                }
                int ringLocal = ringLocalOfVertex[neighbour];
                if (ringLocal < 0) {
                    ringLocal = length + ringCount;
                    ringLocalOfVertex[neighbour] = ringLocal;
                    ringVertices[ringCount] = neighbour;
                    ringX[ringCount] = positions[neighbour * TRIANGLE_CORNERS];
                    ringY[ringCount] = positions[neighbour * TRIANGLE_CORNERS + 1];
                    ringZ[ringCount] = positions[neighbour * TRIANGLE_CORNERS + 2];
                    ringCount++;
                }
                surfaceEdges[surfaceEdgeCount++] = EdgeKey.undirected(index, ringLocal);
            }
        }
        for (int index = 0; index < length; index++) {
            loopLocalOfVertex[loop[index]] = -1;
        }
        for (int ring = 0; ring < ringCount; ring++) {
            ringLocalOfVertex[ringVertices[ring]] = -1;
        }
        filler.forbiddenChords = Arrays.copyOf(forbidden, forbiddenCount);
        Arrays.sort(filler.forbiddenChords);
        filler.surfaceEdges = Arrays.copyOf(surfaceEdges, surfaceEdgeCount);
        filler.ringCount = ringCount;
        filler.ringX = Arrays.copyOf(ringX, ringCount);
        filler.ringY = Arrays.copyOf(ringY, ringCount);
        filler.ringZ = Arrays.copyOf(ringZ, ringCount);
        int[] meshOfFixed = new int[length + ringCount];
        System.arraycopy(loop, 0, meshOfFixed, 0, length);
        System.arraycopy(ringVertices, 0, meshOfFixed, length, ringCount);
        return new PreparedFill(filler, meshOfFixed);
    }

    /**
     * Appends a built fill to the working mesh: its new vertices, then its triangles.
     *
     * @param fill a prepared loop whose filler has been built
     * @return whether the loop was filled
     */
    private boolean appendFill(PreparedFill fill) {
        MeshHoleFiller filler = fill.filler();
        if (!filler.filled) {
            return false;
        }
        int fixedCount = fill.meshOfFixed().length;
        int[] meshOfLocal = Arrays.copyOf(fill.meshOfFixed(), filler.vertexCount);
        for (int local = fixedCount; local < filler.vertexCount; local++) {
            meshOfLocal[local] = addVertex((float) filler.vertexX[local], (float) filler.vertexY[local],
                    (float) filler.vertexZ[local]);
            report.fillVertexCount++;
        }
        for (int triangle = 0; triangle < filler.triangleCount; triangle++) {
            addFace(meshOfLocal[filler.triangles[triangle * TRIANGLE_CORNERS]],
                    meshOfLocal[filler.triangles[triangle * TRIANGLE_CORNERS + 1]],
                    meshOfLocal[filler.triangles[triangle * TRIANGLE_CORNERS + 2]]);
            report.fillFaceCount++;
            if (cornerU != null) {
                report.unsetUvCornerCount += TRIANGLE_CORNERS;
            }
        }
        return true;
    }

    /**
     * The edges round one vertex, ascending, found through the faces filed round it.
     *
     * @param vertex working vertex
     * @return edge indices, ascending
     */
    private int[] edgesRound(int vertex) {
        int around = facesRound(vertex);
        int[] found = new int[around * 2];
        int count = 0;
        for (int index = 0; index < around; index++) {
            int slot = aroundScratch[index] * TRIANGLE_CORNERS;
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                if (faceCorners[slot + corner] == vertex) {
                    found[count++] = edges.find(vertex, faceCorners[slot + (corner + 1) % TRIANGLE_CORNERS]);
                    found[count++] = edges.find(vertex, faceCorners[slot + (corner + 2) % TRIANGLE_CORNERS]);
                }
            }
        }
        return Arrays.stream(found, 0, count).sorted().distinct().toArray();
    }

    /**
     * Mean length of the edges round each wanted vertex, the density the hole-fill refinement
     * matches.
     *
     * @param wanted the vertices the caller will read
     * @return per working vertex the mean length, written for the wanted ones only
     */
    private double[] meanIncidentEdgeLengthNear(int[] wanted) {
        if (sigmaScratch.length < vertexCount) {
            sigmaScratch = new double[Math.max(vertexCount, sigmaScratch.length * GROWTH)];
        }
        for (int vertex : wanted) {
            double total = 0;
            int[] incident = edgesRound(vertex);
            for (int edge : incident) {
                total += Math.sqrt(squaredDistance(EdgeKey.minVertex(edges.key(edge)),
                        EdgeKey.maxVertex(edges.key(edge))));
            }
            sigmaScratch[vertex] = incident.length == 0 ? 0 : total / incident.length;
        }
        return sigmaScratch;
    }

    /**
     * The wanted vertices' adjacency in compressed row form: each row lists the far ends of the
     * vertex's edges in edge order, rows in ascending vertex order.
     *
     * @param neighbourStart per working vertex plus a tail, written for the wanted ones
     * @param wanted         the vertices, ascending
     * @return the neighbour entries
     */
    private int[] vertexNeighboursNear(int[] neighbourStart, int[] wanted) {
        int[] neighbours = new int[SMALL_BUCKET];
        int count = 0;
        for (int vertex : wanted) {
            neighbourStart[vertex] = count;
            for (int edge : edgesRound(vertex)) {
                if (count == neighbours.length) {
                    neighbours = Arrays.copyOf(neighbours, count * GROWTH);
                }
                int low = EdgeKey.minVertex(edges.key(edge));
                neighbours[count++] = low == vertex ? EdgeKey.maxVertex(edges.key(edge)) : low;
            }
            neighbourStart[vertex + 1] = count;
        }
        return neighbours;
    }

    /**
     * One boundary loop ready to fill: its filler, and the working vertex of each fixed local
     * vertex, the loop's then its ring's.
     *
     * @param filler      the loop's filler, built or not
     * @param meshOfFixed working vertex per fixed local vertex
     */
    private record PreparedFill(MeshHoleFiller filler, int[] meshOfFixed) {
    }

    // ---------------------------------------------------------------------------------------
    // Prune

    /**
     * A ray tree over the alive faces as they stand, on its own copy of their corners so later
     * corner changes leave it intact; built by the caller.
     *
     * @return the unbuilt tree, with no face retired
     */
    private TriangleBvh rayTree() {
        TriangleBvh tree = new TriangleBvh();
        tree.leafTriangles = RAY_LEAF_TRIANGLES;
        tree.positions = positions;
        tree.triangleCorners = Arrays.copyOf(faceCorners, faceCount * TRIANGLE_CORNERS);
        tree.triangleCount = faceCount;
        tree.triangleGroup = new int[faceCount];
        for (int face = 0; face < faceCount; face++) {
            tree.triangleGroup[face] = faceAlive[face] ? face : -1;
        }
        tree.parallelBuild = true;
        tree.retired = new boolean[faceCount];
        return tree;
    }

    /**
     * On a closed surface, deletes each face whose back is seen from outside, straight on or at a
     * slant, with a ring that widens every round, and refills until none is.
     *
     * @param tree        built ray tree over the faces before the holes were filled
     * @param creasedThen crease flags over those faces
     * @param cellsBefore the grid cells the faces changed since orientation reached before the fill
     */
    private void pruneExposedFaces(TriangleBvh tree, boolean[] creasedThen, int[] cellsBefore) {
        if (report.openHoleCount > 0 || orientedFacing == null) {
            return;
        }
        // A face whose back lies in a cell of nonzero winding number is backed by solid: every ray
        // out of that cell crosses the surface, so only the faces backed by winding zero cast the
        // hemisphere. Orientation's count says which those are for every face nothing has changed
        // near since; the rest count by two winding rays.
        ensureEdges();
        int treeFaces = tree.triangleCount;
        // The fills join the tree as its companion and the crease flags as faces judged near them.
        tree.companion = treeOverFrom(treeFaces);
        boolean[] creased = foldedBackNear(treeFaces, creasedThen);
        creaseJudged = new int[0];
        // Orientation summed every face's area; only the faces changed or added since move the sum.
        double totalArea = orientedTotalArea;
        int areaFaces = orientedAreaFaces;
        for (int index = 0; index < changedSinceCount; index++) {
            int face = changedSince[index];
            // The soup files a face orientation met dead under a negative group.
            if (columnSoup.triangleGroup[face] >= 0) {
                totalArea -= orientedArea[face];
                areaFaces--;
            }
            if (faceAlive[face]) {
                totalArea += faceArea(face);
                areaFaces++;
            }
        }
        for (int face = orientedFaces; face < faceCount; face++) {
            if (faceAlive[face]) {
                totalArea += faceArea(face);
                areaFaces++;
            }
        }
        TriangleBvh grid = columnSoup;
        // The cells the fill's own faces reach, joined to those found while it ran.
        int[] cellsOfFill = grid.cellsUnder(cornersWhere(face -> faceAlive[face], treeFaces));
        int[] cellsChanged = IntStream.concat(Arrays.stream(cellsBefore), Arrays.stream(cellsOfFill)).sorted()
                .distinct().toArray();
        double tinyDoubleArea = 2 * TINY_FACE_FRACTION * totalArea / Math.max(1, areaFaces);
        int[] verdict = new int[faceCount];
        int[][] blockers = new int[faceCount][];
        boolean[] seenThisRound = new boolean[faceCount];
        boolean[] marked = new boolean[vertexCount];
        int[] parityCell = new int[faceCount];
        TriangleBvh changed = null;
        int[] changedColumns = cellsChanged;
        int countedBelow = 0;
        boolean shellsChecked = false;
        // Only a cut whose region leaves two or more loops, or a pinch the split pulls apart, can
        // cut a piece loose; otherwise the shells stand as the shell stage left them.
        boolean mayDetach = false;
        // Every face a count vouches for, filed by the column its counting line runs in. A cut and
        // its refill share a rim and so enclose a pocket; only a face inside it can count
        // differently, and such a face's line runs in a column the change covers. Round 0 files
        // every face, each later round the faces it recounted.
        List<long[]> byColumn = new ArrayList<>();
        // Per face, the faces whose hemisphere it blocks, chained: deleting a face wakes exactly
        // those, and adding faces can only block more.
        int[] blockingHead = new int[faceCount];
        Arrays.fill(blockingHead, -1);
        int[] blockingNext = new int[SMALL_BUCKET];
        int[] blockedFace = new int[SMALL_BUCKET];
        int blockingCount = 0;
        int[] woken = new int[0];
        boolean[] recounted = new boolean[faceCount];
        // The corners of the faces cut since the debris was last checked, three per face.
        int[] debrisSeeds = new int[SMALL_BUCKET];
        int debrisSeedCount = 0;
        boolean buriedChecked = false;
        for (int round = 0; round < MAX_PRUNE_ROUNDS; round++) {
            int[] candidates = changed == null ? null
                    : roundCandidates(countedBelow, byColumn, changedColumns, woken);
            int judging = candidates == null ? faceCount : candidates.length;
            if (verdict.length < faceCount) {
                int capacity = Math.max(faceCount, verdict.length + verdict.length / GROWTH);
                verdict = Arrays.copyOf(verdict, capacity);
                blockers = Arrays.copyOf(blockers, capacity);
                seenThisRound = Arrays.copyOf(seenThisRound, capacity);
                parityCell = Arrays.copyOf(parityCell, capacity);
                recounted = Arrays.copyOf(recounted, capacity);
                int old = blockingHead.length;
                blockingHead = Arrays.copyOf(blockingHead, capacity);
                Arrays.fill(blockingHead, old, capacity, -1);
            }
            RoundState state = new RoundState(tree, grid, changed, changedColumns, countedBelow, creased,
                    tinyDoubleArea, parityCell, verdict, blockers, seenThisRound, candidates, recounted);
            IntStream.range(0, (judging + FACES_PER_RAY_TASK - 1) / FACES_PER_RAY_TASK).parallel()
                    .forEach(task -> castBackRays(state, task * FACES_PER_RAY_TASK,
                            Math.min(judging, (task + 1) * FACES_PER_RAY_TASK)));
            countedBelow = faceCount;
            // File what this round recounted: count-vouched faces by column, blocked faces under
            // each face that stopped one of their rays.
            long[] filed = new long[SMALL_BUCKET];
            int filedCount = 0;
            for (int index = 0; index < judging; index++) {
                int face = candidates == null ? index : candidates[index];
                if (!recounted[face]) {
                    continue;
                }
                recounted[face] = false;
                if (!faceAlive[face]) {
                    continue;
                }
                if (verdict[face] == VOUCHED_BY_PARITY || verdict[face] == VOUCHED_BY_RAYS) {
                    if (filedCount == filed.length) {
                        filed = Arrays.copyOf(filed, filedCount * GROWTH);
                    }
                    filed[filedCount++] = ((long) parityCell[face] << KEY_SHIFT) | face;
                } else if (verdict[face] == HEMISPHERE_BLOCKED) {
                    for (int blocker : blockers[face]) {
                        if (blocker < 0 || blocker >= blockingHead.length) {
                            continue;
                        }
                        if (blockingCount == blockedFace.length) {
                            blockedFace = Arrays.copyOf(blockedFace, blockingCount * GROWTH);
                            blockingNext = Arrays.copyOf(blockingNext, blockingCount * GROWTH);
                        }
                        blockedFace[blockingCount] = face;
                        blockingNext[blockingCount] = blockingHead[blocker];
                        blockingHead[blocker] = blockingCount++;
                    }
                }
            }
            long[] filedRound = Arrays.copyOf(filed, filedCount);
            Arrays.sort(filedRound);
            byColumn.add(filedRound);
            if (marked.length < vertexCount) {
                marked = Arrays.copyOf(marked, Math.max(vertexCount, marked.length + marked.length / GROWTH));
            }
            int[] markedList = new int[SMALL_BUCKET];
            int markedCount = 0;
            int exposed = 0;
            for (int index = 0; index < judging; index++) {
                int face = candidates == null ? index : candidates[index];
                boolean faceSeen = seenThisRound[face];
                seenThisRound[face] = false;
                if (faceAlive[face] && faceSeen) {
                    exposed++;
                    for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                        int vertex = faceCorners[face * TRIANGLE_CORNERS + corner];
                        if (!marked[vertex]) {
                            marked[vertex] = true;
                            if (markedCount == markedList.length) {
                                markedList = Arrays.copyOf(markedList, markedCount * GROWTH);
                            }
                            markedList[markedCount++] = vertex;
                        }
                    }
                }
            }
            // Only once no back shows does the surface say what is inside it: the sheets sealed in
            // the body then go, cut exactly, their necks refilled and judged like any refill.
            boolean buriedCut = false;
            if (exposed == 0 && !buriedChecked) {
                buriedChecked = true;
                int[] buried = buriedFaces(tree);
                for (int face : buried) {
                    for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                        int vertex = faceCorners[face * TRIANGLE_CORNERS + corner];
                        if (!marked[vertex]) {
                            marked[vertex] = true;
                            if (markedCount == markedList.length) {
                                markedList = Arrays.copyOf(markedList, markedCount * GROWTH);
                            }
                            markedList[markedCount++] = vertex;
                        }
                    }
                }
                buriedCut = buried.length > 0;
            }
            if (!buriedCut && exposed == 0 && (shellsChecked || !mayDetach)) {
                return;
            }
            if (!buriedCut && exposed == 0) {
                // Debris the cuts cut loose goes once the faces are settled; a drop is a change,
                // so one more round judges what it uncovered.
                shellsChecked = true;
                // Flooded out from the cut corners first; only when the floods cannot settle it does
                // a pass over every face decide.
                int[] droppedFaces = dropDebrisNear(debrisSeeds, debrisSeedCount);
                debrisSeedCount = 0;
                if (droppedFaces == null) {
                    boolean[] aliveBeforeShells = faceAlive.clone();
                    if (!dropLooseDebris()) {
                        classifyShells();
                    }
                    droppedFaces = IntStream.range(0, Math.min(faceCount, aliveBeforeShells.length))
                            .filter(face -> aliveBeforeShells[face] && !faceAlive[face]).toArray();
                }
                float[] dropped = cornersOf(droppedFaces);
                IntStream.Builder wake = IntStream.builder();
                for (int face : droppedFaces) {
                    if (face < treeFaces) {
                        tree.retired[face] = true;
                    }
                    for (int entry = blockingHead[face]; entry >= 0; entry = blockingNext[entry]) {
                        wake.add(blockedFace[entry]);
                    }
                    blockingHead[face] = -1;
                }
                woken = wake.build().toArray();
                if (dropped.length == 0) {
                    return;
                }
                changed = treeOver(dropped);
                // A dropped shell is closed, so it crossed every line an even number of times and
                // no face's parity moves; only ray evidence needs judging against it.
                changedColumns = new int[0];
                tree.companion = treeOverFrom(treeFaces);
                continue;
            }
            shellsChecked = false;
            report.exposedFaceCount += exposed;
            report.pruneRoundCount++;
            // Widening the cut each round lets a fold that keeps poking out be cut back far enough
            // that its refill ends up inside the skin.
            ensureEdges();
            int frontierStart = 0;
            for (int ring = 0; ring < (buriedCut ? 0 : FIRST_PRUNE_RINGS + round); ring++) {
                int frontierEnd = markedCount;
                for (int index = frontierStart; index < frontierEnd; index++) {
                    int around = facesRound(markedList[index]);
                    for (int near = 0; near < around; near++) {
                        for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                            int vertex = faceCorners[aroundScratch[near] * TRIANGLE_CORNERS + corner];
                            if (!marked[vertex]) {
                                marked[vertex] = true;
                                if (markedCount == markedList.length) {
                                    markedList = Arrays.copyOf(markedList, markedCount * GROWTH);
                                }
                                markedList[markedCount++] = vertex;
                            }
                        }
                    }
                }
                frontierStart = frontierEnd;
            }
            int[] cut = new int[SMALL_BUCKET];
            int cutCount = 0;
            for (int index = 0; index < markedCount; index++) {
                int around = facesRound(markedList[index]);
                for (int near = 0; near < around; near++) {
                    int face = aroundScratch[near];
                    int slot = face * TRIANGLE_CORNERS;
                    if (faceAlive[face] && marked[faceCorners[slot]] && marked[faceCorners[slot + 1]]
                            && marked[faceCorners[slot + 2]]) {
                        faceAlive[face] = false;
                        touch(face);
                        report.prunedFaceCount++;
                        if (cutCount == cut.length) {
                            cut = Arrays.copyOf(cut, cutCount * GROWTH);
                        }
                        cut[cutCount++] = face;
                    }
                }
            }
            cut = Arrays.copyOf(cut, cutCount);
            Arrays.sort(cut);
            float[] changedCorners = cornersOf(cut);
            IntStream.Builder wake = IntStream.builder();
            for (int face : cut) {
                if (face < treeFaces) {
                    tree.retired[face] = true;
                }
                for (int entry = blockingHead[face]; entry >= 0; entry = blockingNext[entry]) {
                    wake.add(blockedFace[entry]);
                }
                blockingHead[face] = -1;
                // Every piece a cut can cut loose keeps a corner of a face it deleted.
                if (debrisSeedCount + TRIANGLE_CORNERS > debrisSeeds.length) {
                    debrisSeeds = Arrays.copyOf(debrisSeeds, (debrisSeedCount + TRIANGLE_CORNERS) * GROWTH);
                }
                System.arraycopy(faceCorners, face * TRIANGLE_CORNERS, debrisSeeds, debrisSeedCount, TRIANGLE_CORNERS);
                debrisSeedCount += TRIANGLE_CORNERS;
            }
            woken = wake.build().toArray();
            mayDetach |= cutLeavesSeveralLoops(cut);
            mayDetach |= splitPinchedVertices(markedList, markedCount);
            int firstRefill = faceCount;
            refillCuts();
            if (report.refillOpenHoleCount > 0) {
                return;
            }
            float[] changedAll = append(changedCorners, cornersWhere(face -> faceAlive[face], firstRefill));
            changed = treeOver(changedAll);
            changedColumns = grid.cellsUnder(changedAll);
            tree.companion = treeOverFrom(treeFaces);
            creased = foldedBackNear(firstRefill, creased);
            for (int index = 0; index < markedCount; index++) {
                marked[markedList[index]] = false;
            }
        }
    }

    /**
     * Fills the holes the pruning's cuts opened, counting them as refills and leaving the scan's
     * own hole records as they were.
     */
    private void refillCuts() {
        int[] scanHoleEdgeCounts = report.holeEdgeCounts;
        boolean[] scanHoleFilled = report.holeFilled;
        double[] scanHolePerimeters = report.holePerimeters;
        int[] scanHoleFillFaceCounts = report.holeFillFaceCounts;
        int scanHoles = report.holeCount;
        int filledBefore = report.filledHoleCount;
        fillHoles(true);
        report.refilledHoleCount += report.filledHoleCount - filledBefore;
        report.refillOpenHoleCount += report.openHoleCount;
        report.filledHoleCount = filledBefore;
        report.openHoleCount = 0;
        report.oversizedHoleCount = 0;
        report.unfillableHoleCount = 0;
        report.holeCount = scanHoles;
        report.holeEdgeCounts = scanHoleEdgeCounts;
        report.holeFilled = scanHoleFilled;
        report.holePerimeters = scanHolePerimeters;
        report.holeFillFaceCounts = scanHoleFillFaceCounts;
    }

    /**
     * The faces sealed inside the body. Such sheets hang where the non-manifold split pulled a fin
     * off the skin, or are fills that capped one, so they are sought among the faces round the
     * split's vertices and the fills, then flooded out across edges.
     *
     * @param tree the pruning's ray tree, its companion holding the fills
     * @return the sealed faces, ascending
     */
    private int[] buriedFaces(TriangleBvh tree) {
        ensureEdges();
        int stackSize = Math.max(tree.nodeCount, tree.companion == null ? 0 : tree.companion.nodeCount) + 1;
        IntStream.Builder seeds = IntStream.builder();
        for (int index = 0; index < splitVertexCount; index++) {
            int around = facesRound(splitVertices[index]);
            for (int near = 0; near < around; near++) {
                seeds.add(aroundScratch[near]);
            }
        }
        for (int face = firstFillFace; face < faceCount; face++) {
            seeds.add(face);
        }
        int[] candidates = seeds.build().filter(face -> faceAlive[face]).sorted().distinct().toArray();
        boolean[] sealed = new boolean[candidates.length];
        IntStream.range(0, (candidates.length + FACES_PER_RAY_TASK - 1) / FACES_PER_RAY_TASK).parallel()
                .forEach(task -> {
                    int[] stack = new int[stackSize];
                    for (int index = task * FACES_PER_RAY_TASK;
                            index < Math.min(candidates.length, (task + 1) * FACES_PER_RAY_TASK); index++) {
                        sealed[index] = sealedIn(tree, candidates[index], stack);
                    }
                });
        boolean[] judged = new boolean[faceCount];
        int[] found = new int[SMALL_BUCKET];
        int foundCount = 0;
        for (int index = 0; index < candidates.length; index++) {
            judged[candidates[index]] = true;
            if (sealed[index]) {
                if (foundCount == found.length) {
                    found = Arrays.copyOf(found, foundCount * GROWTH);
                }
                found[foundCount++] = candidates[index];
            }
        }
        int[] stack = new int[stackSize];
        for (int next = 0; next < foundCount; next++) {
            int face = found[next];
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int edge = edges.edgeOfSlot(face * TRIANGLE_CORNERS + corner);
                for (int use = 0; use < edges.useCount(edge); use++) {
                    int other = edges.use(edge, use) / TRIANGLE_CORNERS;
                    if (judged[other] || !faceAlive[other]) {
                        continue;
                    }
                    judged[other] = true;
                    if (sealedIn(tree, other, stack)) {
                        if (foundCount == found.length) {
                            found = Arrays.copyOf(found, foundCount * GROWTH);
                        }
                        found[foundCount++] = other;
                    }
                }
            }
        }
        // A region of fill alone only capped a hole: deleting it reopens that hole and the refill
        // builds the same cap again, so only regions holding scan faces go.
        boolean[] inRegion = new boolean[faceCount];
        for (int index = 0; index < foundCount; index++) {
            inRegion[found[index]] = true;
        }
        boolean[] visited = new boolean[faceCount];
        int[] region = new int[SMALL_BUCKET];
        IntStream.Builder kept = IntStream.builder();
        for (int index = 0; index < foundCount; index++) {
            if (visited[found[index]]) {
                continue;
            }
            visited[found[index]] = true;
            region[0] = found[index];
            int size = 1;
            boolean holdsScan = false;
            for (int next = 0; next < size; next++) {
                int face = region[next];
                holdsScan |= face < firstFillFace;
                for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                    int edge = edges.edgeOfSlot(face * TRIANGLE_CORNERS + corner);
                    for (int use = 0; use < edges.useCount(edge); use++) {
                        int other = edges.use(edge, use) / TRIANGLE_CORNERS;
                        if (inRegion[other] && !visited[other]) {
                            visited[other] = true;
                            if (size == region.length) {
                                region = Arrays.copyOf(region, size * GROWTH);
                            }
                            region[size++] = other;
                        }
                    }
                }
            }
            if (holdsScan) {
                for (int member = 0; member < size; member++) {
                    kept.add(region[member]);
                }
            }
        }
        return kept.build().sorted().toArray();
    }

    /**
     * Whether a face is sealed inside the body: two rays agree that solid lies in front of it, and
     * no front hemisphere ray escapes. The winding spares a skin face deep in a crevice.
     *
     * @param tree  the pruning's ray tree
     * @param face  working face
     * @param stack scratch node stack
     * @return true when the face is hidden from both sides
     */
    private boolean sealedIn(TriangleBvh tree, int face, int[] stack) {
        float[] normal = new float[TRIANGLE_CORNERS];
        float[] centroid = new float[TRIANGLE_CORNERS];
        unitNormal(faceCorners, positions, face, normal, 0);
        for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
            int at = faceCorners[face * TRIANGLE_CORNERS + corner] * TRIANGLE_CORNERS;
            for (int axis = 0; axis < TRIANGLE_CORNERS; axis++) {
                centroid[axis] += positions[at + axis] / TRIANGLE_CORNERS;
            }
        }
        return tree.agreedWinding(centroid, normal, stack) != 0 && !tree.hemisphereEscapes(centroid, normal, stack);
    }

    /**
     * The corner positions of every face orientation counted that died or moved a corner since,
     * as orientation's soup holds them and as they stand, and of every alive face added since: the
     * parity lines that run near any of them may count differently now.
     *
     * @return nine floats per triangle
     */
    private float[] changedSinceOrientationCorners() {
        int added = 0;
        for (int face = orientedFaces; face < faceCount; face++) {
            added += faceAlive[face] ? 1 : 0;
        }
        float[] corners = new float[(changedSinceCount * 2 + added) * TRIANGLE_CORNERS * TRIANGLE_CORNERS];
        int next = 0;
        int[] before = columnSoup.triangleCorners;
        float[] at = columnSoup.positions;
        for (int index = 0; index < changedSinceCount; index++) {
            int face = changedSince[index];
            for (int corner = 0; corner < TRIANGLE_CORNERS && columnSoup.triangleGroup[face] >= 0; corner++) {
                System.arraycopy(at, before[face * TRIANGLE_CORNERS + corner] * TRIANGLE_CORNERS, corners, next,
                        TRIANGLE_CORNERS);
                next += TRIANGLE_CORNERS;
            }
            for (int corner = 0; corner < TRIANGLE_CORNERS && faceAlive[face]; corner++) {
                System.arraycopy(positions, faceCorners[face * TRIANGLE_CORNERS + corner] * TRIANGLE_CORNERS,
                        corners, next, TRIANGLE_CORNERS);
                next += TRIANGLE_CORNERS;
            }
        }
        for (int face = orientedFaces; face < faceCount; face++) {
            for (int corner = 0; corner < TRIANGLE_CORNERS && faceAlive[face]; corner++) {
                System.arraycopy(positions, faceCorners[face * TRIANGLE_CORNERS + corner] * TRIANGLE_CORNERS,
                        corners, next, TRIANGLE_CORNERS);
                next += TRIANGLE_CORNERS;
            }
        }
        return Arrays.copyOf(corners, next);
    }

    /**
     * What one pruning round's ray tasks read and write.
     *
     * @param tree        tree over the faces as pruning began, with the dead retired and a
     *                    companion over the faces added since
     * @param grid        orientation's soup, whose column grid names each parity line's cell
     * @param changed     tree over the faces that died or were added since the last round, or null
     *                    in the first round
     * @param changedColumns the grid cells the changed faces overlap, ascending
     * @param recountFrom faces from this index on are new and always judged
     * @param creased     per face, whether a face round one of its corners folds back over it
     * @param tinyDoubleArea twice the area under which a face is too small to vouch for by count
     * @param parityCell  per face, its parity line's column times three plus axis
     * @param verdict     what vouches for each face's back
     * @param blockedBy   per hemisphere-blocked face, the face that stopped each of its rays
     * @param seen        per face, whether its back was seen this round
     * @param candidates  the faces the round judges, or null for every face
     * @param recounted   per face, set when the round recounted it rather than let its verdict stand
     */
    private record RoundState(TriangleBvh tree, TriangleBvh grid, TriangleBvh changed, int[] changedColumns,
            int recountFrom, boolean[] creased, double tinyDoubleArea, int[] parityCell, int[] verdict,
            int[][] blockedBy, boolean[] seen, int[] candidates, boolean[] recounted) {
    }

    /**
     * For each alive face in a run, recounts what vouches for its back when needed and, where its
     * winding is zero, casts into the back hemisphere and marks it seen if a ray escapes.
     *
     * @param state the round
     * @param first first entry of the run
     * @param end   one past the last entry
     */
    private void castBackRays(RoundState state, int first, int end) {
        TriangleBvh tree = state.tree();
        TriangleBvh changed = state.changed();
        int need = Math.max(1, Math.max(tree.nodeCount, Math.max(changed == null ? 0 : changed.nodeCount,
                tree.companion == null ? 0 : tree.companion.nodeCount)));
        int[] stack = rayStacks.get();
        if (stack.length < need) {
            stack = new int[need];
            rayStacks.set(stack);
        }
        int[] verdict = state.verdict();
        float[] centroid = new float[TRIANGLE_CORNERS];
        float[] back = new float[TRIANGLE_CORNERS];
        int[] nearby = new int[CORNER_FACES_TRIED_FIRST];
        for (int index = first; index < end; index++) {
            int face = state.candidates() == null ? index : state.candidates()[index];
            if (!faceAlive[face]) {
                continue;
            }
            if (changed != null && face < state.recountFrom()
                    && (verdict[face] == VOUCHED_BY_PARITY || verdict[face] == VOUCHED_BY_RAYS)
                    && !state.creased()[face] && !columnMarked(state.changedColumns(), state.parityCell()[face])) {
                continue;
            }
            int a = faceCorners[face * TRIANGLE_CORNERS] * TRIANGLE_CORNERS;
            int b = faceCorners[face * TRIANGLE_CORNERS + 1] * TRIANGLE_CORNERS;
            int c = faceCorners[face * TRIANGLE_CORNERS + 2] * TRIANGLE_CORNERS;
            double alongX = positions[b] - positions[a];
            double alongY = positions[b + 1] - positions[a + 1];
            double alongZ = positions[b + 2] - positions[a + 2];
            double acrossX = positions[c] - positions[a];
            double acrossY = positions[c + 1] - positions[a + 1];
            double acrossZ = positions[c + 2] - positions[a + 2];
            double normalX = alongY * acrossZ - alongZ * acrossY;
            double normalY = alongZ * acrossX - alongX * acrossZ;
            double normalZ = alongX * acrossY - alongY * acrossX;
            double length = Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
            if (length == 0.0) {
                continue;
            }
            for (int axis = 0; axis < TRIANGLE_CORNERS; axis++) {
                centroid[axis] = (positions[a + axis] + positions[b + axis] + positions[c + axis]) / TRIANGLE_CORNERS;
            }
            back[0] = (float) (-normalX / length);
            back[1] = (float) (-normalY / length);
            back[2] = (float) (-normalZ / length);
            double longest = Math.max(alongX * alongX + alongY * alongY + alongZ * alongZ,
                    Math.max(acrossX * acrossX + acrossY * acrossY + acrossZ * acrossZ,
                            (acrossX - alongX) * (acrossX - alongX) + (acrossY - alongY) * (acrossY - alongY)
                                    + (acrossZ - alongZ) * (acrossZ - alongZ)));
            // A sliver's normal is rounding noise, and past a fold its grazing rays leave a
            // different cell than the counted ones, so no count can vouch for either.
            boolean countable = length >= SLIVER_RATIO * longest && length >= state.tinyDoubleArea()
                    && !state.creased()[face];
            int lineAxis = Math.abs(normalX) >= Math.abs(normalY) && Math.abs(normalX) >= Math.abs(normalZ) ? 0
                    : Math.abs(normalY) >= Math.abs(normalZ) ? 1 : 2;
            if (changed != null && face < state.recountFrom()) {
                // Evidence holds until a changed face lands on the line or rays it rests on.
                boolean stands = switch (verdict[face]) {
                    case VOUCHED_BY_PARITY -> countable && !meetsAxisLine(state.grid(), changed,
                            state.changedColumns(), centroid, lineAxis, stack);
                    case VOUCHED_BY_RAYS -> countable && !changed.meetsWindingRays(centroid, back, stack);
                    case HEMISPHERE_BLOCKED -> {
                        // Faces added since can only stop more rays, so it stays blocked while
                        // every face that stopped one of its rays stands.
                        boolean blockersStand = true;
                        for (int blocker : state.blockedBy()[face]) {
                            blockersStand &= faceAlive[blocker];
                        }
                        yield blockersStand;
                    }
                    default -> false;
                };
                if (stands) {
                    continue;
                }
            }
            state.recounted()[face] = true;
            int cell = state.grid().columnAt(lineAxis, centroid) * TRIANGLE_CORNERS + lineAxis;
            state.parityCell()[face] = cell;
            if (!countable) {
                verdict[face] = UNVOUCHED;
            } else if (changed == null) {
                // Orientation's count stands where nothing has changed on or near the line since.
                boolean vouched = face < orientedFaces && !changedSinceOrientation[face]
                        && orientedFacing[face] == TriangleBvh.FACING_OUT_OF_VOLUME && !orientedCrowded[face]
                        && !columnMarked(state.changedColumns(), cell);
                verdict[face] = vouched ? VOUCHED_BY_PARITY
                        : tree.agreedWinding(centroid, back, stack) != 0 ? VOUCHED_BY_RAYS : UNVOUCHED;
            } else {
                verdict[face] = tree.agreedWinding(centroid, back, stack) != 0 ? VOUCHED_BY_RAYS : UNVOUCHED;
            }
            if (verdict[face] == UNVOUCHED) {
                // At a fold the faces round the corners are what most rays meet first, so each ray
                // tries them before walking the tree; a hit on one is a hit, so the answer is the
                // walk's.
                int nearbyCount = 0;
                for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                    nearbyCount = facesRoundInto(faceCorners[face * TRIANGLE_CORNERS + corner], face, nearby,
                            nearbyCount);
                }
                int[] blockers = new int[TriangleBvh.HEMISPHERE_RAYS];
                boolean faceSeen = tree.hemisphereEscapes(centroid, back, stack, blockers, nearby, nearbyCount);
                state.seen()[face] = faceSeen;
                verdict[face] = faceSeen ? UNVOUCHED : HEMISPHERE_BLOCKED;
                state.blockedBy()[face] = faceSeen ? null : blockers;
            }
        }
    }

    /**
     * Whether a tree has a triangle on the axis line a face's parity was read along.
     *
     * @param grid        the soup whose column grids name the cells
     * @param tree        tree to test
     * @param treeColumns the grid cells the tree's triangles overlap, ascending
     * @param centroid    the face's centroid
     * @param axis        the line's axis
     * @param stack       scratch node stack
     * @return true when either half of the line meets a triangle
     */
    private static boolean meetsAxisLine(TriangleBvh grid, TriangleBvh tree, int[] treeColumns, float[] centroid,
            int axis, int[] stack) {
        if (!columnMarked(treeColumns, grid.columnAt(axis, centroid) * TRIANGLE_CORNERS + axis)) {
            return false;
        }
        float[] along = new float[TRIANGLE_CORNERS];
        along[axis] = 1;
        if (tree.anyHit(centroid, along, stack) >= 0) {
            return true;
        }
        along[axis] = -1;
        return tree.anyHit(centroid, along, stack) >= 0;
    }

    /**
     * Whether a sorted cell list holds one cell.
     *
     * @param cells cells, column times three plus axis, ascending
     * @param cell  the cell
     * @return true when listed
     */
    private static boolean columnMarked(int[] cells, int cell) {
        return Arrays.binarySearch(cells, cell) >= 0;
    }

    /**
     * The faces a later pruning round must judge, ascending: those added since the last round,
     * those the refill's crease check judged, the blocked ones whose blocker died, and the
     * count-vouched ones whose line runs in a column a changed face reaches.
     *
     * @param recountFrom    faces from this index on were added since the last round
     * @param byColumn       per round, {@code (column, face)} pairs of the faces it count-vouched, sorted
     * @param changedColumns the columns the changed faces reach, ascending
     * @param woken          the blocked faces whose blocker died
     * @return the faces, each once
     */
    private int[] roundCandidates(int recountFrom, List<long[]> byColumn, int[] changedColumns, int[] woken) {
        int[] found = new int[(faceCount - recountFrom) + creaseJudged.length + woken.length + SMALL_BUCKET];
        int count = 0;
        for (int face = recountFrom; face < faceCount; face++) {
            found[count++] = face;
        }
        for (int face : creaseJudged) {
            found[count++] = face;
        }
        creaseJudged = new int[0];
        for (int face : woken) {
            found[count++] = face;
        }
        for (long[] filed : byColumn) {
            for (int cell : changedColumns) {
                int at = Arrays.binarySearch(filed, (long) cell << KEY_SHIFT);
                for (int index = at < 0 ? -at - 1 : at; index < filed.length && filed[index] >>> KEY_SHIFT == cell;
                        index++) {
                    if (count == found.length) {
                        found = Arrays.copyOf(found, count * GROWTH);
                    }
                    found[count++] = (int) (filed[index] & KEY_MASK);
                }
            }
        }
        return Arrays.stream(found, 0, count).sorted().distinct().toArray();
    }

    /**
     * Whether some edge-connected region of the faces a cut just deleted is bounded by two or more
     * loops, like a band round a protrusion, which can leave the far side a shell of its own.
     *
     * @param cut the faces the cut deleted
     * @return true when such a region exists
     */
    private boolean cutLeavesSeveralLoops(int[] cut) {
        long[] sides = new long[cut.length * TRIANGLE_CORNERS];
        for (int index = 0; index < cut.length; index++) {
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                sides[index * TRIANGLE_CORNERS + corner] =
                        ((long) faceCorners[cut[index] * TRIANGLE_CORNERS + corner] << KEY_SHIFT)
                                | faceCorners[cut[index] * TRIANGLE_CORNERS + (corner + 1) % TRIANGLE_CORNERS];
            }
        }
        Arrays.sort(sides);
        int[] vertices = new int[sides.length];
        for (int index = 0; index < sides.length; index++) {
            vertices[index] = (int) (sides[index] >>> KEY_SHIFT);
        }
        vertices = Arrays.stream(vertices).sorted().distinct().toArray();
        int[] region = UnionFind.singletons(vertices.length);
        int[] loopOf = UnionFind.singletons(vertices.length);
        for (long side : sides) {
            int from = Arrays.binarySearch(vertices, (int) (side >>> KEY_SHIFT));
            int to = Arrays.binarySearch(vertices, (int) (side & KEY_MASK));
            UnionFind.union(region, from, to);
            if (Arrays.binarySearch(sides, reversed(side)) < 0) {
                UnionFind.union(loopOf, from, to);
            }
        }
        int[] loopOfRegion = new int[vertices.length];
        Arrays.fill(loopOfRegion, -1);
        for (long side : sides) {
            if (Arrays.binarySearch(sides, reversed(side)) >= 0) {
                continue;
            }
            int from = Arrays.binarySearch(vertices, (int) (side >>> KEY_SHIFT));
            int owner = UnionFind.find(region, from);
            int loop = UnionFind.find(loopOf, from);
            if (loopOfRegion[owner] < 0) {
                loopOfRegion[owner] = loop;
            } else if (loopOfRegion[owner] != loop) {
                return true;
            }
        }
        return false;
    }

    /**
     * A packed side run the other way.
     *
     * @param side packed {@code (from, to)}
     * @return packed {@code (to, from)}
     */
    private static long reversed(long side) {
        return (side << KEY_SHIFT) | (side >>> KEY_SHIFT);
    }

    /**
     * Pulls apart the vertices a cut pinched into several fans: a cut only deletes faces from a
     * manifold surface, so only the vertices it reached need the split's work.
     *
     * @param markedList  the vertices the cut reached
     * @param markedCount how many of them
     * @return true when some vertex was split
     */
    private boolean splitPinchedVertices(int[] markedList, int markedCount) {
        long[] entries = new long[SMALL_BUCKET];
        int entryCount = 0;
        for (int index = 0; index < markedCount; index++) {
            int vertex = markedList[index];
            int around = facesRound(vertex);
            for (int near = 0; near < around; near++) {
                int slot = vertexSlot(aroundScratch[near], vertex);
                if (entryCount == entries.length) {
                    entries = Arrays.copyOf(entries, entryCount * GROWTH);
                }
                entries[entryCount++] = ((long) vertex << KEY_SHIFT) | slot;
            }
        }
        entries = Arrays.stream(entries, 0, entryCount).sorted().distinct().toArray();
        entryCount = entries.length;
        boolean split = false;
        int groupStart = 0;
        while (groupStart < entryCount) {
            int vertex = (int) (entries[groupStart] >>> KEY_SHIFT);
            int groupEnd = groupStart;
            while (groupEnd < entryCount && (int) (entries[groupEnd] >>> KEY_SHIFT) == vertex) {
                groupEnd++;
            }
            split |= splitFans(entries, groupStart, groupEnd);
            groupStart = groupEnd;
        }
        return split;
    }

    /**
     * Splits one vertex's corners into fans of faces joined by a side through the vertex, and
     * gives every fan but the one with the lowest face a copy of the vertex.
     *
     * @param entries packed {@code (vertex, slot)} corners, sorted
     * @param start   first corner of the vertex
     * @param end     one past its last corner
     * @return true when the vertex had more than one fan
     */
    private boolean splitFans(long[] entries, int start, int end) {
        int count = end - start;
        int[] fan = UnionFind.singletons(count);
        int[] next = new int[count];
        int[] previous = new int[count];
        for (int index = 0; index < count; index++) {
            int slot = (int) (entries[start + index] & KEY_MASK);
            next[index] = faceCorners[slot / TRIANGLE_CORNERS * TRIANGLE_CORNERS
                    + (slot % TRIANGLE_CORNERS + 1) % TRIANGLE_CORNERS];
            previous[index] = faceCorners[previousSlot(slot)];
        }
        for (int first = 0; first < count; first++) {
            for (int second = first + 1; second < count; second++) {
                if (next[first] == previous[second] || previous[first] == next[second]
                        || next[first] == next[second] || previous[first] == previous[second]) {
                    UnionFind.union(fan, first, second);
                }
            }
        }
        int keeper = UnionFind.find(fan, 0);
        int vertex = (int) (entries[start] >>> KEY_SHIFT);
        int[] copyOfFan = new int[count];
        Arrays.fill(copyOfFan, -1);
        boolean split = false;
        for (int index = 0; index < count; index++) {
            int root = UnionFind.find(fan, index);
            if (root == keeper) {
                continue;
            }
            if (copyOfFan[root] < 0) {
                copyOfFan[root] = copyVertex(vertex);
                split = true;
            }
            int slot = (int) (entries[start + index] & KEY_MASK);
            faceCorners[slot] = copyOfFan[root];
            refile(slot / TRIANGLE_CORNERS);
            noteCorner(slot / TRIANGLE_CORNERS, copyOfFan[root]);
        }
        return split;
    }

    /**
     * Marks each face that a face sharing one of its corners folds back over: a normal turned
     * more than about 105 degrees away, into the 75-degree cone of its back rays.
     *
     * @param corners  three vertex indices per face
     * @param at       xyz per vertex
     * @param faces    faces to judge
     * @param vertices vertices the corners name
     * @param group    per face, negative for one left out
     * @return per face whether it is creased
     */
    private static boolean[] creasedFaces(int[] corners, float[] at, int faces, int vertices, int[] group) {
        boolean[] creased = new boolean[faces];
        float[] normals = new float[faces * TRIANGLE_CORNERS];
        IntStream.range(0, faces).parallel().filter(face -> group[face] >= 0)
                .forEach(face -> unitNormal(corners, at, face, normals, face * TRIANGLE_CORNERS));
        int[] faceStart = new int[vertices + 1];
        for (int slot = 0; slot < faces * TRIANGLE_CORNERS; slot++) {
            if (group[slot / TRIANGLE_CORNERS] >= 0) {
                faceStart[corners[slot] + 1]++;
            }
        }
        for (int vertex = 0; vertex < vertices; vertex++) {
            faceStart[vertex + 1] += faceStart[vertex];
        }
        int[] cursor = Arrays.copyOf(faceStart, vertices);
        int[] facesOfVertex = new int[faceStart[vertices]];
        for (int slot = 0; slot < faces * TRIANGLE_CORNERS; slot++) {
            if (group[slot / TRIANGLE_CORNERS] >= 0) {
                facesOfVertex[cursor[corners[slot]]++] = slot / TRIANGLE_CORNERS;
            }
        }
        IntStream.range(0, faces).parallel().forEach(face -> {
            for (int corner = 0; corner < TRIANGLE_CORNERS && group[face] >= 0 && !creased[face]; corner++) {
                int vertex = corners[face * TRIANGLE_CORNERS + corner];
                for (int index = faceStart[vertex]; index < faceStart[vertex + 1]; index++) {
                    int other = facesOfVertex[index];
                    if (dot(normals, face, other) < FOLDED_BACK_NORMAL_COSINE) {
                        creased[face] = true;
                        break;
                    }
                }
            }
        });
        return creased;
    }

    /**
     * {@link #creasedFaces} for the faces round a refill: only faces round its corners are judged,
     * against each other.
     *
     * @param first first new face
     * @param flags per face, the flags to update in place while they have room
     * @return the updated flags, grown ahead of the faces when they had none
     */
    private boolean[] foldedBackNear(int first, boolean[] flags) {
        boolean[] creased = flags.length >= faceCount ? flags
                : Arrays.copyOf(flags, Math.max(faceCount, flags.length + flags.length / GROWTH));
        int[] near = new int[SMALL_BUCKET];
        int nearCount = 0;
        for (int slot = first * TRIANGLE_CORNERS; slot < faceCount * TRIANGLE_CORNERS; slot++) {
            if (faceAlive[slot / TRIANGLE_CORNERS]) {
                if (nearCount == near.length) {
                    near = Arrays.copyOf(near, nearCount * GROWTH);
                }
                near[nearCount++] = faceCorners[slot];
            }
        }
        near = Arrays.stream(near, 0, nearCount).sorted().distinct().toArray();
        int[] judged = new int[SMALL_BUCKET];
        int judgedCount = 0;
        for (int vertex : near) {
            int around = facesRound(vertex);
            for (int index = 0; index < around; index++) {
                if (judgedCount == judged.length) {
                    judged = Arrays.copyOf(judged, judgedCount * GROWTH);
                }
                judged[judgedCount++] = aroundScratch[index];
            }
        }
        judged = Arrays.stream(judged, 0, judgedCount).sorted().distinct().toArray();
        creaseJudged = judged;
        float[] normals = new float[judged.length * TRIANGLE_CORNERS];
        for (int index = 0; index < judged.length; index++) {
            unitNormal(faceCorners, positions, judged[index], normals, index * TRIANGLE_CORNERS);
        }
        for (int index = 0; index < judged.length; index++) {
            int face = judged[index];
            for (int corner = 0; corner < TRIANGLE_CORNERS && !creased[face]; corner++) {
                int around = facesRound(faceCorners[face * TRIANGLE_CORNERS + corner]);
                for (int other = 0; other < around; other++) {
                    int at = Arrays.binarySearch(judged, aroundScratch[other]);
                    if (at >= 0 && dot(normals, index, at) < FOLDED_BACK_NORMAL_COSINE) {
                        creased[face] = true;
                        break;
                    }
                }
            }
        }
        return creased;
    }

    /**
     * Dot product of two unit normals held three floats apiece.
     *
     * @param normals the normals
     * @param first   index of one
     * @param second  index of the other
     * @return their dot product
     */
    private static float dot(float[] normals, int first, int second) {
        return normals[first * TRIANGLE_CORNERS] * normals[second * TRIANGLE_CORNERS]
                + normals[first * TRIANGLE_CORNERS + 1] * normals[second * TRIANGLE_CORNERS + 1]
                + normals[first * TRIANGLE_CORNERS + 2] * normals[second * TRIANGLE_CORNERS + 2];
    }

    /**
     * Writes one face's unit normal as floats.
     *
     * @param corners three vertex indices per face
     * @param at      xyz per vertex
     * @param face    face index
     * @param normals receives the normal
     * @param offset  where the three floats go
     */
    private static void unitNormal(int[] corners, float[] at, int face, float[] normals, int offset) {
        int a = corners[face * TRIANGLE_CORNERS] * TRIANGLE_CORNERS;
        int b = corners[face * TRIANGLE_CORNERS + 1] * TRIANGLE_CORNERS;
        int c = corners[face * TRIANGLE_CORNERS + 2] * TRIANGLE_CORNERS;
        double alongX = at[b] - at[a];
        double alongY = at[b + 1] - at[a + 1];
        double alongZ = at[b + 2] - at[a + 2];
        double acrossX = at[c] - at[a];
        double acrossY = at[c + 1] - at[a + 1];
        double acrossZ = at[c + 2] - at[a + 2];
        double normalX = alongY * acrossZ - alongZ * acrossY;
        double normalY = alongZ * acrossX - alongX * acrossZ;
        double normalZ = alongX * acrossY - alongY * acrossX;
        double length = Math.max(Double.MIN_NORMAL, Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ));
        normals[offset] = (float) (normalX / length);
        normals[offset + 1] = (float) (normalY / length);
        normals[offset + 2] = (float) (normalZ / length);
    }

    /**
     * The corner positions of the faces from one index on that a test picks.
     *
     * @param picked which faces to copy
     * @param first  first face considered
     * @return nine floats per picked face, corner by corner
     */
    private float[] cornersWhere(IntPredicate picked, int first) {
        float[] corners = new float[0];
        int next = 0;
        for (int face = first; face < faceCount; face++) {
            if (!picked.test(face)) {
                continue;
            }
            if (next == corners.length) {
                corners = Arrays.copyOf(corners, Math.max(TRIANGLE_CORNERS * TRIANGLE_CORNERS, corners.length * GROWTH));
            }
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                System.arraycopy(positions, faceCorners[face * TRIANGLE_CORNERS + corner] * TRIANGLE_CORNERS, corners,
                        next, TRIANGLE_CORNERS);
                next += TRIANGLE_CORNERS;
            }
        }
        return Arrays.copyOf(corners, next);
    }

    /**
     * The corner positions of some faces, nine floats each, in the order given.
     *
     * @param faces working face indices
     * @return their corners
     */
    private float[] cornersOf(int[] faces) {
        float[] corners = new float[faces.length * TRIANGLE_CORNERS * TRIANGLE_CORNERS];
        int next = 0;
        for (int face : faces) {
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                System.arraycopy(positions, faceCorners[face * TRIANGLE_CORNERS + corner] * TRIANGLE_CORNERS, corners,
                        next, TRIANGLE_CORNERS);
                next += TRIANGLE_CORNERS;
            }
        }
        return corners;
    }

    /**
     * Two float runs one after the other.
     *
     * @param head first run
     * @param tail second run
     * @return the joined run
     */
    private static float[] append(float[] head, float[] tail) {
        float[] joined = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, joined, head.length, tail.length);
        return joined;
    }

    /**
     * A tree over standalone triangles, nine floats each.
     *
     * @param corners corner positions, three corners per triangle
     * @return the built tree
     */
    private static TriangleBvh treeOver(float[] corners) {
        TriangleBvh tree = new TriangleBvh();
        tree.leafTriangles = RAY_LEAF_TRIANGLES;
        tree.positions = corners;
        tree.triangleCount = corners.length / (TRIANGLE_CORNERS * TRIANGLE_CORNERS);
        tree.triangleCorners = new int[tree.triangleCount * TRIANGLE_CORNERS];
        for (int corner = 0; corner < tree.triangleCorners.length; corner++) {
            tree.triangleCorners[corner] = corner;
        }
        tree.build();
        return tree;
    }

    /**
     * A tree over the alive working faces from one index on, sharing the working arrays; only
     * those faces are read.
     *
     * @param first first face of the run
     * @return the built tree, empty when none is alive
     */
    private TriangleBvh treeOverFrom(int first) {
        TriangleBvh tree = new TriangleBvh();
        tree.leafTriangles = RAY_LEAF_TRIANGLES;
        tree.positions = positions;
        tree.triangleCorners = faceCorners;
        tree.triangleCount = faceCount;
        tree.filedOnly = IntStream.range(first, faceCount).filter(face -> faceAlive[face]).toArray();
        tree.build();
        return tree;
    }

    // ---------------------------------------------------------------------------------------
    // Assemble

    /**
     * Compacts the survivors into a {@link HalfEdgeMesh} and rebuilds the bundle's slots. An
     * orientation contradiction has no half-edge form, so then the survivors stay an
     * {@link ArrayMesh} and the report, not the build, says why.
     */
    private void assemble() {
        ensureEdges();
        boolean twinsKnown = true;
        for (int edge = 0; edge < edges.edgeCount(); edge++) {
            int uses = edges.useCount(edge);
            if (uses == 1) {
                report.outputBoundaryEdgeCount++;
            } else if (uses == 2 && edges.forward(edges.use(edge, 0)) == edges.forward(edges.use(edge, 1))) {
                report.unorientedEdgeCount++;
            }
            twinsKnown &= uses <= 2;
        }
        int[] newIndexOfVertex = new int[vertexCount];
        Arrays.fill(newIndexOfVertex, -1);
        int aliveFaces = 0;
        for (int face = 0; face < faceCount; face++) {
            if (faceAlive[face]) {
                aliveFaces++;
                for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                    newIndexOfVertex[faceCorners[face * TRIANGLE_CORNERS + corner]] = 0;
                }
            }
        }
        int survivors = 0;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            if (newIndexOfVertex[vertex] == 0) {
                newIndexOfVertex[vertex] = survivors++;
            }
        }
        float[] outPositions = new float[survivors * TRIANGLE_CORNERS];
        int[] outOriginal = new int[survivors];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int target = newIndexOfVertex[vertex];
            if (target >= 0) {
                System.arraycopy(positions, vertex * TRIANGLE_CORNERS, outPositions, target * TRIANGLE_CORNERS,
                        TRIANGLE_CORNERS);
                outOriginal[target] = originalOfVertex[vertex];
            }
        }
        int[] outFaces = new int[aliveFaces * TRIANGLE_CORNERS];
        double[] outU = cornerU == null ? null : new double[aliveFaces * TRIANGLE_CORNERS];
        double[] outV = cornerU == null ? null : new double[aliveFaces * TRIANGLE_CORNERS];
        int[] fillFaceIndices = new int[Math.max(0, faceCount - firstFillFace)];
        int fillFaces = 0;
        int[] outFaceOf = new int[faceCount];
        int nextFace = 0;
        for (int face = 0; face < faceCount; face++) {
            if (!faceAlive[face]) {
                continue;
            }
            outFaceOf[face] = nextFace;
            if (face >= firstFillFace) {
                fillFaceIndices[fillFaces++] = nextFace;
            }
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int slot = face * TRIANGLE_CORNERS + corner;
                outFaces[nextFace * TRIANGLE_CORNERS + corner] = newIndexOfVertex[faceCorners[slot]];
                if (outU != null) {
                    outU[nextFace * TRIANGLE_CORNERS + corner] = cornerU[slot];
                    outV[nextFace * TRIANGLE_CORNERS + corner] = cornerV[slot];
                }
            }
            nextFace++;
        }
        report.outputVertexCount = survivors;
        report.outputFaceCount = aliveFaces;
        report.fillFaceIndices = Arrays.copyOf(fillFaceIndices, fillFaces);
        // The edge table already pairs every side, so the half-edge build need not find the pairs.
        int[] cornerTwin = null;
        if (twinsKnown && report.unorientedEdgeCount == 0) {
            int[] twins = new int[aliveFaces * TRIANGLE_CORNERS];
            IntStream.range(0, faceCount).parallel().filter(face -> faceAlive[face]).forEach(face -> {
                for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                    int slot = face * TRIANGLE_CORNERS + corner;
                    int edge = edges.edgeOfSlot(slot);
                    int other = edges.useCount(edge) < 2 ? -1
                            : edges.use(edge, 0) == slot ? edges.use(edge, 1) : edges.use(edge, 0);
                    twins[outFaceOf[face] * TRIANGLE_CORNERS + corner] = other < 0 ? MeshTopology.NONE
                            : outFaceOf[other / TRIANGLE_CORNERS] * TRIANGLE_CORNERS + other % TRIANGLE_CORNERS;
                }
            });
            cornerTwin = twins;
        }
        HalfEdgeMesh oriented = report.unorientedEdgeCount > 0 ? null
                : HalfEdgeMeshOps.buildFromIndexedMesh(outPositions, outFaces, cornerTwin);
        GeometryBundle bundle = GeometryBundle.ofMesh(oriented != null ? oriented
                : new ArrayMesh(outPositions, null, outFaces, TRIANGLE_CORNERS));
        if (outU != null) {
            bundle = bundle.withSlot(CornerUvField.SLOT, new CornerUvField(outU, outV));
        }
        bundle = bundle.withSlot(EdgeMarks.SLOT, Map.of());
        if (oriented != null) {
            int[] sourceVertex = new int[survivors];
            for (int vertex = 0; vertex < survivors; vertex++) {
                int origin = outOriginal[vertex];
                sourceVertex[vertex] = origin < 0 ? MeshTopology.NONE
                        : input.mesh().vertexIdAt(weldedFirstVertex == null ? origin : weldedFirstVertex[origin]);
            }
            oriented.recordSources(sourceVertex, null, null);
            bundle = EdgeMarks.with(bundle, MeshRepairReport.OPEN_BOUNDARY_LABEL, boundaryEdgeMarks(oriented));
        }
        output = SlotCarry.carry(List.of(input), bundle.withSlot(MeshRepairReport.SLOT, report));
    }

    /**
     * Boundary flags over the repaired mesh's own edge ids, the overlay the mesh viewer draws.
     *
     * @param repaired the assembled mesh
     * @return one flag per edge id, true on the loops the repair left open
     */
    private static boolean[] boundaryEdgeMarks(HalfEdgeMesh repaired) {
        int maxEdgeId = 0;
        for (int index = 0; index < repaired.edgeCount(); index++) {
            maxEdgeId = Math.max(maxEdgeId, repaired.edgeIdAt(index));
        }
        boolean[] marks = new boolean[maxEdgeId + 1];
        for (int index = 0; index < repaired.edgeCount(); index++) {
            int edgeId = repaired.edgeIdAt(index);
            marks[edgeId] = repaired.isBoundaryEdge(edgeId);
        }
        return marks;
    }

    // ---------------------------------------------------------------------------------------
    // Working state

    /** Re-files in the edges every face changed since they were last read. */
    private void ensureEdges() {
        for (int index = 0; index < touchedCount; index++) {
            int face = touchedFaces[index];
            edges.removeFace(face);
            if (faceAlive[face]) {
                edges.addFace(face, faceCorners, vertexCount);
            }
            faceTouched[face] = false;
        }
        touchedCount = 0;
    }

    /**
     * Chains a face under a vertex it has just taken as a corner.
     *
     * @param face   working face index
     * @param vertex its new corner
     */
    private void noteCorner(int face, int vertex) {
        if (vertex >= aroundHead.length) {
            int old = aroundHead.length;
            aroundHead = Arrays.copyOf(aroundHead, Math.max(vertex + 1, old * GROWTH));
            Arrays.fill(aroundHead, old, aroundHead.length, -1);
        }
        if (aroundChainCount == aroundNext.length) {
            int capacity = Math.max(SMALL_BUCKET, aroundChainCount * GROWTH);
            aroundNext = Arrays.copyOf(aroundNext, capacity);
            aroundChained = Arrays.copyOf(aroundChained, capacity);
        }
        aroundChained[aroundChainCount] = face;
        aroundNext[aroundChainCount] = aroundHead[vertex];
        aroundHead[vertex] = aroundChainCount++;
    }

    /**
     * Gathers into {@link #aroundScratch} the alive faces that have a vertex as a corner now.
     *
     * @param vertex working vertex
     * @return how many faces were gathered
     */
    private int facesRound(int vertex) {
        int count = 0;
        int filedEnd = vertex < aroundStart.length - 1 ? aroundStart[vertex + 1] : 0;
        int filedStart = vertex < aroundStart.length - 1 ? aroundStart[vertex] : 0;
        int chained = vertex < aroundHead.length ? aroundHead[vertex] : -1;
        for (int index = filedStart; index < filedEnd || chained >= 0; ) {
            int face;
            if (index < filedEnd) {
                face = aroundFaces[index++];
            } else {
                face = aroundChained[chained];
                chained = aroundNext[chained];
            }
            int slot = face * TRIANGLE_CORNERS;
            if (!faceAlive[face] || faceCorners[slot] != vertex && faceCorners[slot + 1] != vertex
                    && faceCorners[slot + 2] != vertex) {
                continue;
            }
            if (count == aroundScratch.length) {
                aroundScratch = Arrays.copyOf(aroundScratch, count * GROWTH);
            }
            aroundScratch[count++] = face;
        }
        return count;
    }

    /**
     * Appends to a list the alive faces filed round a vertex, leaving one face out, while it has
     * room; reads the filing only, so threads may call it side by side while no face changes.
     *
     * @param vertex  working vertex
     * @param skipped a face to leave out
     * @param into    the list
     * @param count   entries in use
     * @return entries in use after appending
     */
    private int facesRoundInto(int vertex, int skipped, int[] into, int count) {
        int filedEnd = vertex < aroundStart.length - 1 ? aroundStart[vertex + 1] : 0;
        int chained = vertex < aroundHead.length ? aroundHead[vertex] : -1;
        int at = vertex < aroundStart.length - 1 ? aroundStart[vertex] : 0;
        while ((at < filedEnd || chained >= 0) && count < into.length) {
            int other;
            if (at < filedEnd) {
                other = aroundFaces[at++];
            } else {
                other = aroundChained[chained];
                chained = aroundNext[chained];
            }
            int slot = other * TRIANGLE_CORNERS;
            if (other != skipped && faceAlive[other] && (faceCorners[slot] == vertex || faceCorners[slot + 1] == vertex
                    || faceCorners[slot + 2] == vertex)) {
                into[count++] = other;
            }
        }
        return count;
    }

    /**
     * Notes that a face's shape or survival changed, so the edges re-file it before they are next
     * read and the pruning does not trust orientation's count for it.
     *
     * @param face working face index
     */
    private void touch(int face) {
        if (changedSinceOrientation != null && face < orientedFaces && !changedSinceOrientation[face]) {
            changedSinceOrientation[face] = true;
            if (changedSinceCount == changedSince.length) {
                changedSince = Arrays.copyOf(changedSince, Math.max(SMALL_BUCKET, changedSinceCount * GROWTH));
            }
            changedSince[changedSinceCount++] = face;
        }
        refile(face);
    }

    /**
     * Notes that a face took a copy of a corner, which moves no point of it, so only the edges
     * re-file it; every parity line it crossed it still crosses.
     *
     * @param face working face index
     */
    private void refile(int face) {
        if (faceTouched.length <= face) {
            faceTouched = Arrays.copyOf(faceTouched, Math.max(face + 1, faceTouched.length * GROWTH));
        }
        if (faceTouched[face]) {
            return;
        }
        faceTouched[face] = true;
        if (touchedCount == touchedFaces.length) {
            touchedFaces = Arrays.copyOf(touchedFaces, Math.max(SMALL_BUCKET, touchedCount * GROWTH));
        }
        touchedFaces[touchedCount++] = face;
    }

    /**
     * Area of one working face.
     *
     * @param face working face index
     * @return the triangle's area
     */
    private double faceArea(int face) {
        return TriangleGeometry.area(positions, faceCorners[face * TRIANGLE_CORNERS],
                faceCorners[face * TRIANGLE_CORNERS + 1], faceCorners[face * TRIANGLE_CORNERS + 2]);
    }

    /**
     * Squared distance between two working vertices.
     *
     * @param first  one vertex
     * @param second the other vertex
     * @return the squared distance
     */
    private double squaredDistance(int first, int second) {
        double deltaX = positions[first * TRIANGLE_CORNERS] - positions[second * TRIANGLE_CORNERS];
        double deltaY = positions[first * TRIANGLE_CORNERS + 1] - positions[second * TRIANGLE_CORNERS + 1];
        double deltaZ = positions[first * TRIANGLE_CORNERS + 2] - positions[second * TRIANGLE_CORNERS + 2];
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }

    /**
     * Appends a working vertex at a position, with no input vertex behind it.
     *
     * @param x position x
     * @param y position y
     * @param z position z
     * @return the new working vertex index
     */
    private int addVertex(float x, float y, float z) {
        if ((vertexCount + 1) * TRIANGLE_CORNERS > positions.length) {
            int capacity = Math.max(positions.length * GROWTH, (vertexCount + 1) * TRIANGLE_CORNERS);
            positions = Arrays.copyOf(positions, capacity);
            originalOfVertex = Arrays.copyOf(originalOfVertex, capacity / TRIANGLE_CORNERS);
        }
        positions[vertexCount * TRIANGLE_CORNERS] = x;
        positions[vertexCount * TRIANGLE_CORNERS + 1] = y;
        positions[vertexCount * TRIANGLE_CORNERS + 2] = z;
        originalOfVertex[vertexCount] = -1;
        return vertexCount++;
    }

    /**
     * Appends a working vertex copying another one's position and origin.
     *
     * @param source vertex to copy
     * @return the new working vertex index
     */
    private int copyVertex(int source) {
        int origin = originalOfVertex[source];
        int copy = addVertex(positions[source * TRIANGLE_CORNERS], positions[source * TRIANGLE_CORNERS + 1],
                positions[source * TRIANGLE_CORNERS + 2]);
        originalOfVertex[copy] = origin;
        return copy;
    }

    /**
     * Appends a working face with no texture coordinates.
     *
     * @param cornerA first corner vertex
     * @param cornerB second corner vertex
     * @param cornerC third corner vertex
     */
    private void addFace(int cornerA, int cornerB, int cornerC) {
        if ((faceCount + 1) * TRIANGLE_CORNERS > faceCorners.length) {
            int capacity = Math.max(faceCorners.length * GROWTH, (faceCount + 1) * TRIANGLE_CORNERS);
            faceCorners = Arrays.copyOf(faceCorners, capacity);
            faceAlive = Arrays.copyOf(faceAlive, capacity / TRIANGLE_CORNERS);
            if (cornerU != null) {
                cornerU = Arrays.copyOf(cornerU, capacity);
                cornerV = Arrays.copyOf(cornerV, capacity);
            }
        }
        faceCorners[faceCount * TRIANGLE_CORNERS] = cornerA;
        faceCorners[faceCount * TRIANGLE_CORNERS + 1] = cornerB;
        faceCorners[faceCount * TRIANGLE_CORNERS + 2] = cornerC;
        faceAlive[faceCount] = true;
        touch(faceCount);
        noteCorner(faceCount, cornerA);
        noteCorner(faceCount, cornerB);
        noteCorner(faceCount, cornerC);
        if (cornerU != null) {
            Arrays.fill(cornerU, faceCount * TRIANGLE_CORNERS, (faceCount + 1) * TRIANGLE_CORNERS, Double.NaN);
            Arrays.fill(cornerV, faceCount * TRIANGLE_CORNERS, (faceCount + 1) * TRIANGLE_CORNERS, Double.NaN);
        }
        faceCount++;
    }
}
