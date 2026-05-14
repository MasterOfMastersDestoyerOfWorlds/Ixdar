package ixdar.geometry.mesh.quadlayout.seamless;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;

/**
 * The combinatorial layout induced by cutting the surface open along a seam:
 * which edges are seams, the per-face branch labels, the seam rotation
 * transitions, the chart-vertex identification, and the dense numbering of
 * interior seam edges. Every output here is pure integer combinatorics over
 * (mesh, cross field, choice of cut); the continuous parameterization is fitted
 * on top of it by {@link SeamlessParameterization}.
 *
 * <p>
 * Naming convention used throughout: an <em>id</em> ({@code edgeId},
 * {@code vertexId}, {@code faceId}) is a raw {@link HalfEdgeMesh} handle and
 * may be sparse; an <em>active index</em> ({@code activeEdge},
 * {@code activeVertex}, {@code activeFace}) is the dense {@code [0, count)}
 * index the solver uses, obtained via {@link CrossField#edgeIdToActive},
 * {@link #activeVertexIndex(int)}, {@link SeamlessParameterization#edgeFaceA}
 * and friends.
 */
public class CutGraph {

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

    /** The seamless parameterization owning this cut graph. */
    public SeamlessParameterization seamless;

    /** Number of chart vertices. */
    public int chartVertexCount;

    /** Length 3*F, indexed by (activeFace * 3 + corner) → chart-vertex index. */
    public int[] cornerToChartVertex;

    /** Active-face index → branch g_f ∈ {0..3}. */
    public int[] faceBranch;

    /**
     * Active-vertex index → number of incident edges currently in the cut graph.
     * Built during {@link #selectCutEdges()} and retained for downstream
     * branch-walk consumers like {@code SeamlessProjector}.
     */
    public int[] cutDegree;

    /** Mesh-vertex-id → active-vertex-index, lazily built. */
    public HashMap<Integer, Integer> vertexActiveCache;

    /** The mesh. */
    public final HalfEdgeMesh mesh;

    /** The cross field. */
    public final CrossField crossField;

    /**
     * Constructor.
     * 
     * @param mesh                     the mesh
     * @param crossField               the cross field
     * @param seamlessParameterization the seamless parameterization
     */
    public CutGraph(HalfEdgeMesh mesh, CrossField crossField, SeamlessParameterization seamlessParameterization) {
        this.mesh = mesh;
        this.crossField = crossField;
        this.seamless = seamlessParameterization;
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
    }

    /**
     * Choose the seam edge set: start from the complement of a dual spanning tree
     * (so the cut opens the surface into a disk), trim the dead "whiskers" that
     * leaves behind, route every interior singularity onto the cut, and — for
     * Lyon's seamless input — give every interior singularity cut-degree ≥ 2 so the
     * fan of faces around it splits into separate chart vertices and the cross
     * field's ±π/2 turn there has a transition to absorb. (BZK09's full pipeline
     * instead integer-pins singularity (u, v); that step is skipped when
     * {@link SeamlessParameterization#integerGridMap} is off.)
     */
    private void selectCutEdges() {
        initialCutFromDualSpanningTree();
        cutDegree = computeCutDegree();
        trimDanglingBranches(cutDegree);
        connectDetachedSingularities(cutDegree);
        if (!seamless.integerGridMap) {
            for (Singularity singularity : crossField.singularities) {
                int vertexId = singularity.vertexId();
                if (mesh.isBoundaryVertex(vertexId))
                    continue;
                if (cutDegree[activeVertexIndex(vertexId)] >= 2)
                    continue;
                extendSingularityToDegreeTwo(vertexId, cutDegree);
            }
        }
    }

    /**
     * Initialize the seam set to the complement of a dual spanning tree: mark every
     * edge cut, then BFS the faces through interior two-sided edges, un-cutting
     * each tree edge crossed. Boundary edges stay cut.
     */
    private void initialCutFromDualSpanningTree() {
        isCutEdge = new boolean[seamless.edgeCount];
        Arrays.fill(isCutEdge, true);

        boolean[] faceVisited = new boolean[seamless.faceCount];
        ArrayDeque<Integer> faceQueue = new ArrayDeque<>();
        if (seamless.faceCount > 0) {
            faceVisited[0] = true;
            faceQueue.add(0);
        }
        while (!faceQueue.isEmpty()) {
            int activeFace = faceQueue.poll();
            int faceId = mesh.faceIdAt(activeFace);
            for (int corner = 0; corner < SeamlessParameterization.CORNERS_PER_FACE; corner++) {
                int activeEdge = crossField.edgeIdToActive.get(mesh.faceEdgeAt(faceId, corner));
                int otherActiveFace = (seamless.edgeFaceA[activeEdge] == activeFace)
                        ? seamless.edgeFaceB[activeEdge]
                        : seamless.edgeFaceA[activeEdge];
                if (otherActiveFace < 0 || faceVisited[otherActiveFace])
                    continue;
                faceVisited[otherActiveFace] = true;
                isCutEdge[activeEdge] = false;
                faceQueue.add(otherActiveFace);
            }
        }
    }

    /**
     * Count, per active vertex, how many of its incident edges are currently cut.
     * 
     * @return the cut degree array
     */
    private int[] computeCutDegree() {
        int[] cutDegree = new int[mesh.vertexCount()];
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
     * 
     * @param cutDegree the cut degree array
     */
    private void trimDanglingBranches(int[] cutDegree) {
        Set<Integer> singularityVertexIds = new HashSet<>();
        for (Singularity singularity : crossField.singularities)
            singularityVertexIds.add(singularity.vertexId());

        ArrayDeque<Integer> trimQueue = new ArrayDeque<>();
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            if (cutDegree[activeVertex] == 1 && !singularityVertexIds.contains(vertexId)
                    && !mesh.isBoundaryVertex(vertexId))
                trimQueue.add(activeVertex);
        }
        while (!trimQueue.isEmpty()) {
            int activeVertex = trimQueue.poll();
            int vertexId = mesh.vertexIdAt(activeVertex);
            if (cutDegree[activeVertex] != 1)
                continue;
            if (singularityVertexIds.contains(vertexId) || mesh.isBoundaryVertex(vertexId))
                continue;
            int incidentEdgeCount = mesh.vertexEdgeCount(vertexId);
            for (int i = 0; i < incidentEdgeCount; i++) {
                int edgeId = mesh.vertexEdgeAt(vertexId, i);
                int activeEdge = crossField.edgeIdToActive.get(edgeId);
                if (!isCutEdge[activeEdge] || mesh.isBoundaryEdge(edgeId))
                    continue;
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
                        && !mesh.isBoundaryVertex(otherVertexId))
                    trimQueue.add(otherActiveVertex);
                break;
            }
        }
    }

    /**
     * Route every interior singularity that is not already on the cut to it along
     * the shortest mesh-edge path (BZK09 §5).
     * 
     * @param cutDegree the cut degree array
     */
    private void connectDetachedSingularities(int[] cutDegree) {
        for (Singularity singularity : crossField.singularities) {
            int vertexId = singularity.vertexId();
            if (cutDegree[activeVertexIndex(vertexId)] > 0 || mesh.isBoundaryVertex(vertexId))
                continue;
            connectVertexToCut(vertexId, cutDegree, -1);
        }
    }

    /**
     * Push a degree-1 singularity's cut-degree to 2 by adding the shortest extra
     * cut path that leaves by a different edge than the one already connecting it.
     */
    private void extendSingularityToDegreeTwo(int singularityVertexId, int[] cutDegree) {
        int existingCutEdge = -1;
        int incidentEdgeCount = mesh.vertexEdgeCount(singularityVertexId);
        for (int i = 0; i < incidentEdgeCount; i++) {
            int edgeId = mesh.vertexEdgeAt(singularityVertexId, i);
            int activeEdge = crossField.edgeIdToActive.get(edgeId);
            if (isCutEdge[activeEdge] && !mesh.isBoundaryEdge(edgeId)) {
                existingCutEdge = activeEdge;
                break;
            }
        }
        connectVertexToCut(singularityVertexId, cutDegree, existingCutEdge);
    }

    /**
     * Mark the shortest mesh-edge path from {@code startVertexId} to the cut graph
     * as cut, keeping {@code cutDegree} in sync. No-op if {@code startVertexId}
     * cannot reach the cut (disconnected mesh) — that chart's origin simply floats.
     * {@code skipFirstEdge} (an active-edge index to exclude as the first hop, or
     * -1) lets a caller force a branch away from an edge that is already on the
     * cut.
     * 
     * @param startVertexId the vertex id to start from
     * @param cutDegree     the cut degree array
     * @param skipFirstEdge the edge to skip as the first hop
     */
    private void connectVertexToCut(int startVertexId, int[] cutDegree, int skipFirstEdge) {
        int[] pathEdges = shortestMeshPathToCut(startVertexId, cutDegree, skipFirstEdge);
        if (pathEdges == null)
            return;
        for (int activeEdge : pathEdges) {
            if (isCutEdge[activeEdge])
                continue;
            isCutEdge[activeEdge] = true;
            int halfEdge = mesh.edgeHalfEdge(mesh.edgeIdAt(activeEdge));
            cutDegree[activeVertexIndex(mesh.halfEdgeVertex(halfEdge))]++;
            cutDegree[activeVertexIndex(mesh.halfEdgeEndVertex(halfEdge))]++;
        }
    }

    /**
     * Dijkstra over mesh edges weighted by Euclidean length, from
     * {@code startVertexId} until any vertex already on the cut is reached. Returns
     * the active-edge indices along that shortest path, or {@code null} if no
     * on-cut vertex is reachable. {@code skipFirstEdge} (or -1) may not be used as
     * the first hop out of {@code startVertexId}. Implementation note: the priority
     * queue holds {@code long[]} pairs of {bit-cast distance, active vertex}.
     * 
     * @param startVertexId the vertex id to start from
     * @param cutDegree     the cut degree array
     * @param skipFirstEdge the edge to skip as the first hop
     * @return the active-edge indices along the shortest path
     */
    private int[] shortestMeshPathToCut(int startVertexId, int[] cutDegree, int skipFirstEdge) {
        int vertexCount = mesh.vertexCount();
        double[] distance = new double[vertexCount];
        int[] prevVertex = new int[vertexCount];
        int[] prevEdge = new int[vertexCount];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        Arrays.fill(prevVertex, -1);
        Arrays.fill(prevEdge, -1);
        int startActiveVertex = activeVertexIndex(startVertexId);
        distance[startActiveVertex] = 0.0;

        PriorityQueue<long[]> frontier = new PriorityQueue<>((a, b) -> Double.compare(
                Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0])));
        frontier.add(new long[] { Double.doubleToLongBits(0.0), startActiveVertex });

        Vector3f posHere = new Vector3f();
        Vector3f posOther = new Vector3f();

        int reachedActiveVertex = -1;
        while (!frontier.isEmpty()) {
            long[] top = frontier.poll();
            double distHere = Double.longBitsToDouble(top[0]);
            int activeVertex = (int) top[1];
            if (distHere > distance[activeVertex])
                continue;
            int vertexId = mesh.vertexIdAt(activeVertex);
            if (cutDegree[activeVertex] > 0 && activeVertex != startActiveVertex) {
                reachedActiveVertex = activeVertex;
                break;
            }
            mesh.vertexPosition(vertexId, posHere);
            int incidentEdgeCount = mesh.vertexEdgeCount(vertexId);
            for (int i = 0; i < incidentEdgeCount; i++) {
                int edgeId = mesh.vertexEdgeAt(vertexId, i);
                int activeEdge = crossField.edgeIdToActive.get(edgeId);
                if (activeVertex == startActiveVertex && activeEdge == skipFirstEdge)
                    continue;
                int halfEdge = mesh.edgeHalfEdge(edgeId);
                int otherVertexId = (mesh.halfEdgeVertex(halfEdge) == vertexId)
                        ? mesh.halfEdgeEndVertex(halfEdge)
                        : mesh.halfEdgeVertex(halfEdge);
                int otherActiveVertex = activeVertexIndex(otherVertexId);
                mesh.vertexPosition(otherVertexId, posOther);
                double newDistance = distHere + posHere.distance(posOther);
                if (newDistance < distance[otherActiveVertex]) {
                    distance[otherActiveVertex] = newDistance;
                    prevVertex[otherActiveVertex] = activeVertex;
                    prevEdge[otherActiveVertex] = activeEdge;
                    frontier.add(new long[] { Double.doubleToLongBits(newDistance), otherActiveVertex });
                }
            }
        }

        if (reachedActiveVertex < 0)
            return null;

        int pathLength = 0;
        for (int v = reachedActiveVertex; v != startActiveVertex; v = prevVertex[v]) {
            pathLength++;
        }
        int[] pathEdges = new int[pathLength];
        for (int v = reachedActiveVertex, i = 0; v != startActiveVertex; v = prevVertex[v]) {
            pathEdges[i++] = prevEdge[v];
        }
        return pathEdges;
    }

    /**
     * Assign each active face a branch label g_f ∈ {0..3} by BFS over non-cut
     * interior edges, seeding each connected component at 0.
     *
     * <p>
     * BZK09 §5 convention: the cross-field smoothness energy is (θ_A + κ_AB +
     * (π/2)·p_AB − θ_B)² with {@code edgeHalfEdge} oriented A→B, so a (u, v) basis
     * aligned with g_f stays continuous across a non-cut edge when g_B = (g_A −
     * p_AB) mod 4 in that canonical direction; traversing B→A flips the sign of the
     * period jump.
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
                for (int corner = 0; corner < SeamlessParameterization.CORNERS_PER_FACE; corner++) {
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
                    int branchMask = SeamlessParameterization.BRANCH_COUNT - 1;
                    faceBranch[otherActiveFace] = (activeFaceA == activeFace)
                            ? (faceBranch[activeFace] - periodJump) & branchMask
                            : (faceBranch[activeFace] + periodJump) & branchMask;
                    faceQueue.add(otherActiveFace);
                }
            }
        }
    }

    /**
     * Compute the seam rotation r_e ∈ {0..3} for every edge: 0 on non-cut and
     * boundary edges, and (g_B − g_A + p_AB) mod 4 on an interior cut edge.
     *
     * <p>
     * In B's frame the discrepancy between A's and B's chosen u-axes is (θ_B +
     * g_B·π/2) − (θ_A + g_A·π/2 + κ_AB) = (g_B − g_A + p_AB)·π/2 by the cross-field
     * smoothness relation, hence r_e = (g_B − g_A + p_AB) mod 4. On non-cut edges
     * {@link #propagateBranches()} chose g_B = g_A − p, making this 0.
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
            cutRotation[activeEdge] = (faceBranch[activeFaceB] - faceBranch[activeFaceA] + periodJump)
                    & (SeamlessParameterization.BRANCH_COUNT - 1);
        }
    }

    /**
     * Identify chart vertices: union the two corners on each endpoint of every
     * non-cut interior edge (so corners that map to the same point in the unfolded
     * chart merge), then compact the union-find roots to a dense
     * {@code [0, chartVertexCount)} numbering in {@link #cornerToChartVertex}.
     *
     * <p>
     * The half-edge in face B runs opposite to the one in face A across a shared
     * edge, so face A's corner {@code start} pairs with face B's corner
     * {@code start}, and face A's corner {@code start+1} with face B's corner
     * {@code start−1}.
     */
    private void buildChartVertices() {
        final int cornersPerFace = SeamlessParameterization.CORNERS_PER_FACE;
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
     * @throws IllegalStateException if {@code vertexId} is not a live mesh vertex
     * @param vertexId the vertex id to map
     * @return the dense active-vertex index
     */
    public int activeVertexIndex(int vertexId) {
        if (vertexActiveCache == null) {
            int vertexCount = mesh.vertexCount();
            vertexActiveCache = new HashMap<>(vertexCount * 2);
            for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++)
                vertexActiveCache.put(mesh.vertexIdAt(activeVertex), activeVertex);
        }
        Integer activeVertex = vertexActiveCache.get(vertexId);
        if (activeVertex == null)
            throw new IllegalStateException("unknown vertex id " + vertexId);
        return activeVertex;
    }
}
