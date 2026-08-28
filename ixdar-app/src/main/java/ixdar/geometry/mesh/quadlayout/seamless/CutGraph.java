package ixdar.geometry.mesh.quadlayout.seamless;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.ChartAtlas;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;

/**
 * The combinatorial layout induced by cutting the surface open along a seam:
 * which edges are seams, the per-face branch labels, the seam rotation
 * transitions, the chart-vertex identification, and the dense numbering of
 * interior seam edges.
 */
public class CutGraph {

    /**
     * Multiplier applied to alignment-edge weights in
     * {@link #shortestMeshPathToCut} so singularity-to-cut routing avoids putting a
     * feature edge on the seam unless no non-alignment path exists.
     */
    public static final double ALIGNMENT_PATH_PENALTY = 1.0e6;

    /**
     * True iff the edge at this active-edge index is a seam edge or a mesh boundary
     * edge.
     */
    public boolean[] isCutEdge;

    /**
     * Cut transition rotation r<sub>e</sub> ∈ {0,1,2,3}; valid only where
     * {@link #isCutEdge}.
     */
    public int[] cutRotation;

    /** Number of interior cut edges. */
    public int interiorCutEdgeCount;

    /**
     * Active-edge index → dense index in [0, interiorCutEdgeCount), -1 otherwise.
     */
    public int[] cutEdgeDenseIdx;

    /** Number of chart vertices. */
    public int chartVertexCount;

    /** Length 3*F, indexed by (activeFace * 3 + corner) → chart-vertex index. */
    public int[] cornerToChartVertex;

    /** Active-face index → branch g_f ∈ {0..3}. */
    public int[] faceBranch;

    /**
     * A chart vertex is primary iff it sits on the canonical {@code edgeFaceA} side
     * of at least one cut edge, or it touches no cut edge at all.
     */
    public boolean[] chartVertexIsPrimary;

    /**
     * Per chart vertex: cut edge whose seam compatibility equation eliminates this
     * chart vertex by substitution; -1 if primary.
     */
    public int[] secondaryEdge;

    /**
     * Per chart vertex: the partner chart vertex on the "A" side at the same
     * endpoint of {@link #secondaryEdge}; -1 if primary.
     */
    public int[] secondaryPartner;

    /**
     * Per primary chart vertex: dense index in {@code [0, primaryChartCount)}; -1
     * if secondary.
     */
    public int[] primaryChartIndex;

    /** Number of primary chart vertices. */
    public int primaryChartCount;

    /**
     * Leftover seam-compatibility records — seam equations not eliminated by
     * per-cut-edge substitution because the B-side chart vertex was already claimed
     * by another cut edge or is primary. Each record is
     * {@code [activeEdge, chartA, chartB, endpoint]}
     */
    public int[][] leftoverConstraints;

    /**
     * Active-vertex index → number of incident edges currently in the cut graph.
     */
    public int[] cutDegree;

    /**
     * Mesh-vertex-id → active-vertex-index, lazily built.
     */
    public HashMap<Integer, Integer> vertexActiveCache;

    /** The mesh. */
    public final HalfEdgeMesh mesh;

    /** The cross field. */
    public final CrossField crossField;

    /** The seamless parameterization owning this cut graph. */
    public SeamlessUv seamless;

    /** Number of active vertices. */
    public int vertexCount;

    /**
     * Per-face charts and the cut transitions between them; translations are
     * written by each seamless solution write-back.
     */
    public ChartAtlas atlas;

    /**
     * Constructor.
     *
     * @param mesh                     the mesh
     * @param crossField               the cross field
     * @param seamlessUv the seamless parametrization data
     */
    public CutGraph(HalfEdgeMesh mesh, CrossField crossField, SeamlessUv seamlessUv) {
        this.mesh = mesh;
        this.crossField = crossField;
        this.seamless = seamlessUv;
        this.vertexCount = mesh.vertexCount();
    }

    /**
     * Run the full pipeline: choose the seam edges, then derive the branch labels,
     * seam rotations, chart vertices and dense seam-edge indices implied by that
     * choice.
     */
    public void buildCutGraph() {
        selectCutEdges();
        propagateBranches();
        buildCutRotation();
        buildChartVertices();
        buildDenseIndices();
        classifyChartVerticesForSubstitution();
        buildAtlas();
    }

    /**
     * Choose the seam edge set: complement of a min-cost dual spanning tree biased
     * to absorb {@link CrossField#alignmentEdgeIds}, then trim dangling whiskers,
     * then route every interior singularity onto the cut at cut-degree one.
     */
    private void selectCutEdges() {
        initialCutFromDualSpanningTree();
        cutDegree = computeCutDegree();
        trimDanglingBranches();
        connectDetachedSingularities();
    }

    /**
     * Initialize the seam set to the complement of a min-cost dual spanning tree.
     * Alignment edges cost zero so they end up non-cut wherever topology allows: a
     * feature edge on the cut with rotation {@code r ≠ 0} would collapse to a point.
     * Boundary edges stay cut.
     *
     * <p>See also: BZK09 Section 5.2
     */
    private void initialCutFromDualSpanningTree() {
        isCutEdge = new boolean[seamless.edgeCount];
        Arrays.fill(isCutEdge, true);

        double[] distance = new double[seamless.faceCount];
        int[] parentEdge = new int[seamless.faceCount];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        Arrays.fill(parentEdge, -1);

        PriorityQueue<double[]> frontier = new PriorityQueue<>((a, b) -> Double.compare(
                a[0], b[0]));
        if (seamless.faceCount > 0) {
            distance[0] = 0.0;
            frontier.add(new double[] { 0.0, 0 });
        }
        while (!frontier.isEmpty()) {
            double[] top = frontier.poll();
            double distHere = top[0];
            int activeFace = (int) top[1];
            if (distHere > distance[activeFace]) {
                continue;
            }
            int faceId = mesh.faceIdAt(activeFace);
            for (int corner = 0; corner < SeamlessUv.CORNERS_PER_FACE; corner++) {
                int edgeId = mesh.faceEdgeAt(faceId, corner);
                int activeEdge = crossField.edgeIdToActive.get(edgeId);
                int otherActiveFace = (seamless.edgeFaceA[activeEdge] == activeFace)
                        ? seamless.edgeFaceB[activeEdge]
                        : seamless.edgeFaceA[activeEdge];
                if (otherActiveFace < 0) {
                    continue;
                }
                double newDistance = distHere + (crossField.alignmentEdgeIds.contains(edgeId) ? 0.0 : 1.0);
                if (newDistance < distance[otherActiveFace]) {
                    distance[otherActiveFace] = newDistance;
                    parentEdge[otherActiveFace] = activeEdge;
                    frontier.add(new double[] { newDistance, otherActiveFace });
                }
            }
        }
        for (int activeFace = 0; activeFace < seamless.faceCount; activeFace++) {
            int treeEdge = parentEdge[activeFace];
            if (treeEdge >= 0) {
                isCutEdge[treeEdge] = false;
            }
        }
    }

    /**
     * Count, per active vertex, how many of its incident edges are currently cut.
     *
     * @return the cut degree array
     */
    private int[] computeCutDegree() {
        int[] cutDegree = new int[vertexCount];
        for (int activeEdge = 0; activeEdge < seamless.edgeCount; activeEdge++) {
            if (!isCutEdge[activeEdge])
                continue;
            int halfEdge = mesh.edgeHalfEdge(mesh.edgeIdAt(activeEdge));
            cutDegree[activeVertexIndex(mesh.halfEdgeVertex(halfEdge))]++;
            cutDegree[activeVertexIndex(mesh.halfEdgeEndVertex(halfEdge))]++;
        }
        return cutDegree;
    }

    /**
     * Repeatedly remove the lone cut edge at any non-singularity, non-boundary
     * vertex with cut-degree 1, until none remain — this strips the dead branches
     * left by the spanning-tree complement. Boundary edges are never removed.
     */
    private void trimDanglingBranches() {
        Set<Integer> singularityVertexIds = new HashSet<>();
        for (Singularity singularity : crossField.singularities)
            singularityVertexIds.add(singularity.vertexId());

        ArrayDeque<Integer> trimQueue = new ArrayDeque<>();
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            if (cutDegree[activeVertex] == 1 && !singularityVertexIds.contains(vertexId)
                    && !mesh.isBoundaryVertex(vertexId)) {
                trimQueue.add(activeVertex);
            }
        }
        while (!trimQueue.isEmpty()) {
            int activeVertex = trimQueue.poll();
            int vertexId = mesh.vertexIdAt(activeVertex);
            if (cutDegree[activeVertex] != 1) {
                continue;
            }
            if (singularityVertexIds.contains(vertexId) || mesh.isBoundaryVertex(vertexId)) {
                continue;
            }
            int incidentEdgeCount = mesh.vertexEdgeCount(vertexId);
            for (int i = 0; i < incidentEdgeCount; i++) {
                int edgeId = mesh.vertexEdgeAt(vertexId, i);
                int activeEdge = crossField.edgeIdToActive.get(edgeId);
                if (!isCutEdge[activeEdge] || mesh.isBoundaryEdge(edgeId)) {
                    continue;
                }
                isCutEdge[activeEdge] = false;
                cutDegree[activeVertex]--;
                int halfEdge = mesh.edgeHalfEdge(edgeId);
                int otherVertexId = (mesh.halfEdgeVertex(halfEdge) == vertexId)
                        ? mesh.halfEdgeEndVertex(halfEdge)
                        : mesh.halfEdgeVertex(halfEdge);
                int otherActiveVertex = activeVertexIndex(otherVertexId);
                cutDegree[otherActiveVertex]--;
                if (cutDegree[otherActiveVertex] == 1
                        && !singularityVertexIds.contains(otherVertexId)
                        && !mesh.isBoundaryVertex(otherVertexId)) {
                    trimQueue.add(otherActiveVertex);
                }
                break;
            }
        }
    }

    /**
     * Route every interior singularity that is not already on the cut to it along
     * the shortest mesh-edge path.
     */
    private void connectDetachedSingularities() {
        for (Singularity singularity : crossField.singularities) {
            int vertexId = singularity.vertexId();
            if (cutDegree[activeVertexIndex(vertexId)] > 0 || mesh.isBoundaryVertex(vertexId)) {
                continue;
            }
            double[] distance = new double[vertexCount];
            int[] prevVertex = new int[vertexCount];
            int[] prevEdge = new int[vertexCount];
            Arrays.fill(distance, Double.POSITIVE_INFINITY);
            Arrays.fill(prevVertex, -1);
            Arrays.fill(prevEdge, -1);
            int startActiveVertex = activeVertexIndex(vertexId);
            distance[startActiveVertex] = 0.0;

            PriorityQueue<double[]> frontier = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
            frontier.add(new double[] { 0.0, startActiveVertex });

            Vector3f posHere = new Vector3f();
            Vector3f posOther = new Vector3f();

            int reachedActiveVertex = -1;
            while (!frontier.isEmpty()) {
                double[] top = frontier.poll();
                double distHere = top[0];
                int activeVertex = (int) top[1];
                if (distHere > distance[activeVertex]) {
                    continue;
                }
                int activeVertexId = mesh.vertexIdAt(activeVertex);
                if (cutDegree[activeVertex] > 0 && activeVertex != startActiveVertex) {
                    reachedActiveVertex = activeVertex;
                    break;
                }
                mesh.vertexPosition(activeVertexId, posHere);
                int incidentEdgeCount = mesh.vertexEdgeCount(activeVertexId);
                for (int i = 0; i < incidentEdgeCount; i++) {
                    int edgeId = mesh.vertexEdgeAt(activeVertexId, i);
                    int activeEdge = crossField.edgeIdToActive.get(edgeId);
                    if (activeVertex == startActiveVertex) {
                        continue;
                    }
                    int halfEdge = mesh.edgeHalfEdge(edgeId);
                    int otherVertexId = (mesh.halfEdgeVertex(halfEdge) == activeVertexId)
                            ? mesh.halfEdgeEndVertex(halfEdge)
                            : mesh.halfEdgeVertex(halfEdge);
                    int otherActiveVertex = activeVertexIndex(otherVertexId);
                    mesh.vertexPosition(otherVertexId, posOther);
                    double edgeLength = posHere.distance(posOther);
                    double edgeCost = crossField.alignmentEdgeIds.contains(edgeId)
                            ? edgeLength * ALIGNMENT_PATH_PENALTY
                            : edgeLength;
                    double newDistance = distHere + edgeCost;
                    if (newDistance < distance[otherActiveVertex]) {
                        distance[otherActiveVertex] = newDistance;
                        prevVertex[otherActiveVertex] = activeVertex;
                        prevEdge[otherActiveVertex] = activeEdge;
                        frontier.add(new double[] { newDistance, otherActiveVertex });
                    }
                }
            }
            if (reachedActiveVertex < 0) {
                return;
            }
            int pathLength = 0;
            for (int v = reachedActiveVertex; v != startActiveVertex; v = prevVertex[v]) {
                pathLength++;
            }
            int[] pathEdges = new int[pathLength];
            for (int v = reachedActiveVertex, i = 0; v != startActiveVertex; v = prevVertex[v]) {
                pathEdges[i++] = prevEdge[v];
            }
            for (int activeEdge : pathEdges) {
                if (isCutEdge[activeEdge]) {
                    continue;
                }
                isCutEdge[activeEdge] = true;
                int halfEdge = mesh.edgeHalfEdge(mesh.edgeIdAt(activeEdge));
                cutDegree[activeVertexIndex(mesh.halfEdgeVertex(halfEdge))]++;
                cutDegree[activeVertexIndex(mesh.halfEdgeEndVertex(halfEdge))]++;
            }
        }
    }

    /**
     * Assign each active face a branch label g_f ∈ {0..3} by BFS over non-cut
     * interior edges, seeding each connected component at 0.
     *
     * <p>
     * {@code edgeHalfEdge} is oriented A→B, so continuity means g_B = (g_A − p_AB)
     * mod 4; traversing B→A flips the period jump's sign.
     */
    private void propagateBranches() {
        faceBranch = new int[seamless.faceCount];
        Arrays.fill(faceBranch, -1);

        ArrayDeque<Integer> faceQueue = new ArrayDeque<>();
        for (int seed = 0; seed < seamless.faceCount; seed++) {
            if (faceBranch[seed] != -1)
                continue;
            faceBranch[seed] = 0;
            faceQueue.add(seed);
            while (!faceQueue.isEmpty()) {
                int activeFace = faceQueue.poll();
                int faceId = mesh.faceIdAt(activeFace);
                for (int corner = 0; corner < SeamlessUv.CORNERS_PER_FACE; corner++) {
                    int activeEdge = crossField.edgeIdToActive.get(mesh.faceEdgeAt(faceId, corner));
                    if (isCutEdge[activeEdge])
                        continue;
                    int activeFaceA = seamless.edgeFaceA[activeEdge];
                    int activeFaceB = seamless.edgeFaceB[activeEdge];
                    if (activeFaceA < 0 || activeFaceB < 0)
                        continue;
                    int otherActiveFace = (activeFaceA == activeFace) ? activeFaceB : activeFaceA;
                    if (faceBranch[otherActiveFace] != -1)
                        continue;
                    int periodJump = crossField.periodJump[activeEdge];
                    int branchMask = SeamlessUv.BRANCH_COUNT - 1;
                    faceBranch[otherActiveFace] = (activeFaceA == activeFace)
                            ? (faceBranch[activeFace] - periodJump) & branchMask
                            : (faceBranch[activeFace] + periodJump) & branchMask;
                    faceQueue.add(otherActiveFace);
                }
            }
        }
    }

    /**
     * Compute the coordinate transition rotation r_e ∈ {0..3} for every edge: 0 on
     * non-cut and boundary edges, and (g_A − g_B − p_AB) mod 4 on an interior cut
     * edge. Parameter coordinates transform by the inverse of the basis rotation
     * (g_B − g_A + p_AB)·π/2.
     */
    public void buildCutRotation() {
        cutRotation = new int[seamless.edgeCount];
        for (int activeEdge = 0; activeEdge < seamless.edgeCount; activeEdge++) {
            int activeFaceA = seamless.edgeFaceA[activeEdge];
            int activeFaceB = seamless.edgeFaceB[activeEdge];
            if (!isCutEdge[activeEdge] || activeFaceA < 0 || activeFaceB < 0) {
                cutRotation[activeEdge] = 0;
                continue;
            }
            int periodJump = crossField.periodJump[activeEdge];
            cutRotation[activeEdge] = (faceBranch[activeFaceA] - faceBranch[activeFaceB] - periodJump)
                    & (SeamlessUv.BRANCH_COUNT - 1);
        }
    }

    /**
     * Identify chart vertices: union the corners on each endpoint of every non-cut
     * interior edge, then compact the union-find roots into
     * {@link #cornerToChartVertex}. Face B's half-edge runs opposite face A's, so
     * corner {@code start} pairs with {@code start} and {@code start+1} with
     * {@code start−1}.
     */
    private void buildChartVertices() {
        final int cornersPerFace = SeamlessUv.CORNERS_PER_FACE;
        int totalCorners = seamless.faceCount * cornersPerFace;
        int[] parent = new int[totalCorners];
        int[] rank = new int[totalCorners];
        for (int corner = 0; corner < totalCorners; corner++)
            parent[corner] = corner;

        for (int activeEdge = 0; activeEdge < seamless.edgeCount; activeEdge++) {
            if (isCutEdge[activeEdge])
                continue;
            int activeFaceA = seamless.edgeFaceA[activeEdge];
            int activeFaceB = seamless.edgeFaceB[activeEdge];
            if (activeFaceA < 0 || activeFaceB < 0)
                continue;
            int cornerStartA = seamless.edgeCornerInA[activeEdge];
            int cornerStartB = seamless.edgeCornerInB[activeEdge];
            int cornerEndA = (cornerStartA + 1) % cornersPerFace;
            int cornerEndB = (cornerStartB + cornersPerFace - 1) % cornersPerFace;
            unionCorners(parent, rank, activeFaceA * cornersPerFace + cornerStartA,
                    activeFaceB * cornersPerFace + cornerStartB);
            unionCorners(parent, rank, activeFaceA * cornersPerFace + cornerEndA,
                    activeFaceB * cornersPerFace + cornerEndB);
        }

        cornerToChartVertex = new int[totalCorners];
        HashMap<Integer, Integer> rootToChartVertex = new HashMap<>();
        for (int corner = 0; corner < totalCorners; corner++) {
            int root = findCorner(parent, corner);
            Integer chartVertex = rootToChartVertex.get(root);
            if (chartVertex == null) {
                chartVertex = rootToChartVertex.size();
                rootToChartVertex.put(root, chartVertex);
            }
            cornerToChartVertex[corner] = chartVertex;
        }
        chartVertexCount = rootToChartVertex.size();
    }

    /**
     * Union-find {@code find} with path halving.
     *
     * @param parent the parent array
     * @param corner the corner to find
     * @return the root of the corner
     */
    private static int findCorner(int[] parent, int corner) {
        while (parent[corner] != corner) {
            parent[corner] = parent[parent[corner]];
            corner = parent[corner];
        }
        return corner;
    }

    /**
     * Union-find {@code union} by rank.
     *
     * @param parent  the parent array
     * @param rank    the rank array
     * @param cornerA the first corner
     * @param cornerB the second corner
     */
    private static void unionCorners(int[] parent, int[] rank, int cornerA, int cornerB) {
        int rootA = findCorner(parent, cornerA);
        int rootB = findCorner(parent, cornerB);
        if (rootA == rootB)
            return;
        if (rank[rootA] < rank[rootB]) {
            parent[rootA] = rootB;
        } else if (rank[rootA] > rank[rootB]) {
            parent[rootB] = rootA;
        } else {
            parent[rootB] = rootA;
            rank[rootA]++;
        }
    }

    /**
     * Assign each interior cut edge a dense index in
     * {@code [0, interiorCutEdgeCount)} for the seam translation variables;
     * boundary cut edges (only one incident face, hence no transition) get -1.
     */
    public void buildDenseIndices() {
        cutEdgeDenseIdx = new int[seamless.edgeCount];
        Arrays.fill(cutEdgeDenseIdx, -1);
        int nextIndex = 0;
        for (int activeEdge = 0; activeEdge < seamless.edgeCount; activeEdge++) {
            if (!isCutEdge[activeEdge])
                continue;
            if (seamless.edgeFaceA[activeEdge] < 0 || seamless.edgeFaceB[activeEdge] < 0)
                continue;
            cutEdgeDenseIdx[activeEdge] = nextIndex++;
        }
        interiorCutEdgeCount = nextIndex;
    }

    /**
     * Map a {@link HalfEdgeMesh} vertex id to its dense active-vertex index. The
     * lookup table is built on first call (the mesh may have holes, so id ≠ index).
     *
     * @param vertexId the vertex id to map
     * @throws IllegalStateException if {@code vertexId} is not a live mesh vertex
     * @return the dense active-vertex index
     */
    public int activeVertexIndex(int vertexId) {
        if (vertexActiveCache == null) {
            vertexActiveCache = new HashMap<>(vertexCount * 2);
            for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++)
                vertexActiveCache.put(mesh.vertexIdAt(activeVertex), activeVertex);
        }
        Integer activeVertex = vertexActiveCache.get(vertexId);
        if (activeVertex == null)
            throw new IllegalStateException("unknown vertex id " + vertexId);
        return activeVertex;
    }

    /**
     * Classify chart vertices for variable elimination. A chart vertex is primary if
     * it sits on the canonical {@code edgeFaceA} side of some cut edge or touches
     * none; otherwise the first cut edge holding it on its B side owns its
     * substitution, and further such edges produce leftover records.
     */
    private void classifyChartVerticesForSubstitution() {
        final int cornersPerFace = SeamlessUv.CORNERS_PER_FACE;
        chartVertexIsPrimary = new boolean[chartVertexCount];
        secondaryEdge = new int[chartVertexCount];
        secondaryPartner = new int[chartVertexCount];
        Arrays.fill(secondaryEdge, -1);
        Arrays.fill(secondaryPartner, -1);
        Arrays.fill(chartVertexIsPrimary, true);
    
        // Sweep 1: mark every chart vertex on the A-side of a cut edge.
        boolean[] onASide = new boolean[chartVertexCount];
        for (int activeEdge = 0; activeEdge < seamless.edgeCount; activeEdge++) {
            if (!isCutEdge[activeEdge])
                continue;
            int faceA = seamless.edgeFaceA[activeEdge];
            int faceB = seamless.edgeFaceB[activeEdge];
            if (faceA < 0 || faceB < 0)
                continue;
            int cornerStartA = seamless.edgeCornerInA[activeEdge];
            int cornerEndA = (cornerStartA + 1) % cornersPerFace;
            onASide[cornerToChartVertex[faceA * cornersPerFace + cornerStartA]] = true;
            onASide[cornerToChartVertex[faceA * cornersPerFace + cornerEndA]] = true;
        }
    
        // Sweep 2: bind each cut edge's two B-side chart vertices to their A-side
        // partner via this edge's rotation; defer to a leftover record when the
        // B-side is itself primary or already claimed secondary.
        ArrayList<int[]> leftover = new ArrayList<>();
        for (int activeEdge = 0; activeEdge < seamless.edgeCount; activeEdge++) {
            if (!isCutEdge[activeEdge])
                continue;
            int faceA = seamless.edgeFaceA[activeEdge];
            int faceB = seamless.edgeFaceB[activeEdge];
            if (faceA < 0 || faceB < 0)
                continue;
            int cornerStartA = seamless.edgeCornerInA[activeEdge];
            int cornerEndA = (cornerStartA + 1) % cornersPerFace;
            int cornerStartB = seamless.edgeCornerInB[activeEdge];
            int cornerEndB = (cornerStartB + cornersPerFace - 1) % cornersPerFace;
            bindOrDefer(activeEdge,
                    cornerToChartVertex[faceA * cornersPerFace + cornerStartA],
                    cornerToChartVertex[faceB * cornersPerFace + cornerStartB],
                    onASide, leftover);
            bindOrDefer(activeEdge,
                    cornerToChartVertex[faceA * cornersPerFace + cornerEndA],
                    cornerToChartVertex[faceB * cornersPerFace + cornerEndB],
                    onASide, leftover);
        }
    
        primaryChartIndex = new int[chartVertexCount];
        Arrays.fill(primaryChartIndex, -1);
        int nextPrimary = 0;
        for (int cv = 0; cv < chartVertexCount; cv++) {
            if (chartVertexIsPrimary[cv]) {
                primaryChartIndex[cv] = nextPrimary++;
            }
        }
        primaryChartCount = nextPrimary;
        leftoverConstraints = leftover.toArray(new int[0][]);
    }

    /**
     * Fills the atlas: one chart per active face, one boundary per active edge,
     * the cut rotations as transitions directed {@code edgeFaceA} to
     * {@code edgeFaceB}; translations arrive with each solution write-back.
     */
    private void buildAtlas() {
        atlas = new ChartAtlas(seamless.faceCount, seamless.faceCount, seamless.edgeCount, false);
        for (int activeFace = 0; activeFace < seamless.faceCount; activeFace++) {
            atlas.chartOfFace[activeFace] = activeFace;
        }
        for (int activeEdge = 0; activeEdge < seamless.edgeCount; activeEdge++) {
            int activeFaceA = seamless.edgeFaceA[activeEdge];
            int activeFaceB = seamless.edgeFaceB[activeEdge];
            atlas.chartA[activeEdge] = activeFaceA;
            atlas.chartB[activeEdge] = activeFaceB;
            if (activeFaceA >= 0 && activeFaceB >= 0) {
                atlas.quarterTurns[activeEdge] = cutRotation[activeEdge];
            }
        }
    }
    
    private void bindOrDefer(int activeEdge, int chartA, int chartB,
            boolean[] onASide, ArrayList<int[]> leftover) {
        if (onASide[chartB] || !chartVertexIsPrimary[chartB]
                || secondaryEdge[chartB] != -1) {
            leftover.add(new int[] { activeEdge, chartA, chartB });
            return;
        }
        chartVertexIsPrimary[chartB] = false;
        secondaryEdge[chartB] = activeEdge;
        secondaryPartner[chartB] = chartA;
    }

}
