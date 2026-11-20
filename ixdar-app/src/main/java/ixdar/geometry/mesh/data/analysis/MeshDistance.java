package ixdar.geometry.mesh.data.analysis;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;

/**
 * Computes mesh distance metrics between two MeshTopology instances.
 * Supports Hausdorff distance (worst-case) and Chamfer distance (average).
 */
public final class MeshDistance {
    public static final float MAX_SCORE = 100f;
    public static final float MIN_SIMILARITY_SCALE = 1e-10f;
    public static final float MIN_DIAGONAL = 1e-8f;

    private MeshDistance() {
    }

    /**
     * Computes all metrics between two meshes with custom scale.
     *
     * @param meshA positions of the first mesh
     * @param meshB positions of the second mesh
     * @param scale Characteristic scale for similarity normalization
     * @return MeshMetrics containing all computed values
     */
    public static MeshMetrics computeAllMetrics(PackedPoints meshA, PackedPoints meshB, float scale) {
        if (meshA == null || meshB == null) {
            return new MeshMetrics(Float.MAX_VALUE, Float.MAX_VALUE, 0f);
        }

        float hausdorff = 0f;
        float chamfer = 0f;

        if (meshA.size() > 0 && meshB.size() > 0) {
            float[] directedMax = new float[2];
            double[] directedMean = new double[2];
            for (int direction = 0; direction < 2; direction++) {
                PackedPoints from = direction == 0 ? meshA : meshB;
                PackedPoints to = direction == 0 ? meshB : meshA;
                int fromCount = from.size();
                KDTree3D nearest = new KDTree3D(to);
                float maxMinDist = 0f;
                double sumMinDist = 0.0;
                for (int i = 0; i < fromCount; i++) {
                    float minDist = (float) Math.sqrt(nearest.queryNearestSq(from.at(i)));
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
     * @return normalized positions in active-vertex order
     */
    public static PackedPoints normalizedPositions(MeshTopology mesh) {
        return normalizedPositions(mesh, mesh);
    }

    /**
     * A mesh's packed positions in another mesh's normalized frame: centered on the frame mesh's
     * bounding-box center and scaled by its inverse diagonal, so a sculpt and its base share one.
     *
     * @param mesh  mesh to read
     * @param frame mesh whose bounding box defines the frame
     * @return positions in active-vertex order, in the frame's unit-diagonal coordinates
     */
    public static PackedPoints normalizedPositions(MeshTopology mesh, MeshTopology frame) {
        return normalized(mesh.packedPositions(null), frame);
    }

    /**
     * Carry positions into a mesh's normalized frame, as {@link #normalizedPositions} does a
     * mesh's own: points read off a goal (its ridge lines) then share the frame its sculpt is in.
     *
     * @param points positions in the frame mesh's coordinates, overwritten
     * @param frame  mesh whose bounding box defines the frame
     * @return {@code points}, now in the frame's unit-diagonal coordinates
     */
    public static PackedPoints normalized(PackedPoints points, MeshTopology frame) {
        float diagonal = frame.boundsDiagonal();
        float scale = diagonal > MIN_DIAGONAL ? 1.0f / diagonal : 1.0f;
        points.transformPositions(new Matrix4f().translation(frame.center(new Vector3f()).negate()));
        points.transformPositions(new Matrix4f().scaling(scale));
        return points;
    }

    /**
     * Carry normalized positions out into a frame, the inverse of {@link #normalizedPositions}.
     *
     * @param unitPoints positions in unit-diagonal coordinates, overwritten
     * @param center     the frame's bounding-box center
     * @param diagonal   the frame's bounding-box diagonal
     * @return {@code unitPoints}, now in the frame's coordinates
     */
    public static PackedPoints inFrame(PackedPoints unitPoints, Vector3f center, float diagonal) {
        unitPoints.transformPositions(new Matrix4f().translation(center).scale(diagonal));
        return unitPoints;
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

    /**
     * Simple static 3D KD-tree. Thread-safe for queries after construction.
     * Uses array-of-struct layout for cache efficiency.
     */
    static final class KDTree3D {
        private final PackedPoints pos;     // original positions (shared ref)
        private final int[] nodeVertIdx;    // vertex index stored at tree node i
        private final byte[] nodeAxis;      // split axis at tree node i (0/1/2)
        private final int[] left;           // left child node index, -1 = none
        private final int[] right;          // right child node index, -1 = none
        private int nodeCount;

        KDTree3D(PackedPoints positions) {
            this.pos = positions;
            int n = positions.size();
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
         * @param query the query position
         * @return squared Euclidean distance to the closest stored point, or {@link Float#MAX_VALUE} for an empty tree
         */
        private float queryNearestSq(Vector3f query) {
            if (nodeCount == 0) return Float.MAX_VALUE;
            return searchSq(0, query, Float.MAX_VALUE);
        }

        private float searchSq(int node, Vector3f query, float bestSq) {
            if (node < 0) return bestSq;

            int vi = nodeVertIdx[node];
            bestSq = Math.min(bestSq, (float) pos.distanceSquared(vi, query));

            int ax = nodeAxis[node];
            float diff = query.get(ax) - pos.get(vi, ax);
            float diffSq = diff * diff;

            int near = diff <= 0 ? left[node] : right[node];
            int far = diff <= 0 ? right[node] : left[node];

            bestSq = searchSq(near, query, bestSq);
            if (diffSq < bestSq) {
                bestSq = searchSq(far, query, bestSq);
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
                float pivotVal = pos.get(idx[pivotPos], ax);
                swap(idx, pivotPos, hi - 1);
                int store = lo;
                for (int i = lo; i < hi - 1; i++) {
                    if (pos.get(idx[i], ax) < pivotVal) {
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
