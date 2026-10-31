package ixdar.geometry.mesh.data.analysis;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * Computes mesh distance metrics between two MeshTopology instances.
 * Supports Hausdorff distance (worst-case) and Chamfer distance (average).
 */
public final class MeshDistance {
    public static final float MAX_SCORE = 100f;
    public static final float MIN_SIMILARITY_SCALE = 1e-10f;
    public static final float SIMILARITY_SCALE_RATIO = 0.2f;
    public static final float MIN_CHAMFER_SCALE = 1e-6f;
    public static final float COVERAGE_THRESHOLD_RATIO = 0.05f;
    public static final float MIN_DIAGONAL = 1e-8f;

    private static final int FLOATS_PER_VERTEX = 3;

    private MeshDistance() {
    }

    /**
     * Computes all metrics between two meshes with custom scale.
     *
     * @param meshA packed xyz positions of the first mesh
     * @param meshB packed xyz positions of the second mesh
     * @param scale Characteristic scale for similarity normalization
     * @return MeshMetrics containing all computed values
     */
    public static MeshMetrics computeAllMetrics(float[] meshA, float[] meshB, float scale) {
        if (meshA == null || meshB == null) {
            return new MeshMetrics(Float.MAX_VALUE, Float.MAX_VALUE, 0f);
        }

        float hausdorff = 0f;
        float chamfer = 0f;

        if (meshA.length > 0 && meshB.length > 0) {
            // Each direction's nearest-vertex distances, answered by a KD-tree over the other
            // mesh, give its Hausdorff maximum and its Chamfer mean.
            float[] directedMax = new float[2];
            double[] directedMean = new double[2];
            for (int direction = 0; direction < 2; direction++) {
                float[] from = direction == 0 ? meshA : meshB;
                float[] to = direction == 0 ? meshB : meshA;
                int fromCount = from.length / FLOATS_PER_VERTEX;
                KDTree3D nearest = new KDTree3D(to, to.length / FLOATS_PER_VERTEX);
                float maxMinDist = 0f;
                double sumMinDist = 0.0;
                for (int i = 0; i < fromCount; i++) {
                    int oFrom = i * FLOATS_PER_VERTEX;
                    float minDist = (float) Math.sqrt(nearest.queryNearestSq(
                            from[oFrom], from[oFrom + 1], from[oFrom + 2]));
                    if (minDist > maxMinDist) {
                        maxMinDist = minDist;
                    }
                    sumMinDist += minDist;
                }
                directedMax[direction] = maxMinDist;
                directedMean[direction] = sumMinDist / fromCount;
            }
            hausdorff = Math.max(directedMax[0], directedMax[1]);
            chamfer = (float) ((directedMean[0] + directedMean[1]) / 2.0);
        }

        float score = 0f;
        if (hausdorff != Float.MAX_VALUE) {
            score = MAX_SCORE * (float) Math.exp(-hausdorff / Math.max(scale, MIN_SIMILARITY_SCALE));
            score = Math.max(0f, Math.min(MAX_SCORE, score));
        }

        return new MeshMetrics(hausdorff, chamfer, score);
    }

    /**
     * The mesh's packed positions centered on its bounding-box center and scaled to a unit
     * bounding-box diagonal, the frame two meshes are compared in when asked to normalize.
     *
     * @param mesh mesh to read
     * @return normalized packed xyz positions in active-vertex order
     */
    public static float[] normalizedPositions(MeshTopology mesh) {
        float[] pos = extractPositions(mesh);
        int n = mesh.vertexCount();
        if (n == 0) {
            return pos;
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int o = i * FLOATS_PER_VERTEX;
            minX = Math.min(minX, pos[o]);
            maxX = Math.max(maxX, pos[o]);
            minY = Math.min(minY, pos[o + 1]);
            maxY = Math.max(maxY, pos[o + 1]);
            minZ = Math.min(minZ, pos[o + 2]);
            maxZ = Math.max(maxZ, pos[o + 2]);
        }
        float cx = (minX + maxX) * 0.5f;
        float cy = (minY + maxY) * 0.5f;
        float cz = (minZ + maxZ) * 0.5f;
        float dx = maxX - minX;
        float dy = maxY - minY;
        float dz = maxZ - minZ;
        float diagonal = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float invDiag = diagonal > MIN_DIAGONAL ? 1.0f / diagonal : 1.0f;
        for (int i = 0; i < n; i++) {
            int o = i * FLOATS_PER_VERTEX;
            pos[o] = (pos[o] - cx) * invDiag;
            pos[o + 1] = (pos[o + 1] - cy) * invDiag;
            pos[o + 2] = (pos[o + 2] - cz) * invDiag;
        }
        return pos;
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
        float chamfer = (mean(dGenToRef) + mean(dRefToGen)) / 2f;

        // Hausdorff distance (symmetric max of min-distances)
        float hausdorff = Math.max(max(dGenToRef), max(dRefToGen));

        // Similarity score: exponential decay, scale = 20% of reference extent
        float scale = ref.extent * SIMILARITY_SCALE_RATIO;
        float similarity = MAX_SCORE * (float) Math.exp(-chamfer / Math.max(scale, MIN_CHAMFER_SCALE));
        similarity = Math.max(0f, Math.min(MAX_SCORE, similarity));

        // Coverage: fraction of reference points within 5% of extent from generated
        float threshold = ref.extent * COVERAGE_THRESHOLD_RATIO;
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
     * @param mesh source topology
     * @return packed xyz of every active vertex, in the mesh's iteration order
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
     * Container for mesh distance metrics.
     */
    public static class MeshMetrics {
        public final float hausdorffDistance;
        public final float chamferDistance;
        public final float similarityScore;

        /**
         * Bundle of distance + similarity metrics produced by {@link #computeAllMetrics}.
         *
         * @param hausdorffDistance symmetric Hausdorff distance
         * @param chamferDistance symmetric Chamfer distance
         * @param similarityScore exponential decay of {@code hausdorffDistance} mapped to [0, 100]
         */
        public MeshMetrics(float hausdorffDistance, float chamferDistance, float similarityScore) {
            this.hausdorffDistance = hausdorffDistance;
            this.chamferDistance = chamferDistance;
            this.similarityScore = similarityScore;
        }

        /**
         * Human-readable summary of all three metrics.
         *
         * @return formatted string with hausdorff, chamfer, and similarity percentage
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
        public final float[] centeredPos;
        public final int vertexCount;
        public final KDTree3D tree;
        public final float extent;
        public final float[] spans; // X, Y, Z spans of original positions
        public final float[] centroid;

        /**
         * Pre-process a reference mesh: center on its centroid, build a KD-tree, and
         * record per-axis spans.
         *
         * @param positions packed XYZ vertex positions of the reference
         * @param vertexCount number of vertices in {@code positions}
         */
        public PreparedReference(float[] positions, int vertexCount) {
            this.vertexCount = vertexCount;
            this.centroid = computeCentroid(positions, vertexCount);
            centeredPos = new float[vertexCount * 3];
            for (int i = 0; i < vertexCount; i++) {
                int o = i * 3;
                centeredPos[o] = positions[o] - centroid[0];
                centeredPos[o + 1] = positions[o + 1] - centroid[1];
                centeredPos[o + 2] = positions[o + 2] - centroid[2];
            }
            tree = new KDTree3D(centeredPos, vertexCount);

            // Compute axis spans from original positions
            float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int i = 0; i < vertexCount; i++) {
                int o = i * 3;
                for (int a = 0; a < 3; a++) {
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
            int ax = depth % 3;
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
         * @param qx query x
         * @param qy query y
         * @param qz query z
         * @return squared Euclidean distance to the closest stored point, or {@link Float#MAX_VALUE} for an empty tree
         */
        float queryNearestSq(float qx, float qy, float qz) {
            if (nodeCount == 0) return Float.MAX_VALUE;
            return searchSq(0, qx, qy, qz, Float.MAX_VALUE);
        }

        private float searchSq(int node, float qx, float qy, float qz, float bestSq) {
            if (node < 0) return bestSq;

            int vi = nodeVertIdx[node];
            int o = vi * 3;
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
         * @param idx vertex index buffer; partitioned in place
         * @param lo inclusive lower bound of the active range
         * @param hi exclusive upper bound of the active range
         * @param k position whose final occupant is sought
         * @param ax axis (0=X, 1=Y, 2=Z) used as the comparison key
         */
        private void nthElement(int[] idx, int lo, int hi, int k, int ax) {
            while (lo < hi - 1) {
                int pivotPos = lo + (hi - lo) / 2;
                float pivotVal = pos[idx[pivotPos] * 3 + ax];
                swap(idx, pivotPos, hi - 1);
                int store = lo;
                for (int i = lo; i < hi - 1; i++) {
                    if (pos[idx[i] * 3 + ax] < pivotVal) {
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
