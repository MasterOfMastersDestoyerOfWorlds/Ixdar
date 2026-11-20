package ixdar.geometry.mesh.data.analysis.segmentation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.TriangulationRule;
import ixdar.numeric.cluster.EMeans;
import ixdar.numeric.graph.UnionFind;

/**
 * Java ports of the three legacy segmentation methods that used to live in
 * {@code auto_segment.py} (components / curvature / spatial). Output shape
 * matches the existing {@code .tags.json} sidecar: tag name → vertex indices.
 */
public final class MeshSegmenter {
    public static final float EPSILON = 1e-6f;
    public static final int FEATURE_DIM = 4;

    private static final float CURVATURE_WEIGHT = 1.0f;
    private static final float POSITION_WEIGHT = 0.35f;
    private static final long KMEANS_SEED = 0x1ED4CAFEL;

    private MeshSegmenter() {}

    /**
     * Connected-component segmentation via union-find over face edges.
     *
     * @param mesh source mesh
     * @return tag map keyed {@code component_<i>}, each value a sorted vertex-index array
     */
    public static Map<String, int[]> segmentComponents(MeshTopology mesh) {
        int[] faceIdx = mesh.triangulate(TriangulationRule.FAN, null);
        int[] parent = UnionFind.singletons(mesh.vertexIdBound());
        for (int i = 0; i < faceIdx.length; i += 3) {
            UnionFind.union(parent, faceIdx[i], faceIdx[i + 1]);
            UnionFind.union(parent, faceIdx[i + 1], faceIdx[i + 2]);
        }
        Map<Integer, List<Integer>> byRoot = new HashMap<>();
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            byRoot.computeIfAbsent(UnionFind.find(parent, vertexId), k -> new ArrayList<>()).add(vertexId);
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
     * K-means segmentation in a 4D feature space combining vertex curvature
     * and normalized position.
     *
     * @param mesh source mesh
     * @param nClusters target cluster count for k-means
     * @return tag map keyed {@code curvature_<i>}, each value a sorted vertex-index array
     */
    public static Map<String, int[]> segmentCurvature(MeshTopology mesh, int nClusters) {
        PackedPoints positions = SemanticPatchDecomposer.positionsById(mesh);
        float[] curvature = SemanticPatchDecomposer.computeVertexCurvature(mesh);
        int[] bucket = liveVertexIds(mesh);
        float[] bounds = computeBounds(positions, bucket);
        float posScale = 1.0f / Math.max(EPSILON, bounds[6]);
        int[] labels = kmeans(bucket, positions, curvature, bounds, posScale, nClusters);
        Map<Integer, List<Integer>> byLabel = new LinkedHashMap<>();
        for (int i = 0; i < bucket.length; i++) {
            byLabel.computeIfAbsent(labels[i], kk -> new ArrayList<>()).add(bucket[i]);
        }
        Map<String, int[]> out = new LinkedHashMap<>();
        for (int i = 0; i < nClusters; i++) {
            List<Integer> verts = byLabel.get(i);
            if (verts == null || verts.isEmpty()) continue;
            int[] arr = verts.stream().mapToInt(Integer::intValue).toArray();
            Arrays.sort(arr);
            out.put("curvature_" + i, arr);
        }
        return out;
    }

    /**
     * Pure spatial k-means segmentation (curvature feature zeroed) with cluster
     * names tagged by the dominant axis offset of each centroid from the mesh center.
     *
     * @param mesh source mesh
     * @param nClusters target cluster count for k-means
     * @return tag map keyed {@code spatial_<axis><sign>_<i>}, each value a sorted vertex-index array
     */
    public static Map<String, int[]> segmentSpatial(MeshTopology mesh, int nClusters) {
        PackedPoints positions = SemanticPatchDecomposer.positionsById(mesh);
        float[] flatCurv = new float[mesh.vertexIdBound()];
        int[] bucket = liveVertexIds(mesh);
        float[] bounds = computeBounds(positions, bucket);
        float posScale = 1.0f / Math.max(EPSILON, bounds[6]);
        int[] labels = kmeans(bucket, positions, flatCurv, bounds, posScale, nClusters);
        Map<Integer, List<Integer>> byLabel = new LinkedHashMap<>();
        for (int i = 0; i < bucket.length; i++) {
            byLabel.computeIfAbsent(labels[i], k -> new ArrayList<>()).add(bucket[i]);
        }
        float meanX = (bounds[0] + bounds[3]) * 0.5f;
        float meanY = (bounds[1] + bounds[4]) * 0.5f;
        float meanZ = (bounds[2] + bounds[5]) * 0.5f;
        Map<String, int[]> out = new LinkedHashMap<>();
        String[] axisNames = {"X", "Y", "Z"};
        for (Map.Entry<Integer, List<Integer>> e : byLabel.entrySet()) {
            Vector3f sum = new Vector3f();
            for (int v : e.getValue()) {
                sum.add(positions.at(v));
            }
            float n = e.getValue().size();
            Vector3f off = sum.div(n, n, n).sub(meanX, meanY, meanZ);
            int dominant = new Vector3f(off).absolute().maxComponent();
            String sign = off.get(dominant) > 0 ? "+" : "-";
            int[] arr = e.getValue().stream().mapToInt(Integer::intValue).toArray();
            Arrays.sort(arr);
            out.put("spatial_" + axisNames[dominant] + sign + "_" + e.getKey(), arr);
        }
        return out;
    }


    /**
     * The mesh's live vertex ids in active order.
     *
     * @param mesh mesh to read
     * @return one vertex id per active index
     */
    private static int[] liveVertexIds(MeshTopology mesh) {
        int[] vertexIds = new int[mesh.vertexCount()];
        for (int activeVertex = 0; activeVertex < vertexIds.length; activeVertex++) {
            vertexIds[activeVertex] = mesh.vertexIdAt(activeVertex);
        }
        return vertexIds;
    }

    /**
     * Axis-aligned bounds of the given vertices and their largest extent.
     *
     * @param positions positions by vertex id
     * @param vertexIds vertices to bound
     * @return {@code [minX, minY, minZ, maxX, maxY, maxZ, extent]}
     */
    private static float[] computeBounds(PackedPoints positions, int[] vertexIds) {
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        for (int vertexId : vertexIds) {
            Vector3f position = positions.at(vertexId);
            min.min(position);
            max.max(position);
        }
        Vector3f extent = new Vector3f(max).sub(min);
        return new float[]{min.x, min.y, min.z, max.x, max.y, max.z, extent.get(extent.maxComponent())};
    }

    /**
     * E-means labels of the vertices in a curvature-plus-position feature space, seeded from the
     * first vertex id so a mesh always splits the same way; at most {@code k} vertices each get
     * their own cluster.
     *
     * @param bucket    vertex ids to cluster
     * @param positions positions by vertex id
     * @param curvature curvature by vertex id
     * @param bounds    {@link #computeBounds} of the bucket
     * @param posScale  reciprocal of the bucket's largest extent
     * @param k         cluster count
     * @return cluster label per bucket entry, in {@code [0, k)}
     */
    private static int[] kmeans(int[] bucket, PackedPoints positions, float[] curvature,
                                float[] bounds, float posScale, int k) {
        int n = bucket.length;
        if (n <= k) {
            return IntStream.range(0, n).toArray();
        }
        double[] feat = new double[n * FEATURE_DIM];
        float curvMax = 0f;
        for (int v : bucket) curvMax = Math.max(curvMax, curvature[v]);
        if (curvMax < EPSILON) curvMax = 1f;
        for (int i = 0; i < n; i++) {
            int v = bucket[i];
            feat[i * FEATURE_DIM] = CURVATURE_WEIGHT * (curvature[v] / curvMax);
            for (int axis = 0; axis < 3; axis++) {
                feat[i * FEATURE_DIM + 1 + axis] =
                        POSITION_WEIGHT * (positions.get(v, axis) - bounds[axis]) * posScale;
            }
        }
        EMeans clustering = new EMeans(FEATURE_DIM, k);
        clustering.seed = KMEANS_SEED ^ bucket[0];
        clustering.begin(feat, n, EMeans.sampleCentroids(feat, n, k, FEATURE_DIM, clustering.seed));
        while (clustering.step()) {
            continue;
        }
        return clustering.labels;
    }


}
