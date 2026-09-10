package ixdar.geometry.mesh.data.ops;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.EdgeKey;
import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.TriangleBvh;
import ixdar.geometry.mesh.data.TriangleGeometry;
import ixdar.geometry.mesh.data.UnionFind;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.nodes.api.UvField;
import ixdar.geometry.mesh.nodes.data.TagGeometryNode;
import ixdar.geometry.mesh.nodes.modifier.SetBoneWeightNode;

/**
 * Topological repair of a triangle mesh in the order the defects depend on each other: weld,
 * orient, split non-manifold edges, degenerate faces, shells, holes. Removes no surface beyond
 * degenerate faces and dropped debris shells.
 *
 * <p>Set the parameters, call {@link #build()}, then read {@link #output} and {@link #report}.
 */
public final class MeshRepair {

    /** Corners of a triangle. */
    public static final int TRIANGLE_CORNERS = 3;

    /** Shift separating the two vertex indices of a packed undirected edge key. */
    private static final int KEY_SHIFT = 32;

    /** Low-word mask of a packed undirected edge key. */
    private static final long KEY_MASK = 0xFFFFFFFFL;

    /** Growth factor for the working vertex and face buffers. */
    private static final int GROWTH = 2;

    /** Axis rays cast per shell, so a ray grazing an edge cannot decide containment alone. */
    private static final int CONTAINMENT_RAYS = 3;

    /** Rounds of the last-resort face detachment before the split gives up. */
    private static final int DETACH_ROUNDS = 4;

    /** Longest edge bucket {@link #buildEdges} sorts by hand rather than through a library sort. */
    private static final int SMALL_BUCKET = 16;

    /** Corner slots a manifold edge is used by. */
    private static final int SLOTS_PER_EDGE = 2;

    /** Distance below which the weld fuses two vertices; not positive means no weld runs. */
    public double weldEpsilon;

    /** Longest boundary loop the hole filling attempts. */
    public int maxHoleEdges;

    /** Shells with fewer faces than this are debris and are dropped. */
    public int minShellFaces;

    /** What the repair found and did, filled by {@link #build()}. */
    public final MeshRepairReport report = new MeshRepairReport();

    /** The repaired bundle, set by {@link #build()}. */
    public GeometryBundle output;

    /** The bundle handed in. */
    public GeometryBundle input;

    /** Working vertex positions, xyz triples. */
    public float[] positions = new float[0];

    /** Live working vertices. */
    public int vertexCount;

    /** Input vertex each working vertex descends from, or {@code -1} when the repair minted it. */
    public int[] originalOfVertex = new int[0];

    /** Working face corners, three vertex indices per face. */
    public int[] faceCorners = new int[0];

    /** Whether each working face survives. */
    public boolean[] faceAlive = new boolean[0];

    /** Working faces, alive or not. */
    public int faceCount;

    /** Per-corner {@code u}, or null when the input carried no UV field. */
    public double[] cornerU;

    /** Per-corner {@code v}, parallel to {@link #cornerU}. */
    public double[] cornerV;

    /** Distinct undirected edges of the alive faces, sorted ascending in the first
     * {@link #edgeCount} entries. */
    public long[] edgeKeys = new long[0];

    /** Edges in {@link #edgeKeys}. */
    public int edgeCount;

    /** Offsets into {@link #edgeUses}, one per edge plus a tail. */
    public int[] edgeUseStart = new int[0];

    /** Corner slots using each edge, grouped by edge. */
    public int[] edgeUses = new int[0];

    /** Whether each corner slot runs its edge from the lower vertex index to the higher. */
    public boolean[] cornerForward = new boolean[0];

    /** Shell index of each alive face, {@code -1} on a dead face. */
    public int[] shellOfFace = new int[0];

    /** Scratch position reader. */
    private final Vector3f scratchPosition = new Vector3f();

    /** Whether a stage changed the faces since the edge structure was last built. */
    private boolean edgesDirty = true;

    /** Offsets into {@link #edgeUsePacked}, one per working vertex plus a tail. */
    private int[] edgeLowStart = new int[0];

    /** Write cursor per working vertex while {@link #buildEdges} fills the buckets. */
    private int[] edgeLowCursor = new int[0];

    /** Corner slots as {@code (higher vertex, slot)} longs, bucketed by their lower vertex. */
    private long[] edgeUsePacked = new long[0];

    /** Local index of each working vertex on the loop being filled, or {@code -1}. */
    private int[] loopLocalOfVertex = new int[0];

    /** Local index of each working vertex on the filled loop's one-ring, or {@code -1}. */
    private int[] ringLocalOfVertex = new int[0];

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
        orient();
        splitNonManifold();
        fixDegenerates();
        classifyShells();
        fillHoles();
        assemble();
    }

    /** Copies the input mesh and its per-corner UVs into the working arrays. */
    private void readInput() {
        MeshTopology mesh = input.mesh();
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
                    cornerU[slot] = uv.u(faceId, corner);
                    cornerV[slot] = uv.v(faceId, corner);
                }
            }
        }
    }

    /**
     * Stage 1: fuses vertices closer than {@link #weldEpsilon} through {@link MeshMergeByDistance},
     * whose weld map {@link SlotCarry} then follows with the bundle's per-element slots.
     *
     * <p>The glTF loader already welds bitwise-identical positions on import, so this only catches
     * genuinely near-coincident ones and does nothing when the epsilon is not positive.
     */
    private void weld() {
        if (weldEpsilon <= 0 || input.mesh().vertexCount() == 0) {
            return;
        }
        MeshMergeByDistance welder = new MeshMergeByDistance();
        MeshTopology welded = welder.weld(input.mesh(), (float) weldEpsilon);
        report.weldedVertexCount = input.mesh().vertexCount() - welded.vertexCount();
        int slitFaces = input.mesh().faceCount() - welder.sourceFace.length;
        report.degenerateFaceCount += slitFaces;
        report.degenerateDeletedCount += slitFaces;
        input = SlotCarry.weld(input, input.withMesh(welded), welder);
    }

    /**
     * Drops faces with a repeated corner. They are degenerate and have no well-formed edges, so
     * they cannot take part in the edge structure the later stages build; the slit each one leaves
     * is treated as a hole.
     */
    private void dropSlitFaces() {
        for (int face = 0; face < faceCount; face++) {
            if (!faceAlive[face]) {
                continue;
            }
            int cornerA = faceCorners[face * TRIANGLE_CORNERS];
            int cornerB = faceCorners[face * TRIANGLE_CORNERS + 1];
            int cornerC = faceCorners[face * TRIANGLE_CORNERS + 2];
            if (cornerA == cornerB || cornerB == cornerC || cornerA == cornerC) {
                faceAlive[face] = false;
                edgesDirty = true;
                report.degenerateFaceCount++;
                report.degenerateDeletedCount++;
            }
        }
    }

    /**
     * Stage 2: makes the winding consistent by breadth-first search from the largest face of each
     * component. An edge whose two faces demand opposite windings is a Mobius contradiction and is
     * reported as non-manifold rather than resolved.
     */
    private void orient() {
        ensureEdges();
        int[] faceComponent = UnionFind.singletons(faceCount);
        int[] contestedSlots = new int[0];
        int contestedCount = 0;
        for (int edge = 0; edge < edgeCount; edge++) {
            int start = edgeUseStart[edge];
            if (edgeUseStart[edge + 1] - start != 2) {
                continue;
            }
            int firstSlot = edgeUses[start];
            int secondSlot = edgeUses[start + 1];
            if (cornerForward[firstSlot] != cornerForward[secondSlot]) {
                UnionFind.union(faceComponent, firstSlot / TRIANGLE_CORNERS,
                        secondSlot / TRIANGLE_CORNERS);
                continue;
            }
            if ((contestedCount + 1) * SLOTS_PER_EDGE > contestedSlots.length) {
                contestedSlots = Arrays.copyOf(contestedSlots,
                        Math.max(SLOTS_PER_EDGE, contestedSlots.length * GROWTH));
            }
            contestedSlots[contestedCount * SLOTS_PER_EDGE] = firstSlot;
            contestedSlots[contestedCount * SLOTS_PER_EDGE + 1] = secondSlot;
            contestedCount++;
        }
        boolean[] flip = orientComponents(faceComponent, contestedSlots, contestedCount);
        for (int index = 0; index < contestedCount; index++) {
            int firstFace = contestedSlots[index * SLOTS_PER_EDGE] / TRIANGLE_CORNERS;
            int secondFace = contestedSlots[index * SLOTS_PER_EDGE + 1] / TRIANGLE_CORNERS;
            if (flip[firstFace] == flip[secondFace]) {
                report.mobiusEdgeCount++;
            }
        }
        for (int face = 0; face < faceCount; face++) {
            if (!faceAlive[face] || !flip[face]) {
                continue;
            }
            int swap = faceCorners[face * TRIANGLE_CORNERS + 1];
            faceCorners[face * TRIANGLE_CORNERS + 1] = faceCorners[face * TRIANGLE_CORNERS + 2];
            faceCorners[face * TRIANGLE_CORNERS + 2] = swap;
            if (cornerU != null) {
                double swapU = cornerU[face * TRIANGLE_CORNERS + 1];
                cornerU[face * TRIANGLE_CORNERS + 1] = cornerU[face * TRIANGLE_CORNERS + 2];
                cornerU[face * TRIANGLE_CORNERS + 2] = swapU;
                double swapV = cornerV[face * TRIANGLE_CORNERS + 1];
                cornerV[face * TRIANGLE_CORNERS + 1] = cornerV[face * TRIANGLE_CORNERS + 2];
                cornerV[face * TRIANGLE_CORNERS + 2] = swapV;
            }
            edgesDirty = true;
            report.orientedFaceCount++;
        }
    }

    /**
     * Two-colours the components of consistently wound faces across the edges whose two faces run
     * the same way, breadth-first from the component of the largest face. Orienting whole
     * components rather than single faces keeps one contested edge from cascading a flip over
     * everything the search reaches through it.
     *
     * @param faceComponent union-find parents over faces, joined across consistent edges
     * @param contestedSlots the two corner slots of each contested edge, in pairs
     * @param contested edges whose two faces run the same way
     * @return the flip flag of each face
     */
    private boolean[] orientComponents(int[] faceComponent, int[] contestedSlots, int contested) {
        int[] contestedFirst = new int[contested];
        int[] contestedSecond = new int[contested];
        for (int index = 0; index < contested; index++) {
            contestedFirst[index] = UnionFind.find(faceComponent,
                    contestedSlots[index * SLOTS_PER_EDGE] / TRIANGLE_CORNERS);
            contestedSecond[index] = UnionFind.find(faceComponent,
                    contestedSlots[index * SLOTS_PER_EDGE + 1] / TRIANGLE_CORNERS);
        }
        int[] linkStart = new int[faceCount + 1];
        for (int index = 0; index < contested; index++) {
            linkStart[contestedFirst[index]]++;
            linkStart[contestedSecond[index]]++;
        }
        int running = 0;
        for (int component = 0; component < faceCount; component++) {
            int degree = linkStart[component];
            linkStart[component] = running;
            running += degree;
        }
        linkStart[faceCount] = running;
        int[] cursor = Arrays.copyOf(linkStart, faceCount);
        int[] links = new int[running];
        for (int index = 0; index < contested; index++) {
            links[cursor[contestedFirst[index]]++] = contestedSecond[index];
            links[cursor[contestedSecond[index]]++] = contestedFirst[index];
        }
        boolean[] componentFlip = new boolean[faceCount];
        boolean[] componentVisited = new boolean[faceCount];
        int[] stack = new int[faceCount];
        long[] seedOrder = componentSeedsAscending(faceComponent);
        for (int index = seedOrder.length - 1; index >= 0; index--) {
            int root = UnionFind.find(faceComponent, (int) (seedOrder[index] & KEY_MASK));
            if (componentVisited[root]) {
                continue;
            }
            componentVisited[root] = true;
            int top = 0;
            stack[top++] = root;
            while (top > 0) {
                int component = stack[--top];
                for (int link = linkStart[component]; link < linkStart[component + 1]; link++) {
                    int other = links[link];
                    if (componentVisited[other]) {
                        continue;
                    }
                    componentVisited[other] = true;
                    componentFlip[other] = !componentFlip[component];
                    stack[top++] = other;
                }
            }
        }
        boolean[] flip = new boolean[faceCount];
        for (int face = 0; face < faceCount; face++) {
            if (faceAlive[face]) {
                flip[face] = componentFlip[UnionFind.find(faceComponent, face)];
            }
        }
        return flip;
    }

    /**
     * One {@code (area bits, face index)} long per consistent component, holding its largest face
     * and sorted ascending, so the caller seeds components largest-face first.
     *
     * @param faceComponent union-find parents over faces
     * @return the packed sort order
     */
    private long[] componentSeedsAscending(int[] faceComponent) {
        long[] largestOfComponent = new long[faceCount];
        Arrays.fill(largestOfComponent, -1);
        int components = 0;
        for (int face = 0; face < faceCount; face++) {
            if (!faceAlive[face]) {
                continue;
            }
            int component = UnionFind.find(faceComponent, face);
            if (largestOfComponent[component] < 0) {
                components++;
            }
            long key = ((long) Float.floatToIntBits((float) faceArea(face)) << KEY_SHIFT) | face;
            largestOfComponent[component] = Math.max(largestOfComponent[component], key);
        }
        long[] order = new long[components];
        int next = 0;
        for (int component = 0; component < faceCount; component++) {
            if (largestOfComponent[component] >= 0) {
                order[next++] = largestOfComponent[component];
            }
        }
        Arrays.sort(order);
        return order;
    }

    /**
     * Stage 3: pulls non-manifold edges apart by giving each edge-connected face fan its own copy
     * of the vertices, which duplicates the offending edge once per fan and deletes nothing.
     */
    private void splitNonManifold() {
        ensureEdges();
        int[] cornerParent = UnionFind.singletons(faceCount * TRIANGLE_CORNERS);
        for (int edge = 0; edge < edgeCount; edge++) {
            if (!manifoldEdge(edge)) {
                if (edgeUseStart[edge + 1] - edgeUseStart[edge] > 1) {
                    report.nonManifoldEdgeCount++;
                }
                continue;
            }
            int firstSlot = edgeUses[edgeUseStart[edge]];
            int secondSlot = edgeUses[edgeUseStart[edge] + 1];
            int firstFace = firstSlot / TRIANGLE_CORNERS;
            int secondFace = secondSlot / TRIANGLE_CORNERS;
            int firstNext = firstFace * TRIANGLE_CORNERS
                    + (firstSlot % TRIANGLE_CORNERS + 1) % TRIANGLE_CORNERS;
            int secondNext = secondFace * TRIANGLE_CORNERS
                    + (secondSlot % TRIANGLE_CORNERS + 1) % TRIANGLE_CORNERS;
            UnionFind.union(cornerParent, firstSlot, secondNext);
            UnionFind.union(cornerParent, firstNext, secondSlot);
        }

        boolean[] usedOldVertex = new boolean[vertexCount];
        int[] newIndexOfRoot = new int[cornerParent.length];
        Arrays.fill(newIndexOfRoot, -1);
        int newCount = 0;
        for (int face = 0; face < faceCount; face++) {
            if (!faceAlive[face]) {
                continue;
            }
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int slot = face * TRIANGLE_CORNERS + corner;
                usedOldVertex[faceCorners[slot]] = true;
                int root = UnionFind.find(cornerParent, slot);
                if (newIndexOfRoot[root] < 0) {
                    newIndexOfRoot[root] = newCount++;
                }
            }
        }
        int usedOldCount = 0;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            if (usedOldVertex[vertex]) {
                usedOldCount++;
            }
        }
        float[] newPositions = new float[newCount * TRIANGLE_CORNERS];
        int[] newOriginal = new int[newCount];
        for (int face = 0; face < faceCount; face++) {
            if (!faceAlive[face]) {
                continue;
            }
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int slot = face * TRIANGLE_CORNERS + corner;
                int oldVertex = faceCorners[slot];
                int newVertex = newIndexOfRoot[UnionFind.find(cornerParent, slot)];
                System.arraycopy(positions, oldVertex * TRIANGLE_CORNERS, newPositions,
                        newVertex * TRIANGLE_CORNERS, TRIANGLE_CORNERS);
                newOriginal[newVertex] = originalOfVertex[oldVertex];
                faceCorners[slot] = newVertex;
            }
        }
        positions = newPositions;
        originalOfVertex = newOriginal;
        vertexCount = newCount;
        edgesDirty = true;
        report.splitVertexCount = newCount - usedOldCount;

        for (int round = 0; round < DETACH_ROUNDS; round++) {
            ensureEdges();
            if (detachStubbornFaces() == 0) {
                return;
            }
        }
    }

    /**
     * Detaches, on all three corners, any face still sharing an edge with two others after the fan
     * split. It leaves the face as its own tiny shell, which the shell stage then reports.
     *
     * @return how many faces were detached
     */
    private int detachStubbornFaces() {
        boolean[] detach = new boolean[faceCount];
        int detached = 0;
        for (int edge = 0; edge < edgeCount; edge++) {
            int start = edgeUseStart[edge];
            int end = edgeUseStart[edge + 1];
            if (manifoldEdge(edge) || end - start < 2) {
                continue;
            }
            int keptForward = -1;
            int keptBackward = -1;
            for (int use = start; use < end; use++) {
                int slot = edgeUses[use];
                if (cornerForward[slot] && keptForward < 0) {
                    keptForward = slot;
                } else if (!cornerForward[slot] && keptBackward < 0) {
                    keptBackward = slot;
                } else if (!detach[slot / TRIANGLE_CORNERS]) {
                    detach[slot / TRIANGLE_CORNERS] = true;
                    detached++;
                }
            }
        }
        if (detached == 0) {
            return 0;
        }
        for (int face = 0; face < faceCount; face++) {
            if (!detach[face] || !faceAlive[face]) {
                continue;
            }
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int slot = face * TRIANGLE_CORNERS + corner;
                faceCorners[slot] = copyVertex(faceCorners[slot]);
            }
        }
        edgesDirty = true;
        report.detachedFaceCount += detached;
        return detached;
    }

    /**
     * Stage 4: resolves faces of exactly zero area by collapsing their shortest edge when the link
     * condition holds, and by deletion otherwise. Each resolution costs one pass over the faces,
     * which is affordable because a zero-area triangle with three distinct vertices is rare.
     */
    private void fixDegenerates() {
        boolean deleted = false;
        for (int face = 0; face < faceCount; face++) {
            if (!faceAlive[face] || faceArea(face) != 0) {
                continue;
            }
            report.degenerateFaceCount++;
            int shortest = shortestCorner(face);
            int keep = faceCorners[face * TRIANGLE_CORNERS + shortest];
            int drop = faceCorners[face * TRIANGLE_CORNERS
                    + (shortest + 1) % TRIANGLE_CORNERS];
            if (linkConditionHolds(keep, drop)) {
                collapse(keep, drop);
                report.degenerateCollapsedCount++;
                edgesDirty = true;
            } else {
                faceAlive[face] = false;
                deleted = true;
            }
        }
        if (deleted && !edgesDirty) {
            dropDeadEdgeUses();
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
            int from = faceCorners[face * TRIANGLE_CORNERS + corner];
            int to = faceCorners[face * TRIANGLE_CORNERS + (corner + 1) % TRIANGLE_CORNERS];
            double length = squaredDistance(from, to);
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
     * one ring may be shared, which together are Dey et al.'s link condition.
     *
     * @param keep vertex that survives the collapse
     * @param drop vertex that folds into it
     * @return true when the collapse is safe
     */
    private boolean linkConditionHolds(int keep, int drop) {
        boolean[] neighbourOfKeep = new boolean[vertexCount];
        boolean[] neighbourOfDrop = new boolean[vertexCount];
        boolean[] apex = new boolean[vertexCount];
        Set<Long> oppositeEdgeOfKeep = new HashSet<>();
        Set<Long> oppositeEdgeOfDrop = new HashSet<>();
        int apexCount = 0;
        for (int face = 0; face < faceCount; face++) {
            if (!faceAlive[face]) {
                continue;
            }
            int cornerA = faceCorners[face * TRIANGLE_CORNERS];
            int cornerB = faceCorners[face * TRIANGLE_CORNERS + 1];
            int cornerC = faceCorners[face * TRIANGLE_CORNERS + 2];
            boolean hasKeep = cornerA == keep || cornerB == keep || cornerC == keep;
            boolean hasDrop = cornerA == drop || cornerB == drop || cornerC == drop;
            if (hasKeep) {
                neighbourOfKeep[cornerA] = true;
                neighbourOfKeep[cornerB] = true;
                neighbourOfKeep[cornerC] = true;
                oppositeEdgeOfKeep.add(oppositeEdgeKey(cornerA, cornerB, cornerC, keep));
            }
            if (hasDrop) {
                neighbourOfDrop[cornerA] = true;
                neighbourOfDrop[cornerB] = true;
                neighbourOfDrop[cornerC] = true;
                oppositeEdgeOfDrop.add(oppositeEdgeKey(cornerA, cornerB, cornerC, drop));
            }
            if (hasKeep && hasDrop) {
                int third = cornerA + cornerB + cornerC - keep - drop;
                if (!apex[third]) {
                    apex[third] = true;
                    apexCount++;
                }
            }
        }
        if (apexCount == 0 || apexCount > 2) {
            return false;
        }
        oppositeEdgeOfKeep.retainAll(oppositeEdgeOfDrop);
        if (!oppositeEdgeOfKeep.isEmpty()) {
            return false;
        }
        int shared = 0;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            if (vertex == keep || vertex == drop) {
                continue;
            }
            if (neighbourOfKeep[vertex] && neighbourOfDrop[vertex]) {
                if (!apex[vertex]) {
                    return false;
                }
                shared++;
            }
        }
        return shared == apexCount;
    }

    /**
     * Key of the edge of a face opposite one of its corners.
     *
     * @param cornerA first corner of the face
     * @param cornerB second corner of the face
     * @param cornerC third corner of the face
     * @param corner the corner to leave out; must be one of the three
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
     */
    private void collapse(int keep, int drop) {
        for (int face = 0; face < faceCount; face++) {
            if (!faceAlive[face]) {
                continue;
            }
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
            if (hasKeep) {
                faceAlive[face] = false;
                continue;
            }
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                if (faceCorners[face * TRIANGLE_CORNERS + corner] == drop) {
                    faceCorners[face * TRIANGLE_CORNERS + corner] = keep;
                }
            }
        }
    }

    /**
     * Stage 5: groups the faces into edge-connected shells, decides which sit inside another by
     * ray parity, and drops only debris below {@link #minShellFaces} and enclosed bubbles.
     */
    private void classifyShells() {
        ensureEdges();
        int[] faceParent = UnionFind.singletons(faceCount);
        for (int edge = 0; edge < edgeCount; edge++) {
            int start = edgeUseStart[edge];
            for (int use = start + 1; use < edgeUseStart[edge + 1]; use++) {
                UnionFind.union(faceParent, edgeUses[start] / TRIANGLE_CORNERS,
                        edgeUses[use] / TRIANGLE_CORNERS);
            }
        }
        int[] shellOfRoot = new int[faceCount];
        Arrays.fill(shellOfRoot, -1);
        shellOfFace = new int[faceCount];
        Arrays.fill(shellOfFace, -1);
        int shells = 0;
        for (int face = 0; face < faceCount; face++) {
            if (!faceAlive[face]) {
                continue;
            }
            int root = UnionFind.find(faceParent, face);
            if (shellOfRoot[root] < 0) {
                shellOfRoot[root] = shells++;
            }
            shellOfFace[face] = shellOfRoot[root];
        }
        report.shellCount = shells;
        if (shells == 0) {
            return;
        }
        int[] shellFaceCount = new int[shells];
        int[] shellFirstFace = new int[shells];
        Arrays.fill(shellFirstFace, -1);
        for (int face = 0; face < faceCount; face++) {
            int shell = shellOfFace[face];
            if (shell < 0) {
                continue;
            }
            shellFaceCount[shell]++;
            if (shellFirstFace[shell] < 0) {
                shellFirstFace[shell] = face;
            }
        }
        int largestShell = 0;
        for (int shell = 1; shell < shells; shell++) {
            if (shellFaceCount[shell] > shellFaceCount[largestShell]) {
                largestShell = shell;
            }
        }
        boolean[] contained = new boolean[shells];
        if (shells > 1) {
            TriangleBvh soup = new TriangleBvh();
            soup.positions = positions;
            soup.triangleCorners = faceCorners;
            soup.triangleGroup = shellOfFace;
            soup.triangleCount = faceCount;
            soup.build();
            int[] stack = new int[Math.max(1, soup.nodeCount)];
            for (int shell = 0; shell < shells; shell++) {
                contained[shell] = insideAnotherShell(soup, stack, shell, shellFirstFace[shell]);
            }
        }
        boolean[] kept = new boolean[shells];
        for (int shell = 0; shell < shells; shell++) {
            kept[shell] = shell == largestShell
                    || (shellFaceCount[shell] >= minShellFaces && !contained[shell]);
            if (!kept[shell]) {
                report.droppedShellCount++;
                report.droppedShellFaceCount += shellFaceCount[shell];
            }
        }
        boolean dropped = false;
        for (int face = 0; face < faceCount; face++) {
            if (shellOfFace[face] >= 0 && !kept[shellOfFace[face]]) {
                faceAlive[face] = false;
                dropped = true;
            }
        }
        if (dropped) {
            dropDeadEdgeUses();
        }
        recordShells(shellFaceCount, contained, kept);
    }

    /**
     * Writes the per-shell arrays into the report, largest shell first.
     *
     * @param shellFaceCount faces per shell
     * @param contained containment flag per shell
     * @param kept survival flag per shell
     */
    private void recordShells(int[] shellFaceCount, boolean[] contained, boolean[] kept) {
        int shells = shellFaceCount.length;
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
     * Ray-parity containment test: casts one ray per axis from a point on the shell and asks
     * whether an odd number of the other shells' triangles lie in front of it, taking the majority
     * so a ray grazing an edge cannot decide alone.
     *
     * @param soup bounding-volume tree over every alive face, grouped by shell
     * @param stack scratch node stack for the tree walk
     * @param shell shell being tested
     * @param representativeFace a face of that shell
     * @return true when the shell sits inside another one
     */
    private boolean insideAnotherShell(TriangleBvh soup, int[] stack, int shell,
            int representativeFace) {
        double originX = 0;
        double originY = 0;
        double originZ = 0;
        for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
            int vertex = faceCorners[representativeFace * TRIANGLE_CORNERS + corner];
            originX += positions[vertex * TRIANGLE_CORNERS];
            originY += positions[vertex * TRIANGLE_CORNERS + 1];
            originZ += positions[vertex * TRIANGLE_CORNERS + 2];
        }
        originX /= TRIANGLE_CORNERS;
        originY /= TRIANGLE_CORNERS;
        originZ /= TRIANGLE_CORNERS;
        int inside = 0;
        for (int axis = 0; axis < CONTAINMENT_RAYS; axis++) {
            if (soup.positiveAxisRayCrossings(axis, originX, originY, originZ, shell, stack) % 2
                    == 1) {
                inside++;
            }
        }
        return inside * 2 > CONTAINMENT_RAYS;
    }

    /**
     * Stage 6: fills every boundary loop of at most {@link #maxHoleEdges} edges with
     * {@link MeshHoleFiller}, and reports the larger ones as still open.
     */
    private void fillHoles() {
        ensureEdges();
        int[] holeNext = new int[vertexCount];
        Arrays.fill(holeNext, -1);
        boolean[] ambiguous = new boolean[vertexCount];
        for (int edge = 0; edge < edgeCount; edge++) {
            if (edgeUseStart[edge + 1] - edgeUseStart[edge] != 1) {
                continue;
            }
            int slot = edgeUses[edgeUseStart[edge]];
            int face = slot / TRIANGLE_CORNERS;
            int from = faceCorners[slot];
            int to = faceCorners[face * TRIANGLE_CORNERS
                    + (slot % TRIANGLE_CORNERS + 1) % TRIANGLE_CORNERS];
            if (holeNext[to] >= 0) {
                ambiguous[to] = true;
            } else {
                holeNext[to] = from;
            }
        }
        boolean[] onBoundary = new boolean[vertexCount];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            onBoundary[vertex] = holeNext[vertex] >= 0;
        }
        double[] vertexSigma = meanIncidentEdgeLength(onBoundary);
        int[] neighbourStart = new int[0];
        int[] neighbours = new int[0];
        int boundaryVertexCount = vertexCount;
        loopLocalOfVertex = new int[boundaryVertexCount];
        ringLocalOfVertex = new int[boundaryVertexCount];
        Arrays.fill(loopLocalOfVertex, -1);
        Arrays.fill(ringLocalOfVertex, -1);
        boolean[] walked = new boolean[boundaryVertexCount];
        int[] loop = new int[boundaryVertexCount];
        int[] holeEdgeCounts = new int[boundaryVertexCount];
        boolean[] holeFilled = new boolean[boundaryVertexCount];
        double[] holePerimeters = new double[boundaryVertexCount];
        int[] holeFillFaceCounts = new int[boundaryVertexCount];
        int holes = 0;
        for (int start = 0; start < boundaryVertexCount; start++) {
            if (holeNext[start] < 0 || walked[start]) {
                continue;
            }
            int length = 0;
            boolean clean = true;
            int vertex = start;
            do {
                if (walked[vertex] || ambiguous[vertex] || length >= boundaryVertexCount) {
                    clean = false;
                    break;
                }
                walked[vertex] = true;
                loop[length++] = vertex;
                vertex = holeNext[vertex];
            } while (vertex != start && vertex >= 0);
            if (vertex != start) {
                clean = false;
            }
            holeEdgeCounts[holes] = length;
            holePerimeters[holes] = loopPerimeter(loop, length);
            boolean withinCap = clean && length <= maxHoleEdges;
            if (withinCap) {
                if (neighbourStart.length == 0) {
                    neighbourStart = new int[boundaryVertexCount + 1];
                    neighbours = buildVertexNeighbours(neighbourStart, boundaryVertexCount,
                            onBoundary);
                }
                int fillFacesBefore = report.fillFaceCount;
                holeFilled[holes] = fillLoop(loop, length, vertexSigma, neighbourStart, neighbours);
                holeFillFaceCounts[holes] = report.fillFaceCount - fillFacesBefore;
            }
            if (holeFilled[holes]) {
                report.filledHoleCount++;
            } else {
                report.openHoleCount++;
                if (withinCap) {
                    report.unfillableHoleCount++;
                } else {
                    report.oversizedHoleCount++;
                }
            }
            holes++;
        }
        report.holeCount = holes;
        recordHoles(holeEdgeCounts, holeFilled, holePerimeters, holeFillFaceCounts, holes);
    }

    /**
     * Perimeter of one boundary loop, summed over its edges in traversal order.
     *
     * @param loop loop vertices in hole-traversal order
     * @param length vertices in the loop
     * @return summed edge length of the loop
     */
    private double loopPerimeter(int[] loop, int length) {
        double perimeter = 0;
        for (int index = 0; index < length; index++) {
            int here = loop[index] * TRIANGLE_CORNERS;
            int next = loop[(index + 1) % length] * TRIANGLE_CORNERS;
            double deltaX = positions[here] - positions[next];
            double deltaY = positions[here + 1] - positions[next + 1];
            double deltaZ = positions[here + 2] - positions[next + 2];
            perimeter += Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        }
        return perimeter;
    }

    /**
     * Writes the per-hole arrays into the report, longest loop first.
     *
     * @param holeEdgeCounts edge count of each loop in discovery order
     * @param holeFilled whether each loop was filled, in discovery order
     * @param holePerimeters perimeter of each loop, in discovery order
     * @param holeFillFaceCounts triangles added to each loop, in discovery order
     * @param holes loops found
     */
    private void recordHoles(int[] holeEdgeCounts, boolean[] holeFilled, double[] holePerimeters,
            int[] holeFillFaceCounts, int holes) {
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
     * Triangulates, refines and fairs one boundary loop, appending the result to the working mesh.
     *
     * @param loop loop vertices in hole-traversal order
     * @param length vertices in the loop
     * @param vertexSigma mean incident edge length per working vertex
     * @param neighbourStart vertex adjacency offsets
     * @param neighbours vertex adjacency entries
     * @return whether the loop was filled
     */
    private boolean fillLoop(int[] loop, int length, double[] vertexSigma, int[] neighbourStart,
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
            int vertex = loop[index];
            incidence += neighbourStart[vertex + 1] - neighbourStart[vertex];
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
            for (int cursor = neighbourStart[vertex]; cursor < neighbourStart[vertex + 1];
                    cursor++) {
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
        filler.build();
        if (!filler.filled) {
            return false;
        }

        int fixedCount = length + ringCount;
        int[] meshOfLocal = new int[filler.vertexCount];
        System.arraycopy(loop, 0, meshOfLocal, 0, length);
        System.arraycopy(ringVertices, 0, meshOfLocal, length, ringCount);
        for (int local = fixedCount; local < filler.vertexCount; local++) {
            meshOfLocal[local] = addVertex((float) filler.vertexX[local],
                    (float) filler.vertexY[local], (float) filler.vertexZ[local]);
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
     * Mean length of the mesh edges incident to each wanted working vertex, which is the density
     * the hole-fill refinement matches.
     *
     * @param wanted which vertices the caller will read
     * @return one mean length per working vertex, zero where unwanted or isolated
     */
    private double[] meanIncidentEdgeLength(boolean[] wanted) {
        double[] total = new double[vertexCount];
        int[] count = new int[vertexCount];
        for (int edge = 0; edge < edgeCount; edge++) {
            int low = EdgeKey.minVertex(edgeKeys[edge]);
            int high = EdgeKey.maxVertex(edgeKeys[edge]);
            boolean wantLow = wanted[low];
            boolean wantHigh = wanted[high];
            if (!wantLow && !wantHigh) {
                continue;
            }
            double length = Math.sqrt(squaredDistance(low, high));
            if (wantLow) {
                total[low] += length;
                count[low]++;
            }
            if (wantHigh) {
                total[high] += length;
                count[high]++;
            }
        }
        double[] mean = new double[vertexCount];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            mean[vertex] = count[vertex] == 0 ? 0 : total[vertex] / count[vertex];
        }
        return mean;
    }

    /**
     * Builds the wanted working vertices' adjacency in compressed row form; unwanted vertices get
     * an empty row.
     *
     * @param neighbourStart offsets array of {@code count + 1} entries, filled
     * @param count working vertices the edge structure was built over
     * @param wanted which vertices get their neighbours listed
     * @return the neighbour entries
     */
    private int[] buildVertexNeighbours(int[] neighbourStart, int count, boolean[] wanted) {
        int[] degree = new int[count];
        for (int edge = 0; edge < edgeCount; edge++) {
            int low = EdgeKey.minVertex(edgeKeys[edge]);
            int high = EdgeKey.maxVertex(edgeKeys[edge]);
            if (wanted[low]) {
                degree[low]++;
            }
            if (wanted[high]) {
                degree[high]++;
            }
        }
        int running = 0;
        for (int vertex = 0; vertex < count; vertex++) {
            neighbourStart[vertex] = running;
            running += degree[vertex];
        }
        neighbourStart[count] = running;
        int[] cursor = Arrays.copyOf(neighbourStart, count);
        int[] neighbours = new int[running];
        for (int edge = 0; edge < edgeCount; edge++) {
            int low = EdgeKey.minVertex(edgeKeys[edge]);
            int high = EdgeKey.maxVertex(edgeKeys[edge]);
            if (wanted[low]) {
                neighbours[cursor[low]++] = high;
            }
            if (wanted[high]) {
                neighbours[cursor[high]++] = low;
            }
        }
        return neighbours;
    }

    /** Compacts the survivors into a {@link HalfEdgeMesh} and rebuilds the bundle's slots. */
    private void assemble() {
        ensureEdges();
        for (int edge = 0; edge < edgeCount; edge++) {
            int start = edgeUseStart[edge];
            int uses = edgeUseStart[edge + 1] - start;
            if (uses == 1) {
                report.outputBoundaryEdgeCount++;
            } else if (uses == 2
                    && cornerForward[edgeUses[start]] == cornerForward[edgeUses[start + 1]]) {
                report.unorientedEdgeCount++;
            }
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
            if (target < 0) {
                continue;
            }
            System.arraycopy(positions, vertex * TRIANGLE_CORNERS, outPositions,
                    target * TRIANGLE_CORNERS, TRIANGLE_CORNERS);
            outOriginal[target] = originalOfVertex[vertex];
        }
        int[] outFaces = new int[aliveFaces * TRIANGLE_CORNERS];
        double[] outU = cornerU == null ? null : new double[aliveFaces * TRIANGLE_CORNERS];
        double[] outV = cornerU == null ? null : new double[aliveFaces * TRIANGLE_CORNERS];
        int nextFace = 0;
        for (int face = 0; face < faceCount; face++) {
            if (!faceAlive[face]) {
                continue;
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
        HalfEdgeMesh repaired = HalfEdgeMeshEngine.buildFromIndexedMesh(outPositions, outFaces);
        report.outputVertexCount = survivors;
        report.outputFaceCount = aliveFaces;

        GeometryBundle bundle = input.withMesh(repaired);
        if (outU != null) {
            bundle = bundle.withSlot(CornerUvField.SLOT, new CornerUvField(outU, outV));
        }
        bundle = carryVertexSlots(bundle, outOriginal);
        bundle = bundle.withSlot(EdgeMarks.SLOT, Map.of());
        bundle = EdgeMarks.with(bundle, MeshRepairReport.OPEN_BOUNDARY_LABEL,
                boundaryEdgeMarks(repaired));
        output = bundle.withSlot(MeshRepairReport.SLOT, report);
    }

    /**
     * Re-indexes the per-vertex slots onto the repaired vertices: tags and bone weights follow the
     * vertex each output vertex descends from, and vertices the repair minted get neither.
     *
     * @param bundle bundle carrying the repaired mesh
     * @param originalOfOutput input vertex each output vertex descends from, or {@code -1}
     * @return the bundle with its per-vertex slots rewritten
     */
    @SuppressWarnings("unchecked")
    private GeometryBundle carryVertexSlots(GeometryBundle bundle, int[] originalOfOutput) {
        GeometryBundle carried = bundle;
        Object tagSlot = input.slots().get(TagGeometryNode.TAGS_SLOT);
        if (tagSlot instanceof Map<?, ?> tags) {
            Map<String, boolean[]> rebuilt = new LinkedHashMap<>();
            for (Map.Entry<String, boolean[]> entry
                    : ((Map<String, boolean[]>) tags).entrySet()) {
                boolean[] source = entry.getValue();
                boolean[] target = new boolean[originalOfOutput.length];
                for (int vertex = 0; vertex < originalOfOutput.length; vertex++) {
                    int origin = originalOfOutput[vertex];
                    target[vertex] = origin >= 0 && origin < source.length && source[origin];
                }
                rebuilt.put(entry.getKey(), target);
            }
            carried = carried.withSlot(TagGeometryNode.TAGS_SLOT, rebuilt);
        }
        for (Map.Entry<String, Object> entry : input.slots().entrySet()) {
            if (!entry.getKey().startsWith(SetBoneWeightNode.BONE_WEIGHT_PREFIX)
                    || !(entry.getValue() instanceof float[] source)) {
                continue;
            }
            float[] target = new float[originalOfOutput.length];
            for (int vertex = 0; vertex < originalOfOutput.length; vertex++) {
                int origin = originalOfOutput[vertex];
                target[vertex] = origin >= 0 && origin < source.length ? source[origin] : 0;
            }
            carried = carried.withSlot(entry.getKey(), target);
        }
        return carried;
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

    /**
     * Drops the uses of faces that have died since the edge structure was built, and the edges
     * left with none, so a stage that only deletes faces needs no rebuild.
     */
    private void dropDeadEdgeUses() {
        int write = 0;
        int survivors = 0;
        for (int edge = 0; edge < edgeCount; edge++) {
            int start = edgeUseStart[edge];
            int end = edgeUseStart[edge + 1];
            int firstWrite = write;
            for (int use = start; use < end; use++) {
                int slot = edgeUses[use];
                if (faceAlive[slot / TRIANGLE_CORNERS]) {
                    edgeUses[write++] = slot;
                }
            }
            if (write > firstWrite) {
                edgeKeys[survivors] = edgeKeys[edge];
                edgeUseStart[survivors] = firstWrite;
                survivors++;
            }
        }
        edgeCount = survivors;
        edgeUseStart[edgeCount] = write;
    }

    /** Rebuilds the edge structure when a stage has changed the faces since it was last built. */
    private void ensureEdges() {
        if (edgesDirty) {
            buildEdges();
            edgesDirty = false;
        }
    }

    /**
     * Rebuilds the undirected edge structure over the alive faces: the sorted distinct edges, the
     * corner slots using each one, and each slot's edge and direction. Buffers are kept between
     * calls.
     */
    private void buildEdges() {
        int slots = faceCount * TRIANGLE_CORNERS;
        if (cornerForward.length < slots) {
            cornerForward = new boolean[Math.max(slots, cornerForward.length * GROWTH)];
        }
        if (edgeLowStart.length < vertexCount + 1) {
            int capacity = Math.max(vertexCount + 1, edgeLowStart.length * GROWTH);
            edgeLowStart = new int[capacity];
            edgeLowCursor = new int[capacity];
        }
        Arrays.fill(edgeLowStart, 0, vertexCount + 1, 0);
        int useTotal = 0;
        for (int face = 0; face < faceCount; face++) {
            if (!faceAlive[face]) {
                continue;
            }
            useTotal += TRIANGLE_CORNERS;
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int slot = face * TRIANGLE_CORNERS + corner;
                int from = faceCorners[slot];
                int to = faceCorners[face * TRIANGLE_CORNERS + (corner + 1) % TRIANGLE_CORNERS];
                cornerForward[slot] = from < to;
                edgeLowStart[Math.min(from, to)]++;
            }
        }
        int running = 0;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int degree = edgeLowStart[vertex];
            edgeLowStart[vertex] = running;
            edgeLowCursor[vertex] = running;
            running += degree;
        }
        edgeLowStart[vertexCount] = running;
        if (edgeUsePacked.length < useTotal) {
            int capacity = Math.max(useTotal, edgeUsePacked.length * GROWTH);
            edgeUsePacked = new long[capacity];
            edgeKeys = new long[capacity];
            edgeUses = new int[capacity];
            edgeUseStart = new int[capacity + 1];
        }
        for (int face = 0; face < faceCount; face++) {
            if (!faceAlive[face]) {
                continue;
            }
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                int slot = face * TRIANGLE_CORNERS + corner;
                int from = faceCorners[slot];
                int to = faceCorners[face * TRIANGLE_CORNERS + (corner + 1) % TRIANGLE_CORNERS];
                int low = Math.min(from, to);
                int high = Math.max(from, to);
                edgeUsePacked[edgeLowCursor[low]++] = ((long) high << KEY_SHIFT) | slot;
            }
        }
        edgeCount = 0;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int bucketStart = edgeLowStart[vertex];
            int bucketEnd = edgeLowStart[vertex + 1];
            if (bucketStart == bucketEnd) {
                continue;
            }
            if (bucketEnd - bucketStart <= SMALL_BUCKET) {
                for (int use = bucketStart + 1; use < bucketEnd; use++) {
                    long value = edgeUsePacked[use];
                    int back = use - 1;
                    while (back >= bucketStart && edgeUsePacked[back] > value) {
                        edgeUsePacked[back + 1] = edgeUsePacked[back];
                        back--;
                    }
                    edgeUsePacked[back + 1] = value;
                }
            } else {
                Arrays.sort(edgeUsePacked, bucketStart, bucketEnd);
            }
            int previousHigh = -1;
            for (int use = bucketStart; use < bucketEnd; use++) {
                int high = (int) (edgeUsePacked[use] >>> KEY_SHIFT);
                if (use == bucketStart || high != previousHigh) {
                    edgeKeys[edgeCount] = EdgeKey.undirected(vertex, high);
                    edgeUseStart[edgeCount] = use;
                    edgeCount++;
                    previousHigh = high;
                }
                edgeUses[use] = (int) (edgeUsePacked[use] & KEY_MASK);
            }
        }
        edgeUseStart[edgeCount] = useTotal;
    }

    /**
     * Whether an edge carries exactly two faces that run it in opposite directions, which is what
     * the half-edge build accepts.
     *
     * @param edge edge index
     * @return true when the edge is manifold
     */
    private boolean manifoldEdge(int edge) {
        int start = edgeUseStart[edge];
        return edgeUseStart[edge + 1] - start == 2
                && cornerForward[edgeUses[start]] != cornerForward[edgeUses[start + 1]];
    }

    /**
     * Area of one working face.
     *
     * @param face working face index
     * @return the triangle's area
     */
    private double faceArea(int face) {
        return TriangleGeometry.area(positions, faceCorners[face * TRIANGLE_CORNERS],
                faceCorners[face * TRIANGLE_CORNERS + 1],
                faceCorners[face * TRIANGLE_CORNERS + 2]);
    }

    /**
     * Squared distance between two working vertices.
     *
     * @param first one vertex
     * @param second the other vertex
     * @return the squared distance
     */
    private double squaredDistance(int first, int second) {
        double deltaX = positions[first * TRIANGLE_CORNERS] - positions[second * TRIANGLE_CORNERS];
        double deltaY = positions[first * TRIANGLE_CORNERS + 1]
                - positions[second * TRIANGLE_CORNERS + 1];
        double deltaZ = positions[first * TRIANGLE_CORNERS + 2]
                - positions[second * TRIANGLE_CORNERS + 2];
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
            int capacity = Math.max(positions.length * GROWTH,
                    (vertexCount + 1) * TRIANGLE_CORNERS);
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
        int copy = addVertex(positions[source * TRIANGLE_CORNERS],
                positions[source * TRIANGLE_CORNERS + 1],
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
            int capacity = Math.max(faceCorners.length * GROWTH,
                    (faceCount + 1) * TRIANGLE_CORNERS);
            faceCorners = Arrays.copyOf(faceCorners, capacity);
            faceAlive = Arrays.copyOf(faceAlive, capacity / TRIANGLE_CORNERS);
            if (cornerU != null) {
                cornerU = Arrays.copyOf(cornerU, capacity);
                cornerV = Arrays.copyOf(cornerV, capacity);
                Arrays.fill(cornerU, faceCount * TRIANGLE_CORNERS, capacity, Double.NaN);
                Arrays.fill(cornerV, faceCount * TRIANGLE_CORNERS, capacity, Double.NaN);
            }
        }
        faceCorners[faceCount * TRIANGLE_CORNERS] = cornerA;
        faceCorners[faceCount * TRIANGLE_CORNERS + 1] = cornerB;
        faceCorners[faceCount * TRIANGLE_CORNERS + 2] = cornerC;
        faceAlive[faceCount] = true;
        edgesDirty = true;
        if (cornerU != null) {
            cornerU[faceCount * TRIANGLE_CORNERS] = Double.NaN;
            cornerU[faceCount * TRIANGLE_CORNERS + 1] = Double.NaN;
            cornerU[faceCount * TRIANGLE_CORNERS + 2] = Double.NaN;
            cornerV[faceCount * TRIANGLE_CORNERS] = Double.NaN;
            cornerV[faceCount * TRIANGLE_CORNERS + 1] = Double.NaN;
            cornerV[faceCount * TRIANGLE_CORNERS + 2] = Double.NaN;
        }
        faceCount++;
    }

}
