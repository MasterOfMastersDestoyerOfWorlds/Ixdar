package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Java ports of the three legacy segmentation methods that used to live in
 * {@code auto_segment.py} (components / curvature / spatial). Output shape
 * matches the existing {@code .tags.json} sidecar: tag name → vertex indices.
 */
public final class MeshSegmenter {
    public static final int NUM_3 = 3;
    public static final float NUM_1e_6 = 1e-6f;
    public static final int NUM_6 = 6;
    public static final float NUM_0_5 = 0.5f;
    public static final int NUM_4 = 4;
    public static final int NUM_5 = 5;
    public static final float NUM_0 = 0f;
    public static final float NUM_1 = 1f;

    private static final int KMEANS_MAX_ITER = 40;
    private static final float CURVATURE_WEIGHT = 1.0f;
    private static final float POSITION_WEIGHT = 0.35f;
    private static final long KMEANS_SEED = 0x1ED4CAFEL;

    private MeshSegmenter() {}

    /**
     * TODO: document {@code segmentComponents}.
     *
     * @param mesh TODO: describe
     * @return TODO: describe
     */
    public static Map<String, int[]> segmentComponents(ArrayMesh mesh) {
        int nv = mesh.vertexCount();
        int[] faceIdx = mesh.copyFaceIndices();
        int[] parent = new int[nv];
        for (int i = 0; i < nv; i++) parent[i] = i;
        for (int i = 0; i < faceIdx.length; i += NUM_3) {
            union(parent, faceIdx[i], faceIdx[i + 1]);
            union(parent, faceIdx[i + 1], faceIdx[i + 2]);
        }
        Map<Integer, List<Integer>> byRoot = new HashMap<>();
        for (int v = 0; v < nv; v++) {
            byRoot.computeIfAbsent(find(parent, v), k -> new ArrayList<>()).add(v);
        }
        Map<String, int[]> out = new LinkedHashMap<>();
        int i = 0;
        for (List<Integer> verts : byRoot.values()) {
            int[] arr = verts.stream().mapToInt(Integer::intValue).toArray();
            Arrays.sort(arr);
            out.put("component_" + i, arr);
            i++;
        }
        return out;
    }

    /**
     * TODO: document {@code segmentCurvature}.
     *
     * @param mesh TODO: describe
     * @param nClusters TODO: describe
     * @return TODO: describe
     */
    public static Map<String, int[]> segmentCurvature(ArrayMesh mesh, int nClusters) {
        int nv = mesh.vertexCount();
        float[] positions = mesh.copyPositions();
        float[] curvature = SemanticPatchDecomposer.computeVertexCurvature(mesh);
        float[] bounds = computeBounds(positions);
        float posScale = 1.0f / Math.max(NUM_1e_6, bounds[NUM_6]);
        int[] bucket = new int[nv];
        for (int v = 0; v < nv; v++) bucket[v] = v;
        int[] labels = kmeans(bucket, positions, curvature, bounds, posScale, nClusters);
        return labelsToMap(labels, nv, nClusters, "curvature_");
    }

    /**
     * TODO: document {@code segmentSpatial}.
     *
     * @param mesh TODO: describe
     * @param nClusters TODO: describe
     * @return TODO: describe
     */
    public static Map<String, int[]> segmentSpatial(ArrayMesh mesh, int nClusters) {
        int nv = mesh.vertexCount();
        float[] positions = mesh.copyPositions();
        float[] flatCurv = new float[nv];
        float[] bounds = computeBounds(positions);
        float posScale = 1.0f / Math.max(NUM_1e_6, bounds[NUM_6]);
        int[] bucket = new int[nv];
        for (int v = 0; v < nv; v++) bucket[v] = v;
        int[] labels = kmeans(bucket, positions, flatCurv, bounds, posScale, nClusters);
        Map<Integer, List<Integer>> byLabel = new LinkedHashMap<>();
        for (int v = 0; v < nv; v++) {
            byLabel.computeIfAbsent(labels[v], k -> new ArrayList<>()).add(v);
        }
        float meanX = (bounds[0] + bounds[NUM_3]) * NUM_0_5;
        float meanY = (bounds[1] + bounds[NUM_4]) * NUM_0_5;
        float meanZ = (bounds[2] + bounds[NUM_5]) * NUM_0_5;
        Map<String, int[]> out = new LinkedHashMap<>();
        String[] axisNames = {"X", "Y", "Z"};
        for (Map.Entry<Integer, List<Integer>> e : byLabel.entrySet()) {
            float cx = NUM_0, cy = NUM_0, cz = NUM_0;
            for (int v : e.getValue()) {
                cx += positions[v * NUM_3];
                cy += positions[v * NUM_3 + 1];
                cz += positions[v * NUM_3 + 2];
            }
            int n = e.getValue().size();
            cx /= n; cy /= n; cz /= n;
            float[] off = {cx - meanX, cy - meanY, cz - meanZ};
            int dominant = 0;
            for (int a = 1; a < NUM_3; a++) {
                if (Math.abs(off[a]) > Math.abs(off[dominant])) dominant = a;
            }
            String sign = off[dominant] > 0 ? "+" : "-";
            int[] arr = e.getValue().stream().mapToInt(Integer::intValue).toArray();
            Arrays.sort(arr);
            out.put("spatial_" + axisNames[dominant] + sign + "_" + e.getKey(), arr);
        }
        return out;
    }

    // ---------- private helpers (lifted from the old SemanticPatchDecomposer) ----------

    static float[] computeBounds(float[] positions) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < positions.length; i += NUM_3) {
            float x = positions[i], y = positions[i + 1], z = positions[i + 2];
            if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z;
            if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z;
        }
        float extX = maxX - minX, extY = maxY - minY, extZ = maxZ - minZ;
        float ext = Math.max(extX, Math.max(extY, extZ));
        return new float[]{minX, minY, minZ, maxX, maxY, maxZ, ext};
    }

    private static int[] kmeans(int[] bucket, float[] positions, float[] curvature,
                                float[] bounds, float posScale, int k) {
        int n = bucket.length;
        int featDim = NUM_4;
        float[] feat = new float[n * featDim];
        float curvMax = NUM_0;
        for (int v : bucket) curvMax = Math.max(curvMax, curvature[v]);
        if (curvMax < NUM_1e_6) curvMax = NUM_1;
        for (int i = 0; i < n; i++) {
            int v = bucket[i];
            feat[i * featDim] = CURVATURE_WEIGHT * (curvature[v] / curvMax);
            feat[i * featDim + 1] = POSITION_WEIGHT * (positions[v * NUM_3] - bounds[0]) * posScale;
            feat[i * featDim + 2] = POSITION_WEIGHT * (positions[v * NUM_3 + 1] - bounds[1]) * posScale;
            feat[i * featDim + NUM_3] = POSITION_WEIGHT * (positions[v * NUM_3 + 2] - bounds[2]) * posScale;
        }
        Random rnd = new Random(KMEANS_SEED ^ (bucket.length > 0 ? bucket[0] : 0));
        float[] centroids = new float[k * featDim];
        int first = rnd.nextInt(n);
        System.arraycopy(feat, first * featDim, centroids, 0, featDim);
        float[] d2 = new float[n];
        Arrays.fill(d2, Float.MAX_VALUE);
        for (int ci = 1; ci < k; ci++) {
            double total = 0;
            for (int i = 0; i < n; i++) {
                float dd = distSq(feat, i, centroids, ci - 1, featDim);
                if (dd < d2[i]) d2[i] = dd;
                total += d2[i];
            }
            double target = rnd.nextDouble() * total;
            double acc = 0;
            int pick = n - 1;
            for (int i = 0; i < n; i++) {
                acc += d2[i];
                if (acc >= target) {
                    pick = i;
                    break;
                }
            }
            System.arraycopy(feat, pick * featDim, centroids, ci * featDim, featDim);
        }
        int[] labels = new int[n];
        float[] newCentroids = new float[k * featDim];
        int[] counts = new int[k];
        for (int iter = 0; iter < KMEANS_MAX_ITER; iter++) {
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int best = 0;
                float bestD = Float.MAX_VALUE;
                for (int c = 0; c < k; c++) {
                    float dd = distSq(feat, i, centroids, c, featDim);
                    if (dd < bestD) {
                        bestD = dd;
                        best = c;
                    }
                }
                if (labels[i] != best) {
                    changed = true;
                    labels[i] = best;
                }
            }
            if (!changed && iter > 0) break;
            Arrays.fill(newCentroids, NUM_0);
            Arrays.fill(counts, 0);
            for (int i = 0; i < n; i++) {
                int c = labels[i];
                counts[c]++;
                for (int d = 0; d < featDim; d++) {
                    newCentroids[c * featDim + d] += feat[i * featDim + d];
                }
            }
            for (int c = 0; c < k; c++) {
                if (counts[c] == 0) continue;
                for (int d = 0; d < featDim; d++) {
                    centroids[c * featDim + d] = newCentroids[c * featDim + d] / counts[c];
                }
            }
        }
        return labels;
    }

    private static float distSq(float[] feat, int i, float[] centroids, int c, int d) {
        float s = NUM_0;
        for (int k = 0; k < d; k++) {
            float diff = feat[i * d + k] - centroids[c * d + k];
            s += diff * diff;
        }
        return s;
    }

    private static Map<String, int[]> labelsToMap(int[] labels, int nv, int k, String prefix) {
        Map<Integer, List<Integer>> byLabel = new LinkedHashMap<>();
        for (int v = 0; v < nv; v++) {
            byLabel.computeIfAbsent(labels[v], kk -> new ArrayList<>()).add(v);
        }
        Map<String, int[]> out = new LinkedHashMap<>();
        for (int i = 0; i < k; i++) {
            List<Integer> verts = byLabel.get(i);
            if (verts == null || verts.isEmpty()) continue;
            int[] arr = verts.stream().mapToInt(Integer::intValue).toArray();
            Arrays.sort(arr);
            out.put(prefix + i, arr);
        }
        return out;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }
}
