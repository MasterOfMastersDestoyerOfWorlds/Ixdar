package unit.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import ixdar.numeric.cluster.EMeans;
import ixdar.numeric.cluster.LloydKMeans;
import ixdar.numeric.cluster.ThreeArmSpirals;
import ixdar.numeric.cluster.VoronoiCells;

/**
 * The e-means port: it recovers separated blobs, one whole-data batch at step 1 is one Lloyd
 * iteration, the run closes on each cluster's last-pass mean, and the spirals and Voronoi edges it
 * is shown with are what they claim.
 */
class EMeansTest {
    private static final int PLANE = 2;
    private static final double BLOB_SPREAD = 0.1;
    private static final double BLOB_OFFSET = 5;
    private static final int POINTS_PER_BLOB = 1000;
    private static final double[] BLOB_CENTRES = { -BLOB_OFFSET, -BLOB_OFFSET, BLOB_OFFSET, -BLOB_OFFSET,
        -BLOB_OFFSET, BLOB_OFFSET, BLOB_OFFSET, BLOB_OFFSET };
    private static final double[] UNIT_CORNERS = { 0, 0, 1, 0, 0, 1, 1, 1 };
    private static final int SPIRAL_POINTS = 5000;
    private static final int SPIRAL_CLUSTERS = 40;
    private static final long SEED = 7;
    private static final double EXACT = 1e-12;
    private static final double NEAR_CENTRE = 0.02;

    @Test
    void separatedBlobsEachGetOneCentroidAtTheirCentre() {
        int blobs = BLOB_CENTRES.length / PLANE;
        Random random = new Random(SEED);
        double[] points = new double[PLANE * blobs * POINTS_PER_BLOB];
        for (int point = 0; point < blobs * POINTS_PER_BLOB; point++) {
            int blob = point % blobs;
            points[PLANE * point] = BLOB_CENTRES[PLANE * blob] + BLOB_SPREAD * random.nextGaussian();
            points[PLANE * point + 1] = BLOB_CENTRES[PLANE * blob + 1] + BLOB_SPREAD * random.nextGaussian();
        }
        EMeans run = new EMeans(PLANE, blobs);
        run.begin(points, blobs * POINTS_PER_BLOB, UNIT_CORNERS);
        while (run.step()) {
            continue;
        }
        boolean[] taken = new boolean[blobs];
        for (int cluster = 0; cluster < blobs; cluster++) {
            int blob = 0;
            for (int other = 1; other < blobs; other++) {
                double here = Math.hypot(run.centroids[PLANE * cluster] - BLOB_CENTRES[PLANE * other],
                        run.centroids[PLANE * cluster + 1] - BLOB_CENTRES[PLANE * other + 1]);
                double held = Math.hypot(run.centroids[PLANE * cluster] - BLOB_CENTRES[PLANE * blob],
                        run.centroids[PLANE * cluster + 1] - BLOB_CENTRES[PLANE * blob + 1]);
                blob = here < held ? other : blob;
            }
            assertFalse(taken[blob], "two centroids in blob " + blob);
            taken[blob] = true;
            assertEquals(BLOB_CENTRES[PLANE * blob], run.centroids[PLANE * cluster], NEAR_CENTRE);
            assertEquals(BLOB_CENTRES[PLANE * blob + 1], run.centroids[PLANE * cluster + 1], NEAR_CENTRE);
        }
        assertTrue(EMeans.meanSquaredError(points, blobs * POINTS_PER_BLOB, run.centroids, blobs, PLANE,
                run.nearest) < PLANE * BLOB_SPREAD * BLOB_SPREAD * 1.1);
    }

    @Test
    void oneWholeDataBatchAtStepOneIsOneLloydIteration() {
        double[] points = ThreeArmSpirals.points(SPIRAL_POINTS, SEED);
        double[] initial = EMeans.sampleCentroids(points, SPIRAL_POINTS, SPIRAL_CLUSTERS, PLANE, SEED);
        EMeans run = new EMeans(PLANE, SPIRAL_CLUSTERS);
        run.epochs = 1;
        run.requestedBatchSize = SPIRAL_POINTS;
        run.requestedAlpha0 = 1;
        run.begin(points, SPIRAL_POINTS, initial);
        assertTrue(run.step());
        assertTrue(run.finished);
        LloydKMeans lloyd = new LloydKMeans(PLANE, SPIRAL_CLUSTERS);
        lloyd.begin(points, SPIRAL_POINTS, initial);
        lloyd.iterate();
        assertArrayEquals(lloyd.centroids, run.centroids, EXACT);
        assertArrayEquals(lloyd.labels, run.labels);
    }

    @Test
    void theRunClosesOnEachClustersLastPassMean() {
        double[] points = ThreeArmSpirals.points(SPIRAL_POINTS, SEED);
        EMeans run = new EMeans(PLANE, SPIRAL_CLUSTERS);
        run.begin(points, SPIRAL_POINTS,
                EMeans.sampleCentroids(points, SPIRAL_POINTS, SPIRAL_CLUSTERS, PLANE, run.seed));
        while (run.step()) {
            continue;
        }
        assertTrue(run.totalSteps > run.epochRows.length, "more than one batch per pass");
        double[] sums = new double[PLANE * SPIRAL_CLUSTERS];
        int[] counts = new int[SPIRAL_CLUSTERS];
        for (int point = 0; point < SPIRAL_POINTS; point++) {
            int cluster = run.labels[point];
            counts[cluster]++;
            sums[PLANE * cluster] += points[PLANE * point];
            sums[PLANE * cluster + 1] += points[PLANE * point + 1];
        }
        for (int cluster = 0; cluster < SPIRAL_CLUSTERS; cluster++) {
            assertTrue(counts[cluster] > 0);
            assertEquals(sums[PLANE * cluster] / counts[cluster], run.centroids[PLANE * cluster], EXACT);
            assertEquals(sums[PLANE * cluster + 1] / counts[cluster], run.centroids[PLANE * cluster + 1], EXACT);
        }
    }

    @Test
    void theSpiralsAreTheSameForTheSameSeed() {
        assertArrayEquals(ThreeArmSpirals.points(SPIRAL_POINTS, SEED), ThreeArmSpirals.points(SPIRAL_POINTS, SEED));
        assertFalse(Arrays.equals(ThreeArmSpirals.points(SPIRAL_POINTS, SEED),
                ThreeArmSpirals.points(SPIRAL_POINTS, SEED + 1)));
    }

    @Test
    void twoCentroidsShareOneEdgeOnTheirBisector() {
        double[] edges = VoronoiCells.edges(new double[] { -0.5, 0, 0.5, 0 }, 2, -1, -1, 1, 1);
        assertEquals(VoronoiCells.FLOATS_PER_SEGMENT, edges.length);
        assertEquals(0, edges[0], EXACT);
        assertEquals(0, edges[2], EXACT);
        assertEquals(2, Math.abs(edges[1] - edges[3]), EXACT);
    }

    @Test
    void aGridOfCentroidsHasOneEdgeBetweenEachAdjacentPair() {
        int side = 3;
        double[] grid = new double[PLANE * side * side];
        for (int row = 0; row < side; row++) {
            for (int column = 0; column < side; column++) {
                grid[PLANE * (row * side + column)] = column;
                grid[PLANE * (row * side + column) + 1] = row;
            }
        }
        double[] edges = VoronoiCells.edges(grid, side * side, -1, -1, side, side);
        int adjacentPairs = 2 * side * (side - 1);
        assertEquals(adjacentPairs * VoronoiCells.FLOATS_PER_SEGMENT, edges.length);
    }
}
