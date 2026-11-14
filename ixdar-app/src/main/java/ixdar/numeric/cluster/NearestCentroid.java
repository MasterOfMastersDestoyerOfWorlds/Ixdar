package ixdar.numeric.cluster;

/**
 * The assignment step of a clustering: which centroid a point belongs to. Swapping it changes what
 * "nearest" means while the centroid update stays the same.
 */
public interface NearestCentroid {

    /** Nearest by squared Euclidean distance, ties to the lower centroid index. */
    NearestCentroid EUCLIDEAN = (points, point, centroids, clusterCount, dimension) -> {
        int best = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int cluster = 0; cluster < clusterCount; cluster++) {
            double distance = 0;
            for (int axis = 0; axis < dimension; axis++) {
                double offset = points[point * dimension + axis] - centroids[cluster * dimension + axis];
                distance += offset * offset;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = cluster;
            }
        }
        return best;
    };

    /**
     * The centroid one point belongs to.
     *
     * @param points       packed point coordinates, {@code dimension} per point
     * @param point        index of the point
     * @param centroids    packed centroid coordinates, {@code dimension} per centroid
     * @param clusterCount number of centroids
     * @param dimension    coordinates per point
     * @return the centroid's index
     */
    int nearest(double[] points, int point, double[] centroids, int clusterCount, int dimension);
}
