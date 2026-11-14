package ixdar.numeric.cluster;

import java.util.Arrays;

/**
 * Lloyd's k-means, the full-pass baseline e-means is measured against: each iteration assigns
 * every point, then moves every centroid to the mean of its points. An emptied cluster is re-seeded
 * by splitting the largest one, as faiss's k-means does.
 */
public final class LloydKMeans {

    /** Coordinates per point. */
    public final int dimension;

    /** Number of centroids. */
    public final int clusterCount;

    /** The assignment step. */
    public NearestCentroid nearest = NearestCentroid.EUCLIDEAN;

    /** Centroid coordinates, {@link #dimension} per centroid. */
    public double[] centroids = new double[0];

    /** Each point's centroid in the last iteration. */
    public int[] labels = new int[0];

    /** Iterations run so far. */
    public int iterations;

    private double[] points = new double[0];
    private int pointCount;
    private double[] sums = new double[0];
    private int[] counts = new int[0];

    /**
     * A Lloyd run waiting for {@link #begin}.
     *
     * @param dimension    coordinates per point
     * @param clusterCount number of centroids
     */
    public LloydKMeans(int dimension, int clusterCount) {
        this.dimension = dimension;
        this.clusterCount = clusterCount;
    }

    /**
     * Start from given centroids; nothing moves until {@link #iterate}.
     *
     * @param points           packed coordinates, {@link #dimension} per point
     * @param pointCount       number of points
     * @param initialCentroids packed starting centroids, copied
     */
    public void begin(double[] points, int pointCount, double[] initialCentroids) {
        this.points = points;
        this.pointCount = pointCount;
        centroids = Arrays.copyOf(initialCentroids, clusterCount * dimension);
        labels = new int[pointCount];
        sums = new double[clusterCount * dimension];
        counts = new int[clusterCount];
        iterations = 0;
    }

    /**
     * One Lloyd iteration: assign every point to its nearest centroid, set each centroid to its
     * points' mean, and re-seed each empty cluster by splitting the largest.
     */
    public void iterate() {
        Arrays.fill(sums, 0);
        Arrays.fill(counts, 0);
        for (int point = 0; point < pointCount; point++) {
            int cluster = nearest.nearest(points, point, centroids, clusterCount, dimension);
            labels[point] = cluster;
            counts[cluster]++;
            for (int axis = 0; axis < dimension; axis++) {
                sums[cluster * dimension + axis] += points[point * dimension + axis];
            }
        }
        for (int cluster = 0; cluster < clusterCount; cluster++) {
            for (int axis = 0; counts[cluster] > 0 && axis < dimension; axis++) {
                centroids[cluster * dimension + axis] = sums[cluster * dimension + axis] / counts[cluster];
            }
        }
        for (int empty = 0; empty < clusterCount; empty++) {
            if (counts[empty] > 0) {
                continue;
            }
            int largest = 0;
            for (int cluster = 1; cluster < clusterCount; cluster++) {
                largest = counts[cluster] > counts[largest] ? cluster : largest;
            }
            for (int axis = 0; axis < dimension; axis++) {
                double offset = EMeans.SPLIT_EPSILON * (axis % 2 == 0 ? 1 : -1);
                double base = centroids[largest * dimension + axis];
                centroids[largest * dimension + axis] = base * (1 - offset);
                centroids[empty * dimension + axis] = base * (1 + offset);
            }
            counts[empty] = counts[largest] / 2;
            counts[largest] -= counts[empty];
        }
        iterations++;
    }
}
