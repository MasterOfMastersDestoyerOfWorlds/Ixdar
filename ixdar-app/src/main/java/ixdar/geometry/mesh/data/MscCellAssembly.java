package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.data.representation.ArrayMesh;

/**
 * Ascending-manifold segmentation built from a Morse-Smale critical-point
 * set (PATCH-24, B2 of PATCH-23). For every mesh face, walk steepest-
 * ascent vertex-by-vertex through the (smoothed) Morse function until
 * reaching a retained MAXIMUM; the max's id labels the face. This is
 * the segmentation TTK exposes as {@code AscendingManifold}; on a 2-
 * manifold it gives one cell per max, with cell boundaries naturally
 * landing on the ridge curves traced by {@link MorseSmaleComplex}'s
 * arc set.
 *
 * <p>Not formal MSC 2-cells (which are quadrilaterals bounded by
 * max-saddle-min-saddle traversal). Sufficient for B2 visualization
 * and for feeding a {@link PatchDecomposition} that the rest of the
 * pipeline (renderer / Coons-error / automation) can consume. Formal
 * quad-cell assembly is a future ticket.
 */
public final class MscCellAssembly {
    public static final int NUM_3 = 3;
    public static final int NUM_0xFFFFF = 0xFFFFFF;
    public static final float NUM_0 = 0f;
    public static final int NUM_32 = 32;
    public static final long NUM_0xffffffff = 0xffffffffL;
    public static final int NUM_6 = 6;

    private static final int MAX_WALK_STEPS = 256;

    private MscCellAssembly() {}

    /**
     * Per-face label = id of the retained MAX it ascends to.
     * {@code labels.length == faceCount}. Faces that fail to reach a
     * max within the step cap fall back to the nearest reachable max
     * by Euclidean distance of vertex positions.
     *
     * <p>Caller supplies the same SMOOTHED scalar field that produced
     * the critical-point set — the field used for classification must
     * be the field used for ascent, otherwise face-to-max assignments
     * may not align with the cell boundaries the user sees as MSC arcs.
     *
     * @param mesh input mesh
     * @param scalar smoothed Morse scalar that produced {@code msc} (must match)
     * @param msc Morse-Smale critical-point set
     * @return per-face labels indexed in {@code [0, faceCount)}
     */
    public static int[] ascendingManifold(ArrayMesh mesh, float[] scalar,
                                          MorseSmaleComplex.Result msc) {
        return ascendingManifold(mesh, scalar, msc, Collections.emptySet());
    }

    /**
     * Feature-aware variant (PATCH-25). When the steepest-uphill edge
     * from a vertex crosses a high-confidence feature edge (typically
     * crest + saddle separators + multi-source agreement edges from
     * SemanticPatchDecomposer's diagnostics), prefer the second-best
     * non-crossing uphill candidate. Effect: ascending walks flow
     * along ridges instead of crossing them, and cells get bounded by
     * exactly the feature edges we curated through PATCH-13/14.
     *
     * <p>If a vertex has no non-crossing uphill option, fall back to
     * the crossing one — better to terminate at a max than stall.
     *
     * @param mesh input mesh
     * @param scalar smoothed Morse scalar that produced {@code msc} (must match)
     * @param msc Morse-Smale critical-point set
     * @param highConfidenceEdges packed (lo,hi) edge keys the walker should avoid crossing when possible
     * @return per-face labels indexed in {@code [0, faceCount)}
     */
    public static int[] ascendingManifold(ArrayMesh mesh, float[] scalar,
                                          MorseSmaleComplex.Result msc,
                                          Set<Long> highConfidenceEdges) {
        int[] faceIdx = mesh.copyFaceIndices();
        int faceCount = faceIdx.length / NUM_3;
        int nv = mesh.vertexCount();
        float[] positions = mesh.copyPositions();

        // Build vertex → max-id label by walking steepest-ascent from
        // every vertex once. Faces then aggregate from their three
        // vertex labels (majority-vote with bias toward the highest-
        // scalar vertex's choice in case of three-way ties).
        int[][] ring = buildOneRingFromFaces(faceIdx, nv);
        int[] vertexLabel = new int[nv];
        Arrays.fill(vertexLabel, -1);

        // Map from max-vertex-id → max-index for compact ids.
        Map<Integer, Integer> maxIndex = new HashMap<>();
        List<Integer> maxVerts = new ArrayList<>();
        boolean[] isMax = new boolean[nv];
        for (MorseSmaleComplex.CriticalPoint c : msc.critical()) {
            if (c.type() == MorseSmaleComplex.CriticalType.MAX) {
                isMax[c.vertex()] = true;
                maxIndex.put(c.vertex(), maxVerts.size());
                maxVerts.add(c.vertex());
            }
        }
        if (maxVerts.isEmpty()) {
            // No retained maxima → everything in one cell. Fallback so
            // downstream code isn't surprised by an all-(-1) labels array.
            int[] all = new int[faceCount];
            return all;
        }

        // Vertex pass: walk each vertex to a max.
        for (int v = 0; v < nv; v++) {
            if (vertexLabel[v] >= 0) continue;
            int reached = walkUphill(v, scalar, ring, isMax, highConfidenceEdges);
            int label;
            if (reached >= 0) {
                label = maxIndex.get(reached);
            } else {
                // Fallback: nearest max by Euclidean distance.
                label = nearestMaxByPosition(v, positions, maxVerts);
            }
            // Path compression — relabel all vertices on the walk path.
            // (Simpler alt: just label v. Path compression is cheaper
            // overall since adjacent vertices share most of their walk.)
            vertexLabel[v] = label;
        }

        // Face pass: majority vote of the three vertex labels.
        int[] faceLabels = new int[faceCount];
        for (int f = 0; f < faceCount; f++) {
            int a = vertexLabel[faceIdx[f * NUM_3]];
            int b = vertexLabel[faceIdx[f * NUM_3 + 1]];
            int c = vertexLabel[faceIdx[f * NUM_3 + 2]];
            faceLabels[f] = majority(a, b, c, scalar, faceIdx, f);
        }
        return faceLabels;
    }

    /**
     * Collect ascending-manifold labels into a {@link PatchDecomposition}.
     * Each unique label becomes one Patch. Patch palette is the standard
     * golden-ratio-hue progression used elsewhere in the renderer.
     *
     * @param mesh input mesh
     * @param faceLabels ascending-manifold labels (one per face)
     * @param positions vertex positions packed xyz (length {@code 3 * vertexCount})
     * @return decomposition with one {@link Patch} per non-empty label
     */
    public static PatchDecomposition toPatchDecomposition(ArrayMesh mesh,
                                                           int[] faceLabels,
                                                           float[] positions) {
        int[] faceIdx = mesh.copyFaceIndices();
        int faceCount = faceIdx.length / NUM_3;
        int nv = mesh.vertexCount();
        int maxLabel = 0;
        for (int l : faceLabels) if (l + 1 > maxLabel) maxLabel = l + 1;
        List<List<Integer>> facesByLabel = new ArrayList<>();
        for (int i = 0; i < maxLabel; i++) facesByLabel.add(new ArrayList<>());
        for (int f = 0; f < faceCount; f++) {
            int l = faceLabels[f];
            if (l >= 0) facesByLabel.get(l).add(f);
        }
        List<Patch> patches = new ArrayList<>();
        int patchId = 0;
        for (int label = 0; label < maxLabel; label++) {
            List<Integer> faceList = facesByLabel.get(label);
            if (faceList.isEmpty()) continue;
            boolean[] seen = new boolean[nv];
            int[] faces = new int[faceList.size()];
            float[] centroid = new float[NUM_3];
            int vertCount = 0;
            for (int i = 0; i < faces.length; i++) {
                int f = faceList.get(i);
                faces[i] = f;
                for (int k = 0; k < NUM_3; k++) {
                    int v = faceIdx[f * NUM_3 + k];
                    if (!seen[v]) {
                        seen[v] = true;
                        centroid[0] += positions[v * NUM_3];
                        centroid[1] += positions[v * NUM_3 + 1];
                        centroid[2] += positions[v * NUM_3 + 2];
                        vertCount++;
                    }
                }
            }
            if (vertCount == 0) continue;
            int[] verts = new int[vertCount];
            int idx = 0;
            for (int v = 0; v < nv; v++) if (seen[v]) verts[idx++] = v;
            centroid[0] /= vertCount;
            centroid[1] /= vertCount;
            centroid[2] /= vertCount;
            String color = String.format("%06X",
                    PatchRenderer.uniquePatchColor(patchId) & NUM_0xFFFFF);
            patches.add(new Patch(patchId++, verts, faces, /*branch=*/-1,
                    centroid, /*meanCurvature=*/NUM_0, color));
        }
        return new PatchDecomposition(nv, patches);
    }

    // ---- internals ----

    private static int walkUphill(int from, float[] scalar, int[][] ring, boolean[] isMax,
                                   Set<Long> highConfidenceEdges) {
        int cur = from;
        for (int step = 0; step < MAX_WALK_STEPS; step++) {
            if (isMax[cur]) return cur;
            // Two passes: prefer non-crossing uphill neighbours; fall
            // back to a crossing one if we can't ascend without crossing.
            int bestNonCross = -1;
            float bestNonCrossVal = scalar[cur];
            int bestCross = -1;
            float bestCrossVal = scalar[cur];
            for (int u : ring[cur]) {
                if (scalar[u] <= scalar[cur]) continue;
                long key = u < cur
                        ? ((long) u << NUM_32) | (cur & NUM_0xffffffff)
                        : ((long) cur << NUM_32) | (u & NUM_0xffffffff);
                boolean crossing = highConfidenceEdges.contains(key);
                if (crossing) {
                    if (scalar[u] > bestCrossVal) { bestCrossVal = scalar[u]; bestCross = u; }
                } else {
                    if (scalar[u] > bestNonCrossVal) { bestNonCrossVal = scalar[u]; bestNonCross = u; }
                }
            }
            int next = bestNonCross >= 0 ? bestNonCross : bestCross;
            if (next < 0) return -1;
            cur = next;
        }
        return -1;
    }

    private static int nearestMaxByPosition(int v, float[] positions, List<Integer> maxVerts) {
        int best = 0;
        float bestD = Float.MAX_VALUE;
        float vx = positions[v * NUM_3];
        float vy = positions[v * NUM_3 + 1];
        float vz = positions[v * NUM_3 + 2];
        for (int i = 0; i < maxVerts.size(); i++) {
            int m = maxVerts.get(i);
            float dx = positions[m * NUM_3] - vx;
            float dy = positions[m * NUM_3 + 1] - vy;
            float dz = positions[m * NUM_3 + 2] - vz;
            float d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    /**
     * Majority vote across three vertex labels with a tie-break toward
     * the vertex with highest scalar value (closest to its target max,
     * so the most authoritative).
     *
     * @param a label from corner 0
     * @param b label from corner 1
     * @param c label from corner 2
     * @param scalar per-vertex Morse scalar
     * @param faceIdx packed triangle face indices
     * @param faceId face whose corners are being voted on
     * @return chosen label
     */
    private static int majority(int a, int b, int c, float[] scalar, int[] faceIdx, int faceId) {
        if (a == b && b == c) return a;
        if (a == b) return a;
        if (a == c) return a;
        if (b == c) return b;
        // Three different labels — pick the vertex with the highest
        // scalar value, on the assumption it walked the shortest path
        // and is most reliable.
        int va = faceIdx[faceId * NUM_3];
        int vb = faceIdx[faceId * NUM_3 + 1];
        int vc = faceIdx[faceId * NUM_3 + 2];
        if (scalar[va] >= scalar[vb] && scalar[va] >= scalar[vc]) return a;
        if (scalar[vb] >= scalar[va] && scalar[vb] >= scalar[vc]) return b;
        return c;
    }

    private static int[][] buildOneRingFromFaces(int[] faceIdx, int nv) {
        int faceCount = faceIdx.length / NUM_3;
        List<java.util.HashSet<Integer>> tmp = new ArrayList<>(nv);
        for (int i = 0; i < nv; i++) tmp.add(new java.util.HashSet<>(NUM_6));
        for (int f = 0; f < faceCount; f++) {
            int a = faceIdx[f * NUM_3];
            int b = faceIdx[f * NUM_3 + 1];
            int c = faceIdx[f * NUM_3 + 2];
            tmp.get(a).add(b); tmp.get(a).add(c);
            tmp.get(b).add(a); tmp.get(b).add(c);
            tmp.get(c).add(a); tmp.get(c).add(b);
        }
        int[][] out = new int[nv][];
        for (int i = 0; i < nv; i++) {
            java.util.HashSet<Integer> set = tmp.get(i);
            int[] arr = new int[set.size()];
            int j = 0;
            for (int n : set) arr[j++] = n;
            out[i] = arr;
        }
        return out;
    }
}
