package ixdar.numeric.cluster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * E-means: mini-batch k-means whose centroids are moving averages of their
 * batch means, stepped with a cosine-annealed step and batch size from fitted
 * scaling laws.
 *
 * @see <a href=
 *      "https://github.com/facebookresearch/faiss/tree/main/contrib/e_means">Ported
 *      from faiss contrib/e_means (e_means.py, base.py), MIT license, Copyright
 *      (c) Meta Platforms, Inc. and affiliates; only the update rule, in
 *      doubles, without GPU backends, quantizers or sharding</a>
 * @see <a href="https://arxiv.org/abs/2611.09501">Bessa et al., E-Means:
 *      Scaling Laws for Mini-Batch K-Means, Algorithm 1</a>
 */
public final class EMeans {
    public static final double ALPHA_MIN_FACTOR = 0.1;
    public static final int CHECK_UNUSED_EVERY = 5;
    public static final double INITIAL_USAGE = 1.0 / 65536;
    public static final double SPLIT_EPSILON = 1e-4;
    public static final double UNUSED_THRESHOLD = 0.1;
    public static final int NO_REVIVAL_MAX_DIMENSION = 2;
    public static final int AUTO_QUALITY_MAX_DIMENSION = 16;
    public static final double THROUGHPUT_BATCH_COEFFICIENT = 0.035134;
    public static final double QUALITY_BATCH_COEFFICIENT = 0.00439175;
    public static final double BATCH_CLUSTER_EXPONENT = 0.134749;
    public static final double BATCH_POINT_EXPONENT = 0.876473;
    public static final double BATCH_EPOCH_EXPONENT = 0.813344;
    public static final double STEP_COEFFICIENT = 0.8244;
    public static final double STEP_CLUSTER_EXPONENT = -0.4617;
    public static final double STEP_BATCH_EXPONENT = 0.9003;
    public static final double STEP_POINT_EXPONENT = -0.4498;
    public static final double STEP_EPOCH_EXPONENT = -0.1057;
    public static final double MIN_ALPHA0 = 1e-4;
    public static final long EPOCH_SEED_MULTIPLIER = 1000003L;
    public static final double DEFAULT_EPOCHS = 5;
    public static final long DEFAULT_SEED = 1234;

    /** Coordinates per point. */
    public final int dimension;

    /** Number of centroids. */
    public final int clusterCount;

    /** Passes over the data; a fraction runs that share of a last partial pass. */
    public double epochs = DEFAULT_EPOCHS;

    /** Seed of the starting centroids, each pass's shuffle and the revivals. */
    public long seed = DEFAULT_SEED;

    /** The law that sizes the batch when {@link #requestedBatchSize} is 0. */
    public BatchLaw batchLaw = BatchLaw.AUTO;

    /** Rows per batch, or 0 for the batch law's. */
    public int requestedBatchSize;

    /** Starting step, or NaN for the step law's at the resolved batch size. */
    public double requestedAlpha0 = Double.NaN;

    /**
     * Revive a cluster whose usage falls below this share of the mean; NaN picks 0
     * (never) at dimension 2 or less and 0.1 above, as faiss does.
     */
    public double unusedThreshold = Double.NaN;

    /** The assignment step. */
    public NearestCentroid nearest = NearestCentroid.EUCLIDEAN;

    /** Each point's weight in the batch means and counts, or null to weigh every point 1. */
    public double[] weights;

    /**
     * Centroid coordinates, {@link #dimension} per centroid; the closing means once
     * finished.
     */
    public double[] centroids = new double[0];

    /** Each centroid's moving mass N_j. */
    public double[] usage = new double[0];

    /** Rows per batch this run uses. */
    public int batchSize;

    /** Starting step this run uses. */
    public double alpha0;

    /**
     * Rows each pass reads: whole passes, a rounded fractional pass, then the
     * closing pass.
     */
    public int[] epochRows = new int[0];

    /** Batches in the whole run. */
    public int totalSteps;

    /** Batches applied so far. */
    public int step;

    /** Pass under way. */
    public int epoch;

    /** Rows of the pass under way already read. */
    public int epochRowsDone;

    /** Each point's centroid in the last pass, -1 for a point it did not read. */
    public int[] labels = new int[0];

    /** Sum of squared distances to the moving centroids over each finished pass. */
    public final List<Double> epochObjectives = new ArrayList<>();

    /** Whether the last pass has ended and the closing means are set. */
    public boolean finished;

    private double[] points = new double[0];
    private int pointCount;
    private int[] order = new int[0];
    private double[] batchSums = new double[0];
    private double[] batchCounts = new double[0];
    private double[] passSums;
    private double[] passCounts = new double[0];
    private double passObjective;
    private double revivalThreshold;
    private Random splitRandom = new Random();

    /**
     * An e-means run waiting for {@link #begin}.
     *
     * @param dimension    coordinates per point
     * @param clusterCount number of centroids
     */
    public EMeans(int dimension, int clusterCount) {
        this.dimension = dimension;
        this.clusterCount = clusterCount;
    }

    /**
     * Start a run from given centroids, resolving the batch size and starting step
     * from the laws unless given; nothing moves until {@link #step}.
     *
     * @param points           packed coordinates, {@link #dimension} per point
     * @param pointCount       number of points
     * @param initialCentroids packed starting centroids, copied
     */
    public void begin(double[] points, int pointCount, double[] initialCentroids) {
        this.points = points;
        this.pointCount = pointCount;
        centroids = Arrays.copyOf(initialCentroids, clusterCount * dimension);
        usage = new double[clusterCount];
        Arrays.fill(usage, INITIAL_USAGE);
        batchSums = new double[clusterCount * dimension];
        batchCounts = new double[clusterCount];
        passCounts = new double[clusterCount];
        splitRandom = new Random(seed);
        revivalThreshold = !Double.isNaN(unusedThreshold) ? unusedThreshold
                : dimension <= NO_REVIVAL_MAX_DIMENSION ? 0 : UNUSED_THRESHOLD;
        if (requestedBatchSize > 0) {
            batchSize = Math.min(requestedBatchSize, pointCount);
        } else {
            boolean quality = batchLaw == BatchLaw.QUALITY
                    || batchLaw == BatchLaw.AUTO && dimension <= AUTO_QUALITY_MAX_DIMENSION;
            double raw = (quality ? QUALITY_BATCH_COEFFICIENT : THROUGHPUT_BATCH_COEFFICIENT)
                    * Math.pow(clusterCount, BATCH_CLUSTER_EXPONENT)
                    * Math.pow(pointCount, BATCH_POINT_EXPONENT) * Math.pow(epochs, BATCH_EPOCH_EXPONENT);
            int size = roundPowerOfTwo(raw);
            size = quality ? Math.min(size, pointCount / 2)
                    : Math.min(size, roundPowerOfTwo(pointCount / 2.0));
            batchSize = Math.max(1, Math.min(size, pointCount));
        }
        alpha0 = !Double.isNaN(requestedAlpha0) ? requestedAlpha0
                : Math.min(Math.max(STEP_COEFFICIENT * Math.pow(clusterCount, STEP_CLUSTER_EXPONENT)
                        * Math.pow(batchSize, STEP_BATCH_EXPONENT)
                        * Math.pow(pointCount, STEP_POINT_EXPONENT)
                        * Math.pow(epochs, STEP_EPOCH_EXPONENT), MIN_ALPHA0), 1.0);
        int whole = (int) Math.floor(epochs);
        int remainder = (int) Math.rint((epochs - whole) * pointCount);
        if (whole == 0) {
            epochRows = new int[] { remainder };
        } else {
            epochRows = new int[whole + (remainder > 0 ? 1 : 0)];
            Arrays.fill(epochRows, pointCount);
            if (remainder > 0) {
                epochRows[whole - 1] = remainder;
            }
        }
        totalSteps = 0;
        for (int rows : epochRows) {
            totalSteps += (rows + batchSize - 1) / batchSize;
        }
        labels = new int[pointCount];
        Arrays.fill(labels, -1);
        step = 0;
        epoch = 0;
        finished = false;
        epochObjectives.clear();
        startEpoch();
    }

    /**
     * The cosine schedule of the paper's Equation 5: {@code alpha0} at the first
     * step, annealing to {@link #ALPHA_MIN_FACTOR} of it at the last.
     *
     * @param alpha0     the starting step
     * @param step       steps taken so far
     * @param totalSteps steps in the whole run
     * @return the step to take now
     */
    public static double scheduledStep(double alpha0, int step, int totalSteps) {
        double fraction = ((double) Math.min(step, totalSteps)) / ((double) Math.max(1, totalSteps));
        double cosine = 0.5 * (1.0 + Math.cos(Math.PI * fraction));
        return alpha0 * (ALPHA_MIN_FACTOR + (1.0 - ALPHA_MIN_FACTOR) * cosine);
    }

    /**
     * Apply one batch: assign its rows, move each touched centroid toward its batch
     * mean by the scheduled step, and after the last batch set each centroid to its
     * last-pass mean.
     *
     * @return whether a batch was applied; false once the run has finished
     */
    public boolean step() {
        if (finished) {
            return false;
        }
        int rows = Math.min(batchSize, epochRows[epoch] - epochRowsDone);
        Arrays.fill(batchSums, 0);
        Arrays.fill(batchCounts, 0);
        for (int row = epochRowsDone; row < epochRowsDone + rows; row++) {
            int point = order[row];
            int cluster = nearest.nearest(points, point, centroids, clusterCount, dimension);
            double pointWeight = weights == null ? 1 : weights[point];
            batchCounts[cluster] += pointWeight;
            for (int axis = 0; axis < dimension; axis++) {
                double coordinate = points[point * dimension + axis];
                double offset = coordinate - centroids[cluster * dimension + axis];
                batchSums[cluster * dimension + axis] += pointWeight * coordinate;
                passObjective += pointWeight * offset * offset;
            }
            if (passSums != null) {
                labels[point] = cluster;
            }
        }
        double alpha = scheduledStep(alpha0, step, totalSteps);
        boolean replaceUsage = alpha == 1;
        for (int cluster = 0; cluster < clusterCount; cluster++) {
            passCounts[cluster] += batchCounts[cluster];
            if (passSums != null) {
                for (int axis = 0; axis < dimension; axis++) {
                    passSums[cluster * dimension + axis] += batchSums[cluster * dimension + axis];
                }
            }
            if (!replaceUsage) {
                usage[cluster] *= 1 - alpha;
            }
            double count = batchCounts[cluster];
            if (count == 0) {
                continue;
            }
            double mass = (replaceUsage ? 0 : usage[cluster]) + alpha * count;
            double weight = alpha * count / mass;
            for (int axis = 0; axis < dimension; axis++) {
                int at = cluster * dimension + axis;
                double mean = batchSums[at] / count;
                centroids[at] = weight == 1 ? mean : centroids[at] + weight * (mean - centroids[at]);
            }
            usage[cluster] = mass;
        }
        if ((step + 1) % CHECK_UNUSED_EVERY == 0 && revivalThreshold > 0) {
            double threshold = Arrays.stream(usage).sum() * revivalThreshold / clusterCount;
            double[] keys = new double[clusterCount];
            List<Integer> donors = new ArrayList<>();
            List<Integer> starved = new ArrayList<>();
            for (int cluster = 0; cluster < clusterCount; cluster++) {
                double uniform = Math.max(splitRandom.nextDouble(), Double.MIN_NORMAL);
                if (usage[cluster] < threshold) {
                    starved.add(cluster);
                } else if (usage[cluster] > 0) {
                    donors.add(cluster);
                    keys[cluster] = Math.log(usage[cluster]) - Math.log(-Math.log(uniform));
                }
            }
            donors.sort((first, second) -> Double.compare(keys[second], keys[first]));
            for (int split = 0; split < Math.min(starved.size(), donors.size()); split++) {
                int donor = donors.get(split);
                int child = starved.get(split);
                boolean collapsed = true;
                for (int axis = 0; axis < dimension; axis++) {
                    double offset = SPLIT_EPSILON * (axis % 2 == 0 ? 1 : -1);
                    double base = centroids[donor * dimension + axis];
                    centroids[donor * dimension + axis] = base * (1 - offset);
                    centroids[child * dimension + axis] = base * (1 + offset);
                    collapsed &= base * (1 - offset) == base * (1 + offset);
                }
                if (collapsed) {
                    centroids[child * dimension] = Math.nextUp(centroids[child * dimension]);
                }
                usage[donor] /= 2;
                usage[child] = usage[donor];
            }
        }
        step++;
        epochRowsDone += rows;
        if (epochRowsDone >= epochRows[epoch]) {
            epochObjectives.add(passObjective);
            epoch++;
            if (epoch < epochRows.length) {
                startEpoch();
            } else {
                for (int cluster = 0; cluster < clusterCount; cluster++) {
                    for (int axis = 0; passCounts[cluster] > 0 && axis < dimension; axis++) {
                        int at = cluster * dimension + axis;
                        centroids[at] = passSums[at] / passCounts[cluster];
                    }
                }
                finished = true;
            }
        }
        return true;
    }

    /**
     * Distinct starting centroids: the first {@code clusterCount} points of a
     * seeded shuffle, or every point when there are exactly that many.
     *
     * @param points       packed coordinates
     * @param pointCount   number of points
     * @param clusterCount number of centroids
     * @param dimension    coordinates per point
     * @param seed         shuffle seed
     * @return packed centroids
     */
    public static double[] sampleCentroids(double[] points, int pointCount, int clusterCount,
            int dimension, long seed) {
        int[] shuffled = permutation(pointCount, seed);
        double[] sampled = new double[clusterCount * dimension];
        for (int cluster = 0; cluster < clusterCount; cluster++) {
            int point = pointCount == clusterCount ? cluster : shuffled[cluster];
            System.arraycopy(points, point * dimension, sampled, cluster * dimension, dimension);
        }
        return sampled;
    }

    /**
     * A seeded Fisher-Yates shuffle of {@code [0, count)}.
     *
     * @param count number of indices
     * @param seed  shuffle seed
     * @return the shuffled indices
     */
    public static int[] permutation(int count, long seed) {
        Random random = new Random(seed);
        int[] shuffled = new int[count];
        for (int index = 0; index < count; index++) {
            shuffled[index] = index;
        }
        for (int index = count - 1; index > 0; index--) {
            int swap = random.nextInt(index + 1);
            int held = shuffled[index];
            shuffled[index] = shuffled[swap];
            shuffled[swap] = held;
        }
        return shuffled;
    }

    /**
     * Mean squared distance from each point to its nearest centroid.
     *
     * @param points       packed coordinates
     * @param pointCount   number of points
     * @param centroids    packed centroids
     * @param clusterCount number of centroids
     * @param dimension    coordinates per point
     * @param nearest      the assignment step
     * @return the mean squared error, 0 for no points
     */
    public static double meanSquaredError(double[] points, int pointCount, double[] centroids,
            int clusterCount, int dimension, NearestCentroid nearest) {
        double total = 0;
        for (int point = 0; point < pointCount; point++) {
            int cluster = nearest.nearest(points, point, centroids, clusterCount, dimension);
            for (int axis = 0; axis < dimension; axis++) {
                double offset = points[point * dimension + axis] - centroids[cluster * dimension + axis];
                total += offset * offset;
            }
        }
        return pointCount == 0 ? 0 : total / pointCount;
    }

    private void startEpoch() {
        order = permutation(pointCount, seed * EPOCH_SEED_MULTIPLIER + epoch);
        epochRowsDone = 0;
        passObjective = 0;
        Arrays.fill(passCounts, 0);
        passSums = epoch == epochRows.length - 1 ? new double[clusterCount * dimension] : null;
    }

    private static int roundPowerOfTwo(double value) {
        if (value <= 1) {
            return 1;
        }
        int low = Integer.highestOneBit((int) Math.floor(value));
        int high = 2 * low;
        return ((value / low) < (high / value)) ? low : high;
    }

    /**
     * Passes read so far, fractional within a pass.
     *
     * @return rows read over the point count
     */
    public double passesDone() {
        long rows = epochRowsDone;
        for (int done = 0; done < Math.min(epoch, epochRows.length); done++) {
            rows += epochRows[done];
        }
        if (finished) {
            rows = 0;
            for (int pass : epochRows) {
                rows += pass;
            }
        }
        return rows / ((double) Math.max(1, pointCount));
    }
}
