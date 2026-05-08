package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

/**
 * Computes mesh distance metrics between two ArrayMesh instances.
 * Supports Hausdorff distance (worst-case) and Chamfer distance (average).
 */
public final class MeshDistance {
    public static final float NUM_0 = 0f;
    public static final double NUM_2_0 = 2.0;
    public static final float NUM_1 = 1f;
    public static final float NUM_100 = 100f;
    public static final float NUM_1e_10 = 1e-10f;
    public static final float NUM_2 = 2f;
    public static final float NUM_0_2 = 0.2f;
    public static final float NUM_1e_6 = 1e-6f;
    public static final float NUM_0_05 = 0.05f;

    private static final int FLOATS_PER_VERTEX = 3;

    private MeshDistance() {
    }

    /**
     * Computes the Hausdorff distance between two meshes.
     * The Hausdorff distance is the maximum of the minimum distances from each
     * point in one set to the other set (bidirectional).
     *
     * @param meshA First mesh
     * @param meshB Second mesh
     * @return Hausdorff distance (Euclidean distance)
     */
    public static float hausdorffDistance(ArrayMesh meshA, ArrayMesh meshB) {
        if (meshA == null || meshA.vertexCount() == 0 || meshB == null || meshB.vertexCount() == 0) {
            return Float.MAX_VALUE;
        }

        float[] posA = meshA.copyPositions();
        float[] posB = meshB.copyPositions();

        int vA = meshA.vertexCount();
        int vB = meshB.vertexCount();

        // Compute directed Hausdorff: max over A of min distance to B
        float maxMinDistAtoB = NUM_0;
        for (int i = 0; i < vA; i++) {
            int oA = i * FLOATS_PER_VERTEX;
            float xA = posA[oA];
            float yA = posA[oA + 1];
            float zA = posA[oA + 2];
            float minDist = Float.MAX_VALUE;
            for (int j = 0; j < vB; j++) {
                int oB = j * FLOATS_PER_VERTEX;
                float dx = xA - posB[oB];
                float dy = yA - posB[oB + 1];
                float dz = zA - posB[oB + 2];
                float distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < minDist) {
                    minDist = (float) Math.sqrt(distSq);
                }
            }
            if (minDist > maxMinDistAtoB) {
                maxMinDistAtoB = minDist;
            }
        }

        // Compute directed Hausdorff: max over B of min distance to A
        float maxMinDistBtoA = NUM_0;
        for (int j = 0; j < vB; j++) {
            int oB = j * FLOATS_PER_VERTEX;
            float xB = posB[oB];
            float yB = posB[oB + 1];
            float zB = posB[oB + 2];
            float minDist = Float.MAX_VALUE;
            for (int i = 0; i < vA; i++) {
                int oA = i * FLOATS_PER_VERTEX;
                float dx = xB - posA[oA];
                float dy = yB - posA[oA + 1];
                float dz = zB - posA[oA + 2];
                float distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < minDist) {
                    minDist = (float) Math.sqrt(distSq);
                }
            }
            if (minDist > maxMinDistBtoA) {
                maxMinDistBtoA = minDist;
            }
        }

        // Symmetric Hausdorff distance
        return Math.max(maxMinDistAtoB, maxMinDistBtoA);
    }

    /**
     * Computes the Chamfer distance between two meshes.
     * The Chamfer distance is the average of:
     * - Mean minimum distance from A to B
     * - Mean minimum distance from B to A
     *
     * @param meshA First mesh
     * @param meshB Second mesh
     * @return Chamfer distance (Euclidean distance)
     */
    public static float chamferDistance(ArrayMesh meshA, ArrayMesh meshB) {
        if (meshA == null || meshA.vertexCount() == 0 || meshB == null || meshB.vertexCount() == 0) {
            return Float.MAX_VALUE;
        }

        float[] posA = meshA.copyPositions();
        float[] posB = meshB.copyPositions();

        int vA = meshA.vertexCount();
        int vB = meshB.vertexCount();

        // Compute directed Chamfer: mean over A of min distance to B
        double sumMinDistAtoB = 0.0;
        for (int i = 0; i < vA; i++) {
            int oA = i * FLOATS_PER_VERTEX;
            float xA = posA[oA];
            float yA = posA[oA + 1];
            float zA = posA[oA + 2];
            float minDist = Float.MAX_VALUE;
            for (int j = 0; j < vB; j++) {
                int oB = j * FLOATS_PER_VERTEX;
                float dx = xA - posB[oB];
                float dy = yA - posB[oB + 1];
                float dz = zA - posB[oB + 2];
                float distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < minDist) {
                    minDist = (float) Math.sqrt(distSq);
                }
            }
            sumMinDistAtoB += minDist;
        }
        double meanDistAtoB = sumMinDistAtoB / vA;

        // Compute directed Chamfer: mean over B of min distance to A
        double sumMinDistBtoA = 0.0;
        for (int j = 0; j < vB; j++) {
            int oB = j * FLOATS_PER_VERTEX;
            float xB = posB[oB];
            float yB = posB[oB + 1];
            float zB = posB[oB + 2];
            float minDist = Float.MAX_VALUE;
            for (int i = 0; i < vA; i++) {
                int oA = i * FLOATS_PER_VERTEX;
                float dx = xB - posA[oA];
                float dy = yB - posA[oA + 1];
                float dz = zB - posA[oA + 2];
                float distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < minDist) {
                    minDist = (float) Math.sqrt(distSq);
                }
            }
            sumMinDistBtoA += minDist;
        }
        double meanDistBtoA = sumMinDistBtoA / vB;

        // Symmetric Chamfer distance
        return (float) ((meanDistAtoB + meanDistBtoA) / NUM_2_0);
    }

    /**
     * Computes the similarity score between two meshes as a percentage (0-100%).
     * Lower distances indicate higher similarity.
     *
     * The score is computed as: 100 * exp(-distance / scale)
     * where scale is a configurable parameter that determines how quickly
     * the score decays with distance.
     *
     * @param meshA First mesh
     * @param meshB Second mesh
     * @param distanceType Type of distance to use
     * @param scale Characteristic scale for normalization (larger = more tolerant)
     * @return Similarity score in range [0, 100]
     */
    public static float similarityScore(ArrayMesh meshA, ArrayMesh meshB, DistanceType distanceType, float scale) {
        if (meshA == null || meshA.vertexCount() == 0 || meshB == null || meshB.vertexCount() == 0) {
            return NUM_0;
        }
        if (scale <= NUM_0) {
            scale = NUM_1;
        }

        float distance;
        if (distanceType == DistanceType.HAUSDORFF) {
            distance = hausdorffDistance(meshA, meshB);
        } else {
            distance = chamferDistance(meshA, meshB);
        }

        if (distance == Float.MAX_VALUE) {
            return NUM_0;
        }

        // Exponential decay: score = 100 * exp(-distance / scale)
        // At distance = 0, score = 100
        // At distance = scale, score = 100 * exp(-1) ≈ 36.8
        // At distance = 3*scale, score = 100 * exp(-3) ≈ 5.0
        float score = NUM_100 * (float) Math.exp(-distance / scale);

        // Clamp to [0, 100]
        return Math.max(NUM_0, Math.min(NUM_100, score));
    }

    /**
     * Computes similarity score with default scale (1.0).
     *
     * @param meshA First mesh
     * @param meshB Second mesh
     * @param distanceType Type of distance to use
     * @return Similarity score in range [0, 100]
     */
    public static float similarityScore(ArrayMesh meshA, ArrayMesh meshB, DistanceType distanceType) {
        return similarityScore(meshA, meshB, distanceType, 1.0f);
    }

    /**
     * Computes all metrics between two meshes.
     *
     * @param meshA First mesh
     * @param meshB Second mesh
     * @return MeshMetrics containing all computed values
     */
    public static MeshMetrics computeAllMetrics(ArrayMesh meshA, ArrayMesh meshB) {
        return computeAllMetrics(meshA, meshB, 1.0f);
    }

    /**
     * Computes all metrics between two meshes with custom scale.
     *
     * @param meshA First mesh
     * @param meshB Second mesh
     * @param scale Characteristic scale for similarity normalization
     * @return MeshMetrics containing all computed values
     */
    public static MeshMetrics computeAllMetrics(ArrayMesh meshA, ArrayMesh meshB, float scale) {
        if (meshA == null || meshB == null) {
            return new MeshMetrics(Float.MAX_VALUE, Float.MAX_VALUE, NUM_0);
        }

        float hausdorff = NUM_0;
        float chamfer = NUM_0;

        if (meshA.vertexCount() > 0 && meshB.vertexCount() > 0) {
            hausdorff = hausdorffDistance(meshA, meshB);
            chamfer = chamferDistance(meshA, meshB);
        }

        float score = NUM_0;
        if (hausdorff != Float.MAX_VALUE) {
            score = NUM_100 * (float) Math.exp(-hausdorff / Math.max(scale, NUM_1e_10));
            score = Math.max(NUM_0, Math.min(NUM_100, score));
        }

        return new MeshMetrics(hausdorff, chamfer, score);
    }

    /**
     * Extracts all vertex positions from a mesh into a list of Vector3f.
     *
     * @param mesh Input mesh
     * @return List of vertex positions
     */
    public static List<Vector3f> extractVertices(ArrayMesh mesh) {
        List<Vector3f> vertices = new ArrayList<>(mesh.vertexCount());
        float[] pos = mesh.copyPositions();
        Vector3f v = new Vector3f();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int o = i * FLOATS_PER_VERTEX;
            v.set(pos[o], pos[o + 1], pos[o + 2]);
            vertices.add(new Vector3f(v));
        }
        return vertices;
    }

    /**
     * Computes distance from a point to the nearest vertex in a mesh.
     *
     * @param mesh Mesh to search
     * @param point Query point
     * @return Minimum Euclidean distance
     */
    public static float pointToMeshDistance(ArrayMesh mesh, Vector3f point) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return Float.MAX_VALUE;
        }
        float[] pos = mesh.copyPositions();
        float minDist = Float.MAX_VALUE;
        float px = point.x;
        float py = point.y;
        float pz = point.z;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int o = i * FLOATS_PER_VERTEX;
            float dx = px - pos[o];
            float dy = py - pos[o + 1];
            float dz = pz - pos[o + 2];
            float distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < minDist) {
                minDist = (float) Math.sqrt(distSq);
            }
        }
        return minDist;
    }

    /**
     * Computes distance from a mesh to a single point (minimum distance from any
     * vertex to the point).
     *
     * @param mesh Mesh to measure
     * @param point Query point
     * @return Minimum Euclidean distance
     */
    public static float meshToPointDistance(ArrayMesh mesh, Vector3f point) {
        return pointToMeshDistance(mesh, point);
    }

    /**
     * Computes the average pairwise distance between corresponding vertices of
     * two meshes (requires same vertex count and ordering).
     *
     * @param meshA First mesh
     * @param meshB Second mesh
     * @return Average Euclidean distance between corresponding vertices
     */
    public static float averageVertexDistance(ArrayMesh meshA, ArrayMesh meshB) {
        if (meshA == null || meshB == null) {
            return Float.MAX_VALUE;
        }
        if (meshA.vertexCount() != meshB.vertexCount() || meshA.vertexCount() == 0) {
            return Float.MAX_VALUE;
        }

        float[] posA = meshA.copyPositions();
        float[] posB = meshB.copyPositions();
        int v = meshA.vertexCount();
        double sumDist = 0.0;

        for (int i = 0; i < v; i++) {
            int o = i * FLOATS_PER_VERTEX;
            float dx = posA[o] - posB[o];
            float dy = posA[o + 1] - posB[o + 1];
            float dz = posA[o + 2] - posB[o + 2];
            sumDist += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        return (float) (sumDist / v);
    }

    /**
     * Compare a generated mesh against a pre-processed reference using KD-trees.
     * Auto-centers the generated mesh. Scale auto-computed as 20% of reference extent.
     *
     * @param genPos      flat XYZ positions of generated mesh
     * @param genVerts    number of vertices in generated mesh
     * @param ref         pre-processed reference (built once, reused)
     * @return full comparison result
     */
    public static CompareResult compareFull(float[] genPos, int genVerts, PreparedReference ref) {
        // Center generated mesh
        float[] genCentroid = computeCentroid(genPos, genVerts);
        float[] genC = new float[genVerts * FLOATS_PER_VERTEX];
        for (int i = 0; i < genVerts; i++) {
            int o = i * FLOATS_PER_VERTEX;
            genC[o] = genPos[o] - genCentroid[0];
            genC[o + 1] = genPos[o + 1] - genCentroid[1];
            genC[o + 2] = genPos[o + 2] - genCentroid[2];
        }

        // Build KD-tree for centered generated mesh
        KDTree3D genTree = new KDTree3D(genC, genVerts);

        // Distances: generated → reference
        float[] dGenToRef = new float[genVerts];
        for (int i = 0; i < genVerts; i++) {
            int o = i * FLOATS_PER_VERTEX;
            dGenToRef[i] = (float) Math.sqrt(ref.tree.queryNearestSq(genC[o], genC[o + 1], genC[o + 2]));
        }

        // Distances: reference → generated
        float[] dRefToGen = new float[ref.vertexCount];
        for (int i = 0; i < ref.vertexCount; i++) {
            int o = i * FLOATS_PER_VERTEX;
            dRefToGen[i] = (float) Math.sqrt(genTree.queryNearestSq(
                    ref.centeredPos[o], ref.centeredPos[o + 1], ref.centeredPos[o + 2]));
        }

        // Chamfer distance (symmetric mean of min-distances)
        float chamfer = (mean(dGenToRef) + mean(dRefToGen)) / NUM_2;

        // Hausdorff distance (symmetric max of min-distances)
        float hausdorff = Math.max(max(dGenToRef), max(dRefToGen));

        // Similarity score: exponential decay, scale = 20% of reference extent
        float scale = ref.extent * NUM_0_2;
        float similarity = NUM_100 * (float) Math.exp(-chamfer / Math.max(scale, NUM_1e_6));
        similarity = Math.max(NUM_0, Math.min(NUM_100, similarity));

        // Coverage: fraction of reference points within 5% of extent from generated
        float threshold = ref.extent * NUM_0_05;
        float coverage = fractionBelow(dRefToGen, threshold);

        // Proximity: fraction of generated points within 5% of extent from reference
        float proximity = fractionBelow(dGenToRef, threshold);

        // Per-axis spans of generated mesh (from original positions)
        float[] genMin = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] genMax = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < genVerts; i++) {
            int o = i * FLOATS_PER_VERTEX;
            for (int a = 0; a < FLOATS_PER_VERTEX; a++) {
                float v = genPos[o + a];
                if (v < genMin[a]) genMin[a] = v;
                if (v > genMax[a]) genMax[a] = v;
            }
        }
        float[] genSpans = {genMax[0] - genMin[0], genMax[1] - genMin[1], genMax[2] - genMin[2]};

        // Centroid offset (gen - ref, before centering)
        float[] centroidOffset = {
                genCentroid[0] - ref.centroid[0],
                genCentroid[1] - ref.centroid[1],
                genCentroid[2] - ref.centroid[2]};

        return new CompareResult(chamfer, hausdorff, similarity, coverage, proximity,
                genSpans, ref.spans, centroidOffset);
    }

    /**
     * Extract flat XYZ positions from a MeshTopology.
     *
     * @param mesh TODO: describe
     * @return TODO: describe
     */
    public static float[] extractPositions(MeshTopology mesh) {
        float[] pos = new float[mesh.vertexCount() * FLOATS_PER_VERTEX];
        Vector3f p = new Vector3f();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int vid = mesh.vertexIdAt(i);
            mesh.vertexPosition(vid, p);
            int o = i * FLOATS_PER_VERTEX;
            pos[o] = p.x;
            pos[o + 1] = p.y;
            pos[o + 2] = p.z;
        }
        return pos;
    }

    // ─── helpers ────────────────────────────────────────────────────

    private static float[] computeCentroid(float[] pos, int vertexCount) {
        double cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < vertexCount; i++) {
            int o = i * FLOATS_PER_VERTEX;
            cx += pos[o];
            cy += pos[o + 1];
            cz += pos[o + 2];
        }
        float n = vertexCount;
        return new float[]{(float) (cx / n), (float) (cy / n), (float) (cz / n)};
    }

    private static float mean(float[] arr) {
        double sum = 0;
        for (float v : arr) sum += v;
        return (float) (sum / arr.length);
    }

    private static float max(float[] arr) {
        float m = arr[0];
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > m) m = arr[i];
        }
        return m;
    }

    private static float fractionBelow(float[] arr, float threshold) {
        int count = 0;
        for (float v : arr) {
            if (v < threshold) count++;
        }
        return (float) count / arr.length;
    }

    /**
     * Type of distance metric to compute.
     */
    public enum DistanceType {
        /** Hausdorff distance (worst-case, symmetric). */
        HAUSDORFF,
        /** Chamfer distance (average, bidirectional). */
        CHAMFER
    }

    /**
     * Container for mesh distance metrics.
     */
    public static class MeshMetrics {
        public final float hausdorffDistance;
        public final float chamferDistance;
        public final float similarityScore;

        /**
         * TODO: document {@code MeshMetrics}.
         *
         * @param hausdorffDistance TODO: describe
         * @param chamferDistance TODO: describe
         * @param similarityScore TODO: describe
         */
        public MeshMetrics(float hausdorffDistance, float chamferDistance, float similarityScore) {
            this.hausdorffDistance = hausdorffDistance;
            this.chamferDistance = chamferDistance;
            this.similarityScore = similarityScore;
        }

        /**
         * TODO: document {@code toString}.
         *
         * @return TODO: describe
         */
        @Override
        public String toString() {
            return String.format("MeshMetrics{hausdorff=%.4f, chamfer=%.4f, similarity=%.2f%%}",
                    hausdorffDistance, chamferDistance, similarityScore);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // KD-tree accelerated comparison (for batch optimization)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Full comparison result compatible with Python mesh_compare output.
     * Includes chamfer, hausdorff, similarity, coverage, proximity, and extent info.
     */
    public record CompareResult(
            float chamferDistance,
            float hausdorffDistance,
            float similarityScore,
            float coverage,
            float proximity,
            float[] perAxisGenSpans,
            float[] perAxisRefSpans,
            float[] centroidOffset) {
    }

    /**
     * Pre-processed reference mesh for efficient batch comparison.
     * Build once, reuse across many generated mesh comparisons.
     * Thread-safe for queries after construction.
     */
    public static final class PreparedReference {
        public static final int NUM_3 = 3;
        public final float[] centeredPos;
        public final int vertexCount;
        public final KDTree3D tree;
        public final float extent;
        public final float[] spans; // X, Y, Z spans of original positions
        public final float[] centroid;

        /**
         * TODO: document {@code PreparedReference}.
         *
         * @param positions TODO: describe
         * @param vertexCount TODO: describe
         */
        public PreparedReference(float[] positions, int vertexCount) {
            this.vertexCount = vertexCount;
            this.centroid = computeCentroid(positions, vertexCount);
            centeredPos = new float[vertexCount * NUM_3];
            for (int i = 0; i < vertexCount; i++) {
                int o = i * NUM_3;
                centeredPos[o] = positions[o] - centroid[0];
                centeredPos[o + 1] = positions[o + 1] - centroid[1];
                centeredPos[o + 2] = positions[o + 2] - centroid[2];
            }
            tree = new KDTree3D(centeredPos, vertexCount);

            // Compute axis spans from original positions
            float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int i = 0; i < vertexCount; i++) {
                int o = i * NUM_3;
                for (int a = 0; a < NUM_3; a++) {
                    float v = positions[o + a];
                    if (v < min[a]) min[a] = v;
                    if (v > max[a]) max[a] = v;
                }
            }
            spans = new float[]{max[0] - min[0], max[1] - min[1], max[2] - min[2]};
            extent = Math.max(spans[0], Math.max(spans[1], spans[2]));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 3D KD-Tree for O(log n) nearest-neighbor queries
    // ═══════════════════════════════════════════════════════════════

    /**
     * Simple static 3D KD-tree. Thread-safe for queries after construction.
     * Uses array-of-struct layout for cache efficiency.
     */
    static final class KDTree3D {
        public static final int NUM_3 = 3;
        private final float[] pos;          // original flat XYZ positions (shared ref)
        private final int[] nodeVertIdx;    // vertex index stored at tree node i
        private final byte[] nodeAxis;      // split axis at tree node i (0/1/2)
        private final int[] left;           // left child node index, -1 = none
        private final int[] right;          // right child node index, -1 = none
        private int nodeCount;

        KDTree3D(float[] positions, int vertexCount) {
            this.pos = positions;
            int n = vertexCount;
            nodeVertIdx = new int[n];
            nodeAxis = new byte[n];
            left = new int[n];
            right = new int[n];
            nodeCount = 0;

            int[] indices = new int[n];
            for (int i = 0; i < n; i++) indices[i] = i;
            build(indices, 0, n, 0);
        }

        private int build(int[] idx, int lo, int hi, int depth) {
            if (lo >= hi) return -1;
            int ax = depth % NUM_3;
            int mid = (lo + hi) / 2;
            nthElement(idx, lo, hi, mid, ax);

            int id = nodeCount++;
            nodeVertIdx[id] = idx[mid];
            nodeAxis[id] = (byte) ax;
            left[id] = build(idx, lo, mid, depth + 1);
            right[id] = build(idx, mid + 1, hi, depth + 1);
            return id;
        }

        /**
         * Returns squared distance to the nearest point in the tree.
         *
         * @param qx TODO: describe
         * @param qy TODO: describe
         * @param qz TODO: describe
         * @return TODO: describe
         */
        float queryNearestSq(float qx, float qy, float qz) {
            if (nodeCount == 0) return Float.MAX_VALUE;
            return searchSq(0, qx, qy, qz, Float.MAX_VALUE);
        }

        private float searchSq(int node, float qx, float qy, float qz, float bestSq) {
            if (node < 0) return bestSq;

            int vi = nodeVertIdx[node];
            int o = vi * NUM_3;
            float dx = qx - pos[o];
            float dy = qy - pos[o + 1];
            float dz = qz - pos[o + 2];
            float distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestSq) bestSq = distSq;

            int ax = nodeAxis[node];
            float diff = (ax == 0 ? dx : ax == 1 ? dy : dz);
            float diffSq = diff * diff;

            // Search the side the query point is on first
            int near = diff <= 0 ? left[node] : right[node];
            int far = diff <= 0 ? right[node] : left[node];

            bestSq = searchSq(near, qx, qy, qz, bestSq);
            // Only search far side if splitting plane is closer than current best
            if (diffSq < bestSq) {
                bestSq = searchSq(far, qx, qy, qz, bestSq);
            }
            return bestSq;
        }

        /**
         * Quickselect: rearrange idx[lo..hi) so that idx[k] holds the element
         * that would be at position k if sorted by positions on the given axis.
         *
         * @param idx TODO: describe
         * @param lo TODO: describe
         * @param hi TODO: describe
         * @param k TODO: describe
         * @param ax TODO: describe
         */
        private void nthElement(int[] idx, int lo, int hi, int k, int ax) {
            while (lo < hi - 1) {
                int pivotPos = lo + (hi - lo) / 2;
                float pivotVal = pos[idx[pivotPos] * NUM_3 + ax];
                swap(idx, pivotPos, hi - 1);
                int store = lo;
                for (int i = lo; i < hi - 1; i++) {
                    if (pos[idx[i] * NUM_3 + ax] < pivotVal) {
                        swap(idx, i, store++);
                    }
                }
                swap(idx, store, hi - 1);
                if (store == k) return;
                else if (k < store) hi = store;
                else lo = store + 1;
            }
        }

        private static void swap(int[] a, int i, int j) {
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }
}
