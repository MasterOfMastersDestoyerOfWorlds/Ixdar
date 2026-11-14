package ixdar.numeric.cluster;

import java.util.Arrays;

/**
 * The edges of the planar Voronoi diagram of a set of centroids inside a box: the boundaries
 * between clusters that nearest-centroid assignment draws, each shared edge once.
 */
public final class VoronoiCells {
    public static final int FLOATS_PER_SEGMENT = 4;

    private VoronoiCells() {
    }

    /**
     * Every cell edge between two centroids, found by clipping the box to each centroid's half
     * planes, nearest neighbours first, until no farther centroid can cut the cell. Box sides are
     * left out.
     *
     * @param centroids    packed xy, two per centroid
     * @param clusterCount number of centroids
     * @param minX         box left
     * @param minY         box bottom
     * @param maxX         box right
     * @param maxY         box top
     * @return packed segments {@code x0, y0, x1, y1}
     */
    public static double[] edges(double[] centroids, int clusterCount, double minX, double minY,
            double maxX, double maxY) {
        double[] segments = new double[FLOATS_PER_SEGMENT * 3 * Math.max(1, clusterCount)];
        int segmentFloats = 0;
        Integer[] byDistance = new Integer[clusterCount];
        double[] distance = new double[clusterCount];
        for (int cell = 0; cell < clusterCount; cell++) {
            double siteX = centroids[2 * cell];
            double siteY = centroids[2 * cell + 1];
            for (int other = 0; other < clusterCount; other++) {
                byDistance[other] = other;
                distance[other] = Math.hypot(centroids[2 * other] - siteX, centroids[2 * other + 1] - siteY);
            }
            Arrays.sort(byDistance, (first, second) -> Double.compare(distance[first], distance[second]));
            double[] cellX = { minX, maxX, maxX, minX };
            double[] cellY = { minY, minY, maxY, maxY };
            int[] cellNeighbour = { -1, -1, -1, -1 };
            int corners = cellX.length;
            for (int rank = 0; rank < clusterCount && corners > 0; rank++) {
                int other = byDistance[rank];
                if (other == cell || distance[other] == 0) {
                    continue;
                }
                double reach = 0;
                for (int corner = 0; corner < corners; corner++) {
                    reach = Math.max(reach, Math.hypot(cellX[corner] - siteX, cellY[corner] - siteY));
                }
                if (distance[other] > 2 * reach) {
                    break;
                }
                // Keep the side of the bisector nearer this cell's centroid: (p - mid) . (other - site) <= 0.
                double normalX = centroids[2 * other] - siteX;
                double normalY = centroids[2 * other + 1] - siteY;
                double midX = (centroids[2 * other] + siteX) / 2;
                double midY = (centroids[2 * other + 1] + siteY) / 2;
                double[] clippedX = new double[corners + 2];
                double[] clippedY = new double[corners + 2];
                int[] clippedNeighbour = new int[corners + 2];
                int kept = 0;
                for (int corner = 0; corner < corners; corner++) {
                    int next = (corner + 1) % corners;
                    double sideHere = (cellX[corner] - midX) * normalX + (cellY[corner] - midY) * normalY;
                    double sideNext = (cellX[next] - midX) * normalX + (cellY[next] - midY) * normalY;
                    if (sideHere <= 0) {
                        clippedX[kept] = cellX[corner];
                        clippedY[kept] = cellY[corner];
                        clippedNeighbour[kept++] = cellNeighbour[corner];
                    }
                    if (sideHere <= 0 != sideNext <= 0) {
                        double share = sideHere / (sideHere - sideNext);
                        clippedX[kept] = cellX[corner] + share * (cellX[next] - cellX[corner]);
                        clippedY[kept] = cellY[corner] + share * (cellY[next] - cellY[corner]);
                        clippedNeighbour[kept++] = sideHere <= 0 ? other : cellNeighbour[corner];
                    }
                }
                cellX = clippedX;
                cellY = clippedY;
                cellNeighbour = clippedNeighbour;
                corners = kept;
            }
            for (int corner = 0; corner < corners; corner++) {
                if (cellNeighbour[corner] <= cell) {
                    continue;
                }
                if (segmentFloats + FLOATS_PER_SEGMENT > segments.length) {
                    segments = Arrays.copyOf(segments, 2 * segments.length);
                }
                int next = (corner + 1) % corners;
                segments[segmentFloats++] = cellX[corner];
                segments[segmentFloats++] = cellY[corner];
                segments[segmentFloats++] = cellX[next];
                segments[segmentFloats++] = cellY[next];
            }
        }
        return Arrays.copyOf(segments, segmentFloats);
    }
}
