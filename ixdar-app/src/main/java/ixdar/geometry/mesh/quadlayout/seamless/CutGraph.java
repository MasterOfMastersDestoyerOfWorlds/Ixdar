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

public class CutGraph {
    /** True iff active edge {@code ae} is a cut edge or a mesh boundary edge. */
    public boolean[] isCutEdge;
    /**
     * Cut transition rotation r<sub>e</sub> ∈ {0,1,2,3}; valid only where
     * {@link #isCutEdge}.
     */
    public int[] cutRotation;


    public final HalfEdgeMesh mesh;
    public final CrossField crossField;

    int interiorCutEdgeCount;

    /** active-edge → dense index in [0, interiorCutEdgeCount), -1 otherwise */
    int[] cutEdgeDenseIdx;

    private SeamlessParameterization seamless;

    int chartVertexCount;

    /** length 3*F (active-face indexed) */
    int[] cornerToChartVertex;

    /** active-face → branch g_f ∈ {0..3} */
    int[] faceBranch;

    /** mesh-vertex-id → active-vertex-index, lazily built. */
    private HashMap<Integer, Integer> vertexActiveCache;

    public CutGraph(HalfEdgeMesh mesh, CrossField crossField, SeamlessParameterization seamlessParameterization) {
        this.mesh = mesh;
        this.crossField = crossField;
        this.seamless = seamlessParameterization;
    }

    public void buildCutGraph() {
        selectCutEdges();
        propagateBranches();
        buildCutRotation();
        buildChartVertices();
        buildDenseIndices();
    }

    private void selectCutEdges() {
        initialCutFromDualSpanningTree();

        int[] cutDegree = computeCutDegree();

        trimDanglingBranches(cutDegree);

        connectDetachedSingularities(cutDegree);
        // For Lyon's seamless input (NOT integer-grid), every singularity must
        // have cut-degree ≥ 2 — otherwise the surrounding face fan stays
        // connected as one chart vertex and the cross-field's ±π/2 rotation
        // around the singularity has no transition to absorb. (BZK09's full
        // pipeline avoids this by integer-pinning singularity (u, v); we
        // skip that step when {@link #integerGridMap} is off.)
        if (!seamless.integerGridMap) {
            for (Singularity s : crossField.singularities) {
                int sVid = s.vertexId();
                if (mesh.isBoundaryVertex(sVid))
                    continue;
                int va = activeVertexIndex(sVid);
                if (cutDegree[va] >= 2)
                    continue;
                extendSingularityToDegreeTwo(sVid, cutDegree);
            }
        }
    }


    private void initialCutFromDualSpanningTree() {
        isCutEdge = new boolean[seamless.edgeCount];

        // Mark all edges as initially cut. Dual spanning tree will UN-cut its tree
        // edges.
        Arrays.fill(isCutEdge, true);

        // Dual spanning tree: BFS over faces via interior, two-sided edges.
        boolean[] faceVisited = new boolean[seamless.faceCount];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        // Seed at active face 0.
        if (seamless.faceCount > 0) {
            faceVisited[0] = true;
            queue.add(0);
        }
        while (!queue.isEmpty()) {
            int afA = queue.poll();
            int faceAId = mesh.faceIdAt(afA);
            for (int c = 0; c < SeamlessParameterization.CORNERS_PER_FACE; c++) {
                int eId = mesh.faceEdgeAt(faceAId, c);
                int ae = crossField.edgeIdToActive.get(eId);
                int afOther = (seamless.edgeFaceA[ae] == afA) ? seamless.edgeFaceB[ae] : seamless.edgeFaceA[ae];
                if (afOther < 0)
                    continue; // boundary side — leave isCutEdge true
                if (faceVisited[afOther])
                    continue;
                faceVisited[afOther] = true;
                isCutEdge[ae] = false; // dual-tree edge → not cut
                queue.add(afOther);
            }
        }
    }

    private int[] computeCutDegree() {
        // For trim we need vertex → set of incident cut edges. Build counts only.
        int vertexCount = mesh.vertexCount();
        int[] cutDegree = new int[vertexCount];
        Arrays.fill(cutDegree, 0);
        for (int ae = 0; ae < seamless.edgeCount; ae++) {
            if (!isCutEdge[ae])
                continue;
            int eId = mesh.edgeIdAt(ae);
            int hCanon = mesh.edgeHalfEdge(eId);
            int va0 = activeVertexIndex(mesh.halfEdgeVertex(hCanon));
            int va1 = activeVertexIndex(mesh.halfEdgeEndVertex(hCanon));
            cutDegree[va0]++;
            cutDegree[va1]++;
        }
        return cutDegree;
    }


    private void trimDanglingBranches(int[] cutDegree) {
        // Trim dangling paths: a non-singularity vertex with exactly one cut edge.
        // Repeat until stable.
        Set<Integer> singularityVerts = new HashSet<>();
        for (Singularity s : crossField.singularities)
            singularityVerts.add(s.vertexId());

        ArrayDeque<Integer> trimQueue = new ArrayDeque<>();
        for (int va = 0; va < mesh.vertexCount(); va++) {
            int vId = mesh.vertexIdAt(va);
            if (cutDegree[va] == 1 && !singularityVerts.contains(vId) && !mesh.isBoundaryVertex(vId)) {
                trimQueue.add(va);
            }
        }
        while (!trimQueue.isEmpty()) {
            int va = trimQueue.poll();
            int vId = mesh.vertexIdAt(va);
            if (cutDegree[va] != 1)
                continue;
            if (singularityVerts.contains(vId) || mesh.isBoundaryVertex(vId))
                continue;
            // Find the one cut edge incident to vId and remove it.
            int incidentEdgeCount = mesh.vertexEdgeCount(vId);
            for (int i = 0; i < incidentEdgeCount; i++) {
                int eId = mesh.vertexEdgeAt(vId, i);
                int ae = crossField.edgeIdToActive.get(eId);
                if (!isCutEdge[ae])
                    continue;
                if (mesh.isBoundaryEdge(eId))
                    continue; // keep boundaries cut
                // Mark edge non-cut and decrement both endpoints' degrees.
                isCutEdge[ae] = false;
                cutDegree[va]--;
                int hCanon = mesh.edgeHalfEdge(eId);
                int otherVid = (mesh.halfEdgeVertex(hCanon) == vId)
                        ? mesh.halfEdgeEndVertex(hCanon)
                        : mesh.halfEdgeVertex(hCanon);
                int otherVa = activeVertexIndex(otherVid);
                cutDegree[otherVa]--;
                int otherVidFinal = otherVid;
                if (cutDegree[otherVa] == 1
                        && !singularityVerts.contains(otherVidFinal)
                        && !mesh.isBoundaryVertex(otherVidFinal)) {
                    trimQueue.add(otherVa);
                }
                break;
            }
        }
    }

    private void connectDetachedSingularities(int[] cutDegree) {
        // BZK09 §5: Connect interior singularities not yet on the cut: Dijkstra
        // (primal) to nearest cut vertex.
        for (Singularity s : crossField.singularities) {
            int sVid = s.vertexId();
            if (cutDegree[activeVertexIndex(sVid)] > 0 || mesh.isBoundaryVertex(sVid))
                continue;
            connectVertexToCut(sVid, cutDegree);
        }
    }



    // =====================================================================
    // C2. branch propagation (BFS over non-cut interior edges)
    // =====================================================================

    private void propagateBranches() {
        faceBranch = new int[seamless.faceCount];
        Arrays.fill(faceBranch, -1);

        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int seed = 0; seed < seamless.faceCount; seed++) {
            if (faceBranch[seed] != -1)
                continue;
            faceBranch[seed] = 0;
            queue.add(seed);
            while (!queue.isEmpty()) {
                int af = queue.poll();
                int fId = mesh.faceIdAt(af);
                for (int c = 0; c < SeamlessParameterization.CORNERS_PER_FACE; c++) {
                    int eId = mesh.faceEdgeAt(fId, c);
                    int ae = crossField.edgeIdToActive.get(eId);
                    if (isCutEdge[ae])
                        continue;
                    int afA = seamless.edgeFaceA[ae];
                    int afB = seamless.edgeFaceB[ae];
                    if (afA < 0 || afB < 0)
                        continue; // boundary
                    int afOther = (afA == af) ? afB : afA;
                    if (faceBranch[afOther] != -1)
                        continue;
                    int p = crossField.periodJump[ae];
                    // BZK09 §5 convention: cross-field smoothness energy is
                    // (θ_A + κ_AB + (π/2)·p_AB − θ_B)² with edgeHalfEdge oriented A→B,
                    // so for a (u,v) basis aligned with branch g_f the continuity
                    // requirement gives g_B = (g_A − p_AB) mod 4 in the canonical
                    // direction; the reverse traversal (B→A) flips that sign.
                    int newBranch;
                    if (afA == af) {
                        newBranch = (faceBranch[af] - p) & (SeamlessParameterization.BRANCH_COUNT - 1);
                    } else {
                        newBranch = (faceBranch[af] + p) & (SeamlessParameterization.BRANCH_COUNT - 1);
                    }
                    faceBranch[afOther] = newBranch;
                    queue.add(afOther);
                }
            }
        }
    }


    public void buildCutRotation() {
        cutRotation = new int[seamless.edgeCount];
        for (int ae1 = 0; ae1 < seamless.edgeCount; ae1++) {
            if (!isCutEdge[ae1]) {
                cutRotation[ae1] = 0;
                continue;
            }
            int afA = seamless.edgeFaceA[ae1];
            int afB = seamless.edgeFaceB[ae1];
            if (afA < 0 || afB < 0) {
                cutRotation[ae1] = 0; // boundary — no transition
                continue;
            }
            // Discrepancy (in B's frame) of A's chosen u-axis vs B's chosen u-axis is
            // (θ_B + g_B·π/2) − (θ_A + g_A·π/2 + κ_AB)
            // = (g_B − g_A)·π/2 + (θ_B − θ_A − κ_AB)
            // = (g_B − g_A + p_AB)·π/2 [from BZK09 cross-field smoothness]
            // so r_e = (g_B − g_A + p_AB) mod 4. For non-cut edges this is 0 by
            // construction (BFS propagated branches with g_B = g_A − p).
            int p = crossField.periodJump[ae1];
            cutRotation[ae1] = (faceBranch[afB] - faceBranch[afA] + p)
                    & (SeamlessParameterization.BRANCH_COUNT - 1);
        }
    }

    // =====================================================================
    // C3. chart vertices via union-find on corners
    // =====================================================================

    private void buildChartVertices() {
        int totalCorners = seamless.faceCount * SeamlessParameterization.CORNERS_PER_FACE;
        int[] parent = new int[totalCorners];
        int[] rank = new int[totalCorners];
        for (int i = 0; i < totalCorners; i++)
            parent[i] = i;

        // For each non-cut interior edge, merge the two corners on each endpoint.
        for (int ae = 0; ae < seamless.edgeCount; ae++) {
            if (isCutEdge[ae])
                continue;
            int afA = seamless.edgeFaceA[ae];
            int afB = seamless.edgeFaceB[ae];
            if (afA < 0 || afB < 0)
                continue;
            int cAStart = seamless.edgeCornerInA[ae];
            int cBStart = seamless.edgeCornerInB[ae];
            // Edge endpoint at "start" vertex: corners cAStart in A, cBStart in B.
            // Edge endpoint at "enpropagateBranches()d" vertex: corners (cAStart+1)%3 in A,
            // (cBStart-1+3)%3 in
            // B
            // (because the half-edge in B goes the OTHER direction across this edge).
            int cAEnd = (cAStart + 1) % SeamlessParameterization.CORNERS_PER_FACE;
            int cBEnd = (cBStart + SeamlessParameterization.CORNERS_PER_FACE - 1)
                    % SeamlessParameterization.CORNERS_PER_FACE;
            unionCorners(parent, rank, afA * SeamlessParameterization.CORNERS_PER_FACE + cAStart,
                    afB * SeamlessParameterization.CORNERS_PER_FACE + cBStart);
            unionCorners(parent, rank, afA * SeamlessParameterization.CORNERS_PER_FACE + cAEnd,
                    afB * SeamlessParameterization.CORNERS_PER_FACE + cBEnd);
        }

        // Compact roots to dense [0, chartVertexCount).
        cornerToChartVertex = new int[totalCorners];
        HashMap<Integer, Integer> rootToCv = new HashMap<>();
        for (int i = 0; i < totalCorners; i++) {
            int r = findCorner(parent, i);
            Integer cv = rootToCv.get(r);
            if (cv == null) {
                cv = rootToCv.size();
                rootToCv.put(r, cv);
            }
            cornerToChartVertex[i] = cv;
        }
        chartVertexCount = rootToCv.size();
    }

    private static int findCorner(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static void unionCorners(int[] parent, int[] rank, int a, int b) {
        int ra = findCorner(parent, a), rb = findCorner(parent, b);
        if (ra == rb)
            return;
        if (rank[ra] < rank[rb]) {
            parent[ra] = rb;
        } else if (rank[ra] > rank[rb]) {
            parent[rb] = ra;
        } else {
            parent[rb] = ra;
            rank[ra]++;
        }
    }

    /**
     * Add one more cut edge incident to a degree-1 singularity to push its
     * cut-degree to 2. Picks the shortest Dijkstra path from {@code sVid} to the
     * existing cut graph that does not back-track over the existing incoming cut
     * edge.
     */
    private void extendSingularityToDegreeTwo(int sVid, int[] cutDegree) {
        int existingCutEdge = -1;
        int incident = mesh.vertexEdgeCount(sVid);
        for (int i = 0; i < incident; i++) {
            int eId = mesh.vertexEdgeAt(sVid, i);
            int ae = crossField.edgeIdToActive.get(eId);
            if (isCutEdge[ae] && !mesh.isBoundaryEdge(eId)) {
                existingCutEdge = ae;
                break;
            }
        }

        int n = mesh.vertexCount();
        double[] dist = new double[n];
        int[] prev = new int[n];
        int[] prevEdge = new int[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        Arrays.fill(prevEdge, -1);
        int startVa = activeVertexIndex(sVid);
        dist[startVa] = 0.0;

        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Double.compare(
                Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0])));
        pq.add(new long[] { Double.doubleToLongBits(0.0), startVa });

        Vector3f pa = new Vector3f();
        Vector3f pb = new Vector3f();

        int hitVa = -1;
        while (!pq.isEmpty()) {
            long[] entry = pq.poll();
            double d = Double.longBitsToDouble(entry[0]);
            int va = (int) entry[1];
            if (d > dist[va])
                continue;
            int vId = mesh.vertexIdAt(va);
            if (cutDegree[va] > 0 && va != startVa) {
                hitVa = va;
                break;
            }
            int incidentEdges = mesh.vertexEdgeCount(vId);
            mesh.vertexPosition(vId, pa);
            for (int i = 0; i < incidentEdges; i++) {
                int eId = mesh.vertexEdgeAt(vId, i);
                int ae = crossField.edgeIdToActive.get(eId);
                if (va == startVa && ae == existingCutEdge)
                    continue;
                int hCanon = mesh.edgeHalfEdge(eId);
                int otherVid = (mesh.halfEdgeVertex(hCanon) == vId)
                        ? mesh.halfEdgeEndVertex(hCanon)
                        : mesh.halfEdgeVertex(hCanon);
                int otherVa = activeVertexIndex(otherVid);
                mesh.vertexPosition(otherVid, pb);
                double w = pa.distance(pb);
                double nd = d + w;
                if (nd < dist[otherVa]) {
                    dist[otherVa] = nd;
                    prev[otherVa] = va;
                    prevEdge[otherVa] = ae;
                    pq.add(new long[] { Double.doubleToLongBits(nd), otherVa });
                }
            }
        }

        if (hitVa < 0)
            return;

        for (int va = hitVa; va != startVa; va = prev[va]) {
            int ae = prevEdge[va];
            if (!isCutEdge[ae]) {
                isCutEdge[ae] = true;
                int eId = mesh.edgeIdAt(ae);
                int hCanon = mesh.edgeHalfEdge(eId);
                int va0 = activeVertexIndex(mesh.halfEdgeVertex(hCanon));
                int va1 = activeVertexIndex(mesh.halfEdgeEndVertex(hCanon));
                cutDegree[va0]++;
                cutDegree[va1]++;
            }
        }
    }

    /**
     * Adds a Dijkstra path of mesh edges from {@code startVid} to the nearest cut
     * vertex.
     */
    private void connectVertexToCut(int startVid, int[] cutDegree) {
        int n = mesh.vertexCount();
        double[] dist = new double[n];
        int[] prev = new int[n];
        int[] prevEdge = new int[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        Arrays.fill(prevEdge, -1);
        int startVa = activeVertexIndex(startVid);
        dist[startVa] = 0.0;

        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Double.compare(
                Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0])));
        pq.add(new long[] { Double.doubleToLongBits(0.0), startVa });

        Vector3f pa = new Vector3f();
        Vector3f pb = new Vector3f();

        int hitVa = -1;
        while (!pq.isEmpty()) {
            long[] entry = pq.poll();
            double d = Double.longBitsToDouble(entry[0]);
            int va = (int) entry[1];
            if (d > dist[va])
                continue;
            int vId = mesh.vertexIdAt(va);
            if (cutDegree[va] > 0 && va != startVa) {
                hitVa = va;
                break;
            }
            int incident = mesh.vertexEdgeCount(vId);
            mesh.vertexPosition(vId, pa);
            for (int i = 0; i < incident; i++) {
                int eId = mesh.vertexEdgeAt(vId, i);
                int hCanon = mesh.edgeHalfEdge(eId);
                int otherVid = (mesh.halfEdgeVertex(hCanon) == vId)
                        ? mesh.halfEdgeEndVertex(hCanon)
                        : mesh.halfEdgeVertex(hCanon);
                int otherVa = activeVertexIndex(otherVid);
                mesh.vertexPosition(otherVid, pb);
                double w = pa.distance(pb);
                double nd = d + w;
                if (nd < dist[otherVa]) {
                    dist[otherVa] = nd;
                    prev[otherVa] = va;
                    prevEdge[otherVa] = crossField.edgeIdToActive.get(eId);
                    pq.add(new long[] { Double.doubleToLongBits(nd), otherVa });
                }
            }
        }

        if (hitVa < 0) {
            // Singularity has no path to existing cut graph (degenerate / non-connected
            // mesh).
            // Bail out silently — the system will still solve, just with one unconstrained
            // chart whose origin floats.
            return;
        }
        for (int va = hitVa; va != startVa; va = prev[va]) {
            int ae = prevEdge[va];
            if (!isCutEdge[ae]) {
                isCutEdge[ae] = true;
                int eId = mesh.edgeIdAt(ae);
                int hCanon = mesh.edgeHalfEdge(eId);
                int va0 = activeVertexIndex(mesh.halfEdgeVertex(hCanon));
                int va1 = activeVertexIndex(mesh.halfEdgeEndVertex(hCanon));
                cutDegree[va0]++;
                cutDegree[va1]++;
            }
        }
    }

    public void buildDenseIndices() {
        // Dense-index every interior cut edge for the seam transition variables.
        cutEdgeDenseIdx = new int[seamless.edgeCount];
        Arrays.fill(cutEdgeDenseIdx, -1);
        int next = 0;
        for (int ae = 0; ae < seamless.edgeCount; ae++) {
            if (!isCutEdge[ae])
                continue;
            if (seamless.edgeFaceA[ae] < 0 || seamless.edgeFaceB[ae] < 0)
                continue; // boundary cut, no transition
            cutEdgeDenseIdx[ae] = next++;
        }
        interiorCutEdgeCount = next;
    }


    public int activeVertexIndex(int vId) {
        // ArrayMesh keeps active = id but HalfEdgeMesh may have holes. Linear scan is
        // OK
        // since we cache cutDegree by active index.
        // Iterate the active list once to build a map; cache lazily.
        if (vertexActiveCache == null) {
            int n = mesh.vertexCount();
            vertexActiveCache = new HashMap<>(n * 2);
            for (int va = 0; va < n; va++) {
                vertexActiveCache.put(mesh.vertexIdAt(va), va);
            }
        }
        Integer i = vertexActiveCache.get(vId);
        if (i == null)
            throw new IllegalStateException("unknown vertex id " + vId);
        return i;
    }
}