package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * PATCH-35: Robins-Wood-Sheppard 2011 discrete-gradient field for 2D
 * triangulated piecewise-linear scalar fields. Implements the
 * lower-star homotopy-expansion pairing from <i>Theory and Algorithms
 * for Constructing Discrete Morse Complexes from Grayscale Digital
 * Images</i> (IEEE TPAMI 2011), adapted from the cubical formulation
 * to the simplicial 2-manifold case.
 *
 * <p>Output: a per-cell gradient pair pointer plus the list of critical
 * cells (those left unpaired). In 2D:
 * <ul>
 *   <li>Critical 0-cell (vertex) → local minimum (no lower neighbours).</li>
 *   <li>Critical 1-cell (edge)   → saddle.</li>
 *   <li>Critical 2-cell (triangle) → local maximum.</li>
 * </ul>
 *
 * <p>The discrete gradient gives stable, combinatorially-defined V-paths
 * (gradient flow lines) that replace the noisy steepest-walks used in
 * the previous {@code MorseSmaleComplex}/{@code MscCellAssembly} pipeline.
 * V-paths are used by {@code MscQuadCells} to assemble formal MSC quad
 * cells with clean, simply-connected boundaries.
 *
 * <p>Cell-id layout: vertices [0, nv), edges [nv, nv+ne), triangles
 * [nv+ne, nv+ne+nt).
 */
public final class DiscreteGradient {

    public record Result(
            int nv, int ne, int nt,
            /** For each cell id, the partner cell in its gradient pair, or -1
             *  if the cell is critical. */
            int[] pair,
            /** Cell dimension per id (0/1/2). */
            int[] dim,
            /** For each edge id (in [0, ne)), the 2 endpoint vertex ids. */
            int[][] edgeEndpoints,
            /** For each triangle id (in [0, nt)), the 3 vertex ids in input
             *  order. */
            int[][] triangleVerts,
            /** For each triangle id (in [0, nt)), the 3 edge ids of its sides
             *  (parallel to {@code triangleVerts}). */
            int[][] triangleEdges,
            /** Edge id by canonical-key {@code ((min<<32)|max)}. */
            Map<Long, Integer> edgeIdByKey,
            /** For each vertex id, list of incident triangle ids. */
            int[][] trianglesByVertex,
            /** For each vertex id, list of incident edge ids. */
            int[][] edgesByVertex,
            /** For each edge id, list of incident triangle ids (1 or 2). */
            int[][] trianglesByEdge
    ) {
        public int cellId(int dimension, int idx) {
            return switch (dimension) {
                case 0 -> idx;
                case 1 -> nv + idx;
                case 2 -> nv + ne + idx;
                default -> throw new IllegalArgumentException("dim " + dimension);
            };
        }

        public int dimOf(int cellId) {
            if (cellId < nv) return 0;
            if (cellId < nv + ne) return 1;
            return 2;
        }

        public int localIdx(int cellId) {
            if (cellId < nv) return cellId;
            if (cellId < nv + ne) return cellId - nv;
            return cellId - nv - ne;
        }

        public boolean isCritical(int cellId) {
            return pair[cellId] < 0;
        }

        public int[] criticalCells() {
            int n = pair.length;
            int count = 0;
            for (int i = 0; i < n; i++) if (pair[i] < 0) count++;
            int[] out = new int[count];
            int o = 0;
            for (int i = 0; i < n; i++) if (pair[i] < 0) out[o++] = i;
            return out;
        }
    }

    private DiscreteGradient() {}

    public static Result compute(ArrayMesh mesh, float[] scalar) {
        int[] faceIdx = mesh.copyFaceIndices();
        int faceCount = faceIdx.length / 3;
        int nv = mesh.vertexCount();

        // ----- Enumerate edges + build connectivity -----
        Map<Long, Integer> edgeIdByKey = new HashMap<>();
        List<int[]> edgeEndpointsList = new ArrayList<>();
        List<List<Integer>> trianglesByEdgeList = new ArrayList<>();
        int[][] triangleEdges = new int[faceCount][3];
        int[][] triangleVerts = new int[faceCount][3];

        for (int f = 0; f < faceCount; f++) {
            int a = faceIdx[f * 3];
            int b = faceIdx[f * 3 + 1];
            int c = faceIdx[f * 3 + 2];
            triangleVerts[f] = new int[]{a, b, c};
            int[] verts = {a, b, c};
            for (int e = 0; e < 3; e++) {
                int u = verts[e];
                int v = verts[(e + 1) % 3];
                long key = u < v
                        ? ((long) u << 32) | (v & 0xffffffffL)
                        : ((long) v << 32) | (u & 0xffffffffL);
                Integer eid = edgeIdByKey.get(key);
                if (eid == null) {
                    eid = edgeEndpointsList.size();
                    edgeIdByKey.put(key, eid);
                    edgeEndpointsList.add(new int[]{
                            (int)(key >>> 32),
                            (int)(key & 0xffffffffL)
                    });
                    trianglesByEdgeList.add(new ArrayList<>(2));
                }
                triangleEdges[f][e] = eid;
                trianglesByEdgeList.get(eid).add(f);
            }
        }
        int ne = edgeEndpointsList.size();
        int nt = faceCount;
        int[][] edgeEndpoints = edgeEndpointsList.toArray(new int[0][]);
        int[][] trianglesByEdge = new int[ne][];
        for (int i = 0; i < ne; i++) {
            List<Integer> list = trianglesByEdgeList.get(i);
            int[] arr = new int[list.size()];
            for (int j = 0; j < arr.length; j++) arr[j] = list.get(j);
            trianglesByEdge[i] = arr;
        }

        // Per-vertex: incident edges + incident triangles.
        int[] edgeCountPerV = new int[nv];
        for (int[] e : edgeEndpoints) { edgeCountPerV[e[0]]++; edgeCountPerV[e[1]]++; }
        int[][] edgesByVertex = new int[nv][];
        int[] eCursor = new int[nv];
        for (int i = 0; i < nv; i++) edgesByVertex[i] = new int[edgeCountPerV[i]];
        for (int eid = 0; eid < ne; eid++) {
            int[] e = edgeEndpoints[eid];
            edgesByVertex[e[0]][eCursor[e[0]]++] = eid;
            edgesByVertex[e[1]][eCursor[e[1]]++] = eid;
        }

        int[] triCountPerV = new int[nv];
        for (int[] t : triangleVerts) { triCountPerV[t[0]]++; triCountPerV[t[1]]++; triCountPerV[t[2]]++; }
        int[][] trianglesByVertex = new int[nv][];
        int[] tCursor = new int[nv];
        for (int i = 0; i < nv; i++) trianglesByVertex[i] = new int[triCountPerV[i]];
        for (int t = 0; t < nt; t++) {
            int[] tv = triangleVerts[t];
            trianglesByVertex[tv[0]][tCursor[tv[0]]++] = t;
            trianglesByVertex[tv[1]][tCursor[tv[1]]++] = t;
            trianglesByVertex[tv[2]][tCursor[tv[2]]++] = t;
        }

        int totalCells = nv + ne + nt;
        int[] pair = new int[totalCells];
        Arrays.fill(pair, -1);
        int[] dim = new int[totalCells];
        for (int i = 0; i < nv; i++) dim[i] = 0;
        for (int i = nv; i < nv + ne; i++) dim[i] = 1;
        for (int i = nv + ne; i < totalCells; i++) dim[i] = 2;

        // ----- Process each vertex's lower star -----
        // Stable scalar comparator (vertex idx tie-breaker).
        for (int v = 0; v < nv; v++) {
            processLowerStar(v, scalar, edgesByVertex, trianglesByVertex,
                    edgeEndpoints, triangleVerts, triangleEdges, trianglesByEdge,
                    nv, ne, pair);
        }

        return new Result(nv, ne, nt, pair, dim, edgeEndpoints, triangleVerts,
                triangleEdges, edgeIdByKey, trianglesByVertex, edgesByVertex,
                trianglesByEdge);
    }

    /**
     * Robins-Wood-Sheppard process-lower-star step at vertex v. Pairs
     * cells in the lower star using homotopy expansion; unpaired cells
     * become critical.
     */
    private static void processLowerStar(int v, float[] scalar,
                                          int[][] edgesByVertex,
                                          int[][] trianglesByVertex,
                                          int[][] edgeEndpoints,
                                          int[][] triangleVerts,
                                          int[][] triangleEdges,
                                          int[][] trianglesByEdge,
                                          int nv, int ne, int[] pair) {
        // Lower-star edges: edges (v, u) with simplexLess(u, v) = true.
        List<Integer> lowerEdges = new ArrayList<>();
        for (int eid : edgesByVertex[v]) {
            int u = edgeEndpoints[eid][0] == v ? edgeEndpoints[eid][1] : edgeEndpoints[eid][0];
            if (vertexLess(u, v, scalar)) lowerEdges.add(eid);
        }
        if (lowerEdges.isEmpty()) {
            // v is a critical 0-cell (local minimum). Already pair[v] = -1.
            return;
        }

        // Lower-star triangles: triangles (v, u, w) with both u, w lower than v.
        List<Integer> lowerTris = new ArrayList<>();
        for (int tid : trianglesByVertex[v]) {
            int[] tv = triangleVerts[tid];
            int o1 = -1, o2 = -1;
            for (int k = 0; k < 3; k++) {
                if (tv[k] != v) {
                    if (o1 < 0) o1 = tv[k];
                    else o2 = tv[k];
                }
            }
            if (vertexLess(o1, v, scalar) && vertexLess(o2, v, scalar)) {
                lowerTris.add(tid);
            }
        }

        // Find delta = lowest-value edge in lower star (sort by simplex value).
        // Edge value = the OTHER endpoint's scalar (since v is the high one).
        Integer deltaEid = null;
        float deltaOtherVal = Float.POSITIVE_INFINITY;
        int deltaOtherV = -1;
        for (int eid : lowerEdges) {
            int u = edgeEndpoints[eid][0] == v ? edgeEndpoints[eid][1] : edgeEndpoints[eid][0];
            float fu = scalar[u];
            if (deltaEid == null
                    || fu < deltaOtherVal
                    || (fu == deltaOtherVal && u < deltaOtherV)) {
                deltaEid = eid;
                deltaOtherVal = fu;
                deltaOtherV = u;
            }
        }
        // Pair v ↔ delta.
        int deltaCellId = nv + deltaEid;
        pair[v] = deltaCellId;
        pair[deltaCellId] = v;

        // PQone: lower-star cells with unpaired-face count == 1 (saddle candidates).
        // PQzero: lower-star cells with unpaired-face count == 0 (always pair-candidates).
        // Both ordered by simplex-value ascending so we process the lowest first.
        // We use lambda priority queues based on ascending lex value.
        PriorityQueue<Integer> pqZero = new PriorityQueue<>(
                (x, y) -> compareSimplexValue(x, y, scalar, edgeEndpoints, triangleVerts, nv));
        PriorityQueue<Integer> pqOne = new PriorityQueue<>(
                (x, y) -> compareSimplexValue(x, y, scalar, edgeEndpoints, triangleVerts, nv));

        // Add cofacets (triangles) of delta in L(v) to PQzero/PQone classification.
        for (int tid : lowerTris) {
            if (containsEdge(triangleEdges[tid], deltaEid)) {
                int unpaired = countUnpairedFacesInLowerStar(tid, v, scalar, triangleEdges,
                        edgeEndpoints, pair, nv);
                if (unpaired == 1) pqZero.add(nv + ne + tid);
            }
        }

        // Add other lower-star edges (not delta) to PQone.
        for (int eid : lowerEdges) {
            if (eid == deltaEid) continue;
            pqOne.add(nv + eid);
        }

        Set<Integer> processedTris = new HashSet<>();
        Set<Integer> processedEdges = new HashSet<>();
        processedEdges.add(deltaEid);

        while (!pqZero.isEmpty() || !pqOne.isEmpty()) {
            while (!pqZero.isEmpty()) {
                int alpha = pqZero.poll();
                if (pair[alpha] >= 0) continue;
                int alphaDim = alpha < nv ? 0 : (alpha < nv + ne ? 1 : 2);
                if (alphaDim == 1) continue; // shouldn't happen for edges in PQzero usually
                // alphaDim == 2 (triangle). Find the unique unpaired face (edge) in L(v).
                int tid = alpha - nv - ne;
                int freeEdgeId = findFreeFaceInLowerStar(tid, v, scalar, triangleEdges,
                        edgeEndpoints, pair, nv);
                if (freeEdgeId < 0) {
                    // No free face — leave on stack; will become critical if never paired.
                    continue;
                }
                int freeEdgeCell = nv + freeEdgeId;
                pair[alpha] = freeEdgeCell;
                pair[freeEdgeCell] = alpha;
                processedTris.add(tid);
                processedEdges.add(freeEdgeId);
                // Cofacets of freeEdge in L(v) may now have unpaired-count==1.
                for (int otherTid : lowerTris) {
                    if (otherTid == tid || processedTris.contains(otherTid)) continue;
                    if (!containsEdge(triangleEdges[otherTid], freeEdgeId)) continue;
                    int unpaired = countUnpairedFacesInLowerStar(otherTid, v, scalar,
                            triangleEdges, edgeEndpoints, pair, nv);
                    if (unpaired == 1) pqZero.add(nv + ne + otherTid);
                }
            }
            // Pop from PQone — these become critical (saddles or remaining lower-star cells).
            if (!pqOne.isEmpty()) {
                int gamma = pqOne.poll();
                if (pair[gamma] >= 0) continue;
                // Mark critical (already pair[gamma] = -1).
                int gammaDim = gamma < nv ? 0 : (gamma < nv + ne ? 1 : 2);
                if (gammaDim == 1) {
                    int eid = gamma - nv;
                    processedEdges.add(eid);
                    // Add cofacets that may now have unpaired-count == 1.
                    for (int tid : lowerTris) {
                        if (processedTris.contains(tid)) continue;
                        if (!containsEdge(triangleEdges[tid], eid)) continue;
                        int unpaired = countUnpairedFacesInLowerStar(tid, v, scalar,
                                triangleEdges, edgeEndpoints, pair, nv);
                        if (unpaired == 1) pqZero.add(nv + ne + tid);
                    }
                }
            }
        }
        // Any unprocessed lower-star triangles whose all faces are critical
        // become critical themselves (maxima).
        for (int tid : lowerTris) {
            if (processedTris.contains(tid)) continue;
            if (pair[nv + ne + tid] < 0) {
                // already critical (no pair). Nothing to do; counts as max.
            }
        }
    }

    private static boolean vertexLess(int u, int v, float[] scalar) {
        if (scalar[u] != scalar[v]) return scalar[u] < scalar[v];
        return u < v;  // simulation-of-simplicity tie-break by index
    }

    private static boolean containsEdge(int[] triEdges, int eid) {
        return triEdges[0] == eid || triEdges[1] == eid || triEdges[2] == eid;
    }

    /** Count faces (= edges) of {@code tid} that are in the lower star of {@code v}
     *  AND are not yet paired. */
    private static int countUnpairedFacesInLowerStar(int tid, int v, float[] scalar,
                                                       int[][] triangleEdges,
                                                       int[][] edgeEndpoints,
                                                       int[] pair, int nv) {
        int count = 0;
        for (int eid : triangleEdges[tid]) {
            int[] ep = edgeEndpoints[eid];
            // Only edges in lower star of v contain v AND have other endpoint < v.
            if (ep[0] != v && ep[1] != v) continue;
            int u = ep[0] == v ? ep[1] : ep[0];
            if (!vertexLess(u, v, scalar)) continue;
            if (pair[nv + eid] < 0) count++;
        }
        return count;
    }

    /** Return the edge-id of the unique unpaired face of {@code tid} in L(v),
     *  or -1 if none/multiple. */
    private static int findFreeFaceInLowerStar(int tid, int v, float[] scalar,
                                                 int[][] triangleEdges,
                                                 int[][] edgeEndpoints,
                                                 int[] pair, int nv) {
        int found = -1;
        for (int eid : triangleEdges[tid]) {
            int[] ep = edgeEndpoints[eid];
            if (ep[0] != v && ep[1] != v) continue;
            int u = ep[0] == v ? ep[1] : ep[0];
            if (!vertexLess(u, v, scalar)) continue;
            if (pair[nv + eid] >= 0) continue;
            if (found >= 0) return -1;
            found = eid;
        }
        return found;
    }

    /** Lex-compare two cell ids by their simplex value (vertex f-tuple sorted desc). */
    private static int compareSimplexValue(int a, int b, float[] scalar,
                                             int[][] edgeEndpoints, int[][] triangleVerts,
                                             int nv) {
        float[] va = simplexValueTuple(a, scalar, edgeEndpoints, triangleVerts, nv);
        float[] vb = simplexValueTuple(b, scalar, edgeEndpoints, triangleVerts, nv);
        int n = Math.min(va.length, vb.length);
        for (int i = 0; i < n; i++) {
            if (va[i] != vb[i]) return Float.compare(va[i], vb[i]);
        }
        return Integer.compare(va.length, vb.length);
    }

    private static float[] simplexValueTuple(int cellId, float[] scalar,
                                               int[][] edgeEndpoints, int[][] triangleVerts,
                                               int nv) {
        if (cellId < nv) return new float[]{ scalar[cellId] };
        int eid = cellId - nv;
        if (cellId < nv + edgeEndpoints.length) {
            int[] e = edgeEndpoints[eid];
            float a = scalar[e[0]], b = scalar[e[1]];
            return new float[]{ Math.max(a, b), Math.min(a, b) };
        }
        int tid = cellId - nv - edgeEndpoints.length;
        int[] t = triangleVerts[tid];
        float[] fs = new float[]{ scalar[t[0]], scalar[t[1]], scalar[t[2]] };
        Arrays.sort(fs);
        return new float[]{ fs[2], fs[1], fs[0] };
    }
}
