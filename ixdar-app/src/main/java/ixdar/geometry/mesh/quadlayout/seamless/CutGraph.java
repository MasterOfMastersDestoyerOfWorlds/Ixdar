package ixdar.geometry.mesh.quadlayout.seamless;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

    /** The seamless parameterization owning this cut graph. */
    public SeamlessParameterization seamless;

    /** Number of chart vertices. */
    public int chartVertexCount;

    /** Length 3*F, indexed by (activeFace * 3 + corner) → chart-vertex index. */
    public int[] cornerToChartVertex;

    /** Active-face index → branch g_f ∈ {0..3}. */
    public int[] faceBranch;

    /**
     * Per chart vertex: true iff <em>primary</em> (free DOF in the BZK09 §5
     * variable-elimination layout). A chart vertex is primary iff it sits on the
     * canonical {@code edgeFaceA} side of at least one cut edge, or it touches no
     * cut edge at all. False ⇒ secondary, with value defined by the per-cut-edge
     * substitution {@code u_c = R_{r_e} · u_partner + (s_e, t_e)} via
     * {@link #secondaryEdge} / {@link #secondaryPartner}.
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
     * {@code [activeEdge, chartA, chartB, endpoint]}; reduced exactly by
     * {@link SeamlessParameterization#reduceLeftoverConstraints}.
     */
    public int[][] leftoverConstraints;

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
        classifyChartVerticesForSubstitution();
    }

    /**
     * Choose the seam edge set: start from the complement of a min-cost dual
     * spanning tree biased to absorb {@link CrossField#alignmentEdgeIds} (so the
     * cut opens the surface into a disk while keeping feature edges non-cut), trim
     * the dead "whiskers" that leaves behind, then route every interior singularity
     * onto the cut. BZK09's full pipeline integer-pins singularity (u, v) once the
     * parametrization runs, so a single cut-degree is sufficient.
     */
    private void selectCutEdges() {
        initialCutFromDualSpanningTree();
        cutDegree = computeCutDegree();
        trimDanglingBranches(cutDegree);
        connectDetachedSingularities(cutDegree);
    }

    /**
     * Initialize the seam set to the complement of a min-cost dual spanning tree:
     * mark every edge cut, then run a Dijkstra-style traversal of the dual graph
     * where alignment edges have cost {@code 0} and all other interior edges have
     * cost {@code 1}. Tree edges (the parents in the resulting spanning forest) are
     * un-cut. Boundary edges stay cut.
     *
     * <p>
     * BZK09 §5.2 requires alignment edges to be non-cut: if a feature edge ended up
     * on the cut with rotation {@code r ≠ 0}, satisfying {@code v_p = v_q} on both
     * sides would collapse the edge to a point. Biasing the spanning tree to absorb
     * alignment edges keeps them non-cut whenever the surface topology allows.
     */
    private void initialCutFromDualSpanningTree() {
        isCutEdge = new boolean[seamless.edgeCount];
        Arrays.fill(isCutEdge, true);

        double[] distance = new double[seamless.faceCount];
        int[] parentEdge = new int[seamless.faceCount];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        Arrays.fill(parentEdge, -1);

        PriorityQueue<long[]> frontier = new PriorityQueue<>((a, b) -> Double.compare(
                Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0])));
        if (seamless.faceCount > 0) {
            distance[0] = 0.0;
            frontier.add(new long[] { Double.doubleToLongBits(0.0), 0 });
        }
        while (!frontier.isEmpty()) {
            long[] top = frontier.poll();
            double distHere = Double.longBitsToDouble(top[0]);
            int activeFace = (int) top[1];
            if (distHere > distance[activeFace]) {
                continue;
            }
            int faceId = mesh.faceIdAt(activeFace);
            for (int corner = 0; corner < SeamlessParameterization.CORNERS_PER_FACE; corner++) {
                int edgeId = mesh.faceEdgeAt(faceId, corner);
                int activeEdge = crossField.edgeIdToActive.get(edgeId);
                int otherActiveFace = (seamless.edgeFaceA[activeEdge] == activeFace)
                        ? seamless.edgeFaceB[activeEdge]
                        : seamless.edgeFaceA[activeEdge];
                if (otherActiveFace < 0) {
                    continue;
                }
                double edgeCost = crossField.alignmentEdgeIds.contains(edgeId) ? 0.0 : 1.0;
                double newDistance = distHere + edgeCost;
                if (newDistance < distance[otherActiveFace]) {
                    distance[otherActiveFace] = newDistance;
                    parentEdge[otherActiveFace] = activeEdge;
                    frontier.add(new long[] { Double.doubleToLongBits(newDistance), otherActiveFace });
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
                // BZK09 §5.2: routing a singularity through an alignment edge
                // would put that feature edge on the cut and force the
                // {@code v_p = v_q} constraint to collapse it to a point.
                // Hard-skipping would disconnect singularities sitting on a
                // feature crease, so penalise instead: alignment edges are
                // taken only when there is no non-alignment alternative.
                double edgeLength = posHere.distance(posOther);
                double edgeCost = crossField.alignmentEdgeIds.contains(edgeId)
                        ? edgeLength * ALIGNMENT_PATH_PENALTY
                        : edgeLength;
                double newDistance = distHere + edgeCost;
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

    /**
     * BZK09 §5 chart-vertex classification for variable elimination.
     *
     * <p>
     * A chart vertex is primary iff it appears on the canonical {@code edgeFaceA}
     * side of at least one cut edge, or it never touches a cut edge at all. Else it
     * is secondary — the first cut edge that has it on its {@code edgeFaceB} side
     * owns the substitution {@code u_c = R_{r_e} · u_partner + (s_e, t_e)}.
     * Subsequent cut edges that would also substitute the same chart vertex, and
     * any cut edge whose B-side chart vertex turns out to be primary (also on some
     * A-side), contribute a leftover record to be reduced exactly by
     * {@link SeamlessParameterization#reduceLeftoverConstraints}.
     */
    private void classifyChartVerticesForSubstitution() {
        chartVertexIsPrimary = new boolean[chartVertexCount];
        secondaryEdge = new int[chartVertexCount];
        secondaryPartner = new int[chartVertexCount];
        Arrays.fill(secondaryEdge, -1);
        Arrays.fill(secondaryPartner, -1);
        Arrays.fill(chartVertexIsPrimary, true);
        primaryChartIndex = new int[chartVertexCount];
        for (int cv = 0; cv < chartVertexCount; cv++) {
            primaryChartIndex[cv] = cv;
        }
        primaryChartCount = chartVertexCount;
        leftoverConstraints = new int[0][];
        return;
    }
}