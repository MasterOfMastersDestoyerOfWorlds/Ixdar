package ixdar.geometry.mesh.quadlayout.seamless;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Set;

import ixdar.geometry.mesh.data.fields.crossfield.CrossField;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.ChartAtlas;
import ixdar.numeric.graph.Dijkstra;
import ixdar.numeric.graph.UnionFind;
import ixdar.platform.Platforms;

/**
 * The combinatorial layout induced by cutting the surface open along a seam:
 * which edges are seams, the per-face branch labels, the seam rotation
 * transitions, the chart-vertex identification, and the dense numbering of
 * interior seam edges.
 */
public class CutGraph {

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
        int cutEdgeCount = 0;
        for (int activeEdge = 0; activeEdge < seamless.edgeCount; activeEdge++) {
            if (isCutEdge[activeEdge]) {
                cutEdgeCount++;
            }
        }
        Platforms.log("[cut-graph] cut edges %d of %d, interior %d, chart vertices %d"
                + " (primary %d) of %d corners%n", cutEdgeCount, seamless.edgeCount,
                interiorCutEdgeCount, chartVertexCount, primaryChartCount,
                SeamlessUv.CORNERS_PER_FACE * seamless.faceCount);
    }

    /**
     * Choose the seam edge set: complement of a min-cost dual spanning tree biased
     * to absorb {@link CrossField#alignmentEdges}, then trim dangling whiskers,
     * then route every interior singularity onto the cut at cut-degree one.
     */
    private void selectCutEdges() {
        initialCutFromDualSpanningTree();
        cutDegree = computeCutDegree();
        trimDanglingBranches();
        connectDetachedSingularities();
    }

    /**
     * Initialize the seam set to the complement of a min-cost dual spanning forest, one tree per
     * connected component. Alignment edges cost zero so they end up non-cut wherever topology
     * allows; boundary edges stay cut.
     *
     * <p>See also: BZK09 Section 5.2
     */
    private void initialCutFromDualSpanningTree() {
        isCutEdge = new boolean[seamless.edgeCount];
        Arrays.fill(isCutEdge, true);

        Dijkstra tree = new Dijkstra();
        tree.start(seamless.faceCount);
        for (int seedFace = 0; seedFace < seamless.faceCount; seedFace++) {
            if (tree.isReached(seedFace)) {
                continue;
            }
            tree.relax(seedFace, 0.0, -1);
            for (int activeFace = tree.settle(); activeFace >= 0; activeFace = tree.settle()) {
                double here = tree.distance(activeFace);
                int faceId = mesh.faceIdAt(activeFace);
                for (int corner = 0; corner < SeamlessUv.CORNERS_PER_FACE; corner++) {
                    int edgeId = mesh.faceEdgeAt(faceId, corner);
                    int activeEdge = crossField.edgeIdToActive[edgeId];
                    int otherActiveFace = (seamless.edgeFaceA[activeEdge] == activeFace)
                            ? seamless.edgeFaceB[activeEdge]
                            : seamless.edgeFaceA[activeEdge];
                    if (otherActiveFace >= 0) {
                        tree.relax(otherActiveFace,
                                here + (crossField.alignmentEdges.get(activeEdge) ? 0.0 : 1.0),
                                activeEdge);
                    }
                }
            }
        }
        for (int activeFace = 0; activeFace < seamless.faceCount; activeFace++) {
            int treeEdge = tree.via(activeFace);
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
        Set<Integer> singularityVertexIds = crossField.singularVertexIds();

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
                int activeEdge = crossField.edgeIdToActive[edgeId];
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
        Dijkstra walk = new Dijkstra();
        for (int singularVertex = 0; singularVertex < crossField.singularityIndex4.length();
                singularVertex++) {
            if (crossField.singularityIndex4.get(singularVertex) == 0) {
                continue;
            }
            int vertexId = mesh.vertexIdAt(singularVertex);
            if (cutDegree[activeVertexIndex(vertexId)] > 0 || mesh.isBoundaryVertex(vertexId)) {
                continue;
            }
            int startActiveVertex = activeVertexIndex(vertexId);
            walk.start(vertexCount);
            walk.relax(startActiveVertex, 0.0, -1);
            int reachedActiveVertex = -1;
            for (int activeVertex = walk.settle(); activeVertex >= 0;
                    activeVertex = walk.settle()) {
                if (cutDegree[activeVertex] > 0 && activeVertex != startActiveVertex) {
                    reachedActiveVertex = activeVertex;
                    break;
                }
                double here = walk.distance(activeVertex);
                int activeVertexId = mesh.vertexIdAt(activeVertex);
                int incidentEdgeCount = mesh.vertexEdgeCount(activeVertexId);
                for (int i = 0; i < incidentEdgeCount; i++) {
                    int edgeId = mesh.vertexEdgeAt(activeVertexId, i);
                    int activeEdge = crossField.edgeIdToActive[edgeId];
                    double edgeLength = mesh.edgeLength(edgeId);
                    double edgeCost = crossField.alignmentEdges.get(activeEdge)
                            ? edgeLength * ALIGNMENT_PATH_PENALTY
                            : edgeLength;
                    walk.relax(activeVertexIndex(mesh.edgeOtherVertex(edgeId, activeVertexId)),
                            here + edgeCost, activeEdge);
                }
            }
            if (reachedActiveVertex < 0) {
                return;
            }
            for (int activeVertex = reachedActiveVertex; activeVertex != startActiveVertex;) {
                int activeEdge = walk.via(activeVertex);
                int edgeId = mesh.edgeIdAt(activeEdge);
                activeVertex = activeVertexIndex(
                        mesh.edgeOtherVertex(edgeId, mesh.vertexIdAt(activeVertex)));
                if (isCutEdge[activeEdge]) {
                    continue;
                }
                isCutEdge[activeEdge] = true;
                int halfEdge = mesh.edgeHalfEdge(edgeId);
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
                    int activeEdge = crossField.edgeIdToActive[mesh.faceEdgeAt(faceId, corner)];
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
        int[] parent = UnionFind.singletons(totalCorners);
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
            UnionFind.union(parent, activeFaceA * cornersPerFace + cornerStartA,
                    activeFaceB * cornersPerFace + cornerStartB);
            UnionFind.union(parent, activeFaceA * cornersPerFace + cornerEndA,
                    activeFaceB * cornersPerFace + cornerEndB);
        }

        cornerToChartVertex = new int[totalCorners];
        HashMap<Integer, Integer> rootToChartVertex = new HashMap<>();
        for (int corner = 0; corner < totalCorners; corner++) {
            int root = UnionFind.find(parent, corner);
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
