package ixdar.geometry.mesh.data.analysis.curvature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;

import ixdar.geometry.volume.VoxelGrid;
import ixdar.numeric.DenseLinearSystem;
import ixdar.numeric.sparse.DirectSolver;
import ixdar.numeric.sparse.matrix.NormalMatrix;
import ixdar.numeric.sparse.ordering.OrderingMethod;

/**
 * A multi-level implicit surface F = 0 through oriented points, positive outside, with exact derivatives
 * to third order (OBS04 section 2).
 */
// Each level clusters the points on a lattice half the previous one's step and adds a partition of
// unity of local quadratic approximations of what the levels so far leave, plus compactly supported
// Wendland functions whose regularised weights pin the zero set to the cluster centres (Ohtake,
// Belyaev and Seidel, "A multi-scale approach to 3D scattered data interpolation with compactly
// supported basis functions", SMI 2003). OBS04 blends local linear approximations instead; a partition
// of unity of planes leaves a Hessian error the size of the curvature itself at every level, which
// the curvature derivatives ridges are found from do not survive, so the quadratics of SMI 2003 stay.
public final class ImplicitFit {

    public static final double SUPPORT_IN_CELLS = 3.0;

    public static final double PROJECTION_PRECISION = 1e-4;

    public static final int PROJECTION_STEPS = 12;

    public static final double SMALLEST = 1e-12;

    public static final int VALUE = 0;

    public static final int FIRST = 1;

    public static final int SECOND = 2;

    public static final int THIRD = 3;

    public static final int[] JET_ENTRIES_BY_ORDER = { 1, 4, 13, 40 };

    public static final int JET_LENGTH = 40;

    public static final int GRADIENT_START = 1;

    public static final int HESSIAN_START = 4;

    public static final int THIRD_START = 13;

    // Per centre: its local approximation's value, gradient and Hessian at the centre.
    public static final int LOCAL_LENGTH = 13;

    public static final int NEIGHBOUR_CELLS = 27;

    public static final int LATTICE_BITS = 21;

    public static final long LATTICE_MASK = (1L << LATTICE_BITS) - 1;

    // A local height fit's unknowns: u^2, uv, v^2, u, v and a constant.
    public static final int HEIGHT_UNKNOWNS = 6;

    // Wendland's phi_{3,2}(r) = (1 - r)^6 (35 r^2 + 18 r + 3), divided by 3 so phi(0) = 1, with
    // phi'/r = -56 (5 r + 1)(1 - r)^5, (phi'/r)'/r = 1680 (1 - r)^4 and ((phi'/r)'/r)' = -6720 (1 - r)^3.
    public static final double WENDLAND_SQUARE = 35.0;

    public static final double WENDLAND_LINEAR = 18.0;

    public static final double WENDLAND_CONSTANT = 3.0;

    public static final double WENDLAND_FIRST = -56.0;

    public static final double WENDLAND_FIRST_SLOPE = 5.0;

    public static final double WENDLAND_SECOND = 1680.0;

    public static final double WENDLAND_THIRD = -6720.0;

    public static final int CURVATURE_LENGTH = 2 + 3 + 3 + 2;

    public static final int DIRECTIONS_START = 2;

    public static final int DERIVATIVES_START = 8;

    // Each thread's evaluation scratch: basis, numerator, weights and blend jets, a step and a slope.
    private static final ThreadLocal<double[][]> SCRATCH = ThreadLocal.withInitial(() -> new double[][] {
        new double[JET_LENGTH], new double[JET_LENGTH], new double[JET_LENGTH], new double[JET_LENGTH], new double[3],
        new double[3] });

    // Each thread's candidate list, kept at the longest a lookup has needed.
    private static final ThreadLocal<int[]> NEAR = ThreadLocal.withInitial(() -> new int[NEIGHBOUR_CELLS]);

    /** The levels, coarsest first. */
    public final List<Level> levels = new ArrayList<>();

    /** Points bucketed on a lattice, for those within one step of a place. */
    public static final class Buckets {

        /** The points, packed xyz. */
        public final double[] xyz;

        /** The lattice, one step per cell. */
        public final VoxelGrid lattice;

        /** Start of each cell's run in {@link #members}, one past the last cell at the end. */
        public final int[] cellStart;

        /** Point indices grouped by cell. */
        public final int[] members;

        /**
         * Bucket points.
         *
         * @param xyz   the points, packed xyz
         * @param step  the lattice step, the farthest a query looks
         * @param low   a corner below every point on every axis
         * @param reach how far past {@code low} the points reach on every axis
         */
        public Buckets(double[] xyz, double step, double low, double reach) {
            this.xyz = xyz;
            int side = (int) Math.ceil(reach / step) + 1;
            lattice = new VoxelGrid(new int[] { side, side, side }, new float[] { (float) low, (float) low, (float) low },
                    (float) step);
            int count = xyz.length / 3;
            int[] cellOf = new int[count];
            cellStart = new int[lattice.values.length + 1];
            for (int point = 0; point < count; point++) {
                cellOf[point] = lattice.index((int) ((xyz[point * 3] - low) / step), (int) ((xyz[point * 3 + 1] - low) / step),
                        (int) ((xyz[point * 3 + 2] - low) / step));
                cellStart[cellOf[point] + 1]++;
            }
            for (int index = 0; index + 1 < cellStart.length; index++) {
                cellStart[index + 1] += cellStart[index];
            }
            members = new int[count];
            int[] cursor = Arrays.copyOf(cellStart, cellStart.length - 1);
            for (int point = 0; point < count; point++) {
                members[cursor[cellOf[point]]++] = point;
            }
        }

        /**
         * The points that may lie within one step of a place: those in its cell and the 26 around it.
         *
         * @param place  holds the place's xyz
         * @param offset where it starts in {@code place}
         * @param found  receives the points; replaced by a longer copy when too short
         * @return {@code found} or its longer copy, terminated by -1 when not full
         */
        public int[] near(double[] place, int offset, int[] found) {
            int[] result = found;
            int count = 0;
            int[] at = new int[3];
            for (int axis = 0; axis < 3; axis++) {
                at[axis] = (int) Math.floor((place[offset + axis] - lattice.origin[axis]) / lattice.spacing);
            }
            for (int near = 0; near < NEIGHBOUR_CELLS; near++) {
                int x = at[0] + near % 3 - 1;
                int y = at[1] + near / 3 % 3 - 1;
                int z = at[2] + near / 9 - 1;
                if (x < 0 || y < 0 || z < 0 || x >= lattice.size[0] || y >= lattice.size[1] || z >= lattice.size[2]) {
                    continue;
                }
                int index = lattice.index(x, y, z);
                for (int slot = cellStart[index]; slot < cellStart[index + 1]; slot++) {
                    if (count == result.length) {
                        result = Arrays.copyOf(result, count * 2 + 1);
                    }
                    result[count++] = members[slot];
                }
            }
            if (count < result.length) {
                result[count] = -1;
            }
            return result;
        }

        /**
         * The nearest point to a place within a distance, skipping those a test rejects.
         *
         * @param place  holds the place's xyz
         * @param offset where it starts in {@code place}
         * @param within the farthest a found point may be, at most one lattice step
         * @param skip   rejects points by index, or null to take any
         * @return the point's index, or -1 when none is near enough
         */
        public int nearest(double[] place, int offset, double within, IntPredicate skip) {
            int[] found = near(place, offset, new int[NEIGHBOUR_CELLS]);
            int best = -1;
            double bestSquared = within * within;
            for (int index = 0; index < found.length && found[index] >= 0; index++) {
                int point = found[index];
                if (skip != null && skip.test(point)) {
                    continue;
                }
                double squared = 0.0;
                for (int axis = 0; axis < 3; axis++) {
                    double step = xyz[point * 3 + axis] - place[offset + axis];
                    squared += step * step;
                }
                if (squared < bestSquared) {
                    bestSquared = squared;
                    best = point;
                }
            }
            return best;
        }
    }

    /** One level: its cluster centres, their local approximations and basis weights. */
    public static final class Level {

        /** Radius of the level's basis functions. */
        public final double support;

        /** Cluster centres, packed xyz. */
        public final double[] centreXyz;

        /** Each centre's unit normal, packed xyz. */
        public final double[] centreNormalXyz;

        /** Each centre's local approximation: value, gradient and Hessian at the centre, {@link #LOCAL_LENGTH} each. */
        public final double[] local;

        /** Each centre's basis weight. */
        public double[] weight;

        /** The centres, bucketed one support per cell; rebuilt once they move onto their local fits. */
        public Buckets centres;

        /**
         * Cluster points on a lattice: one centre per occupied cell, its points' mean and mean normal.
         *
         * @param pointXyz  the points, packed xyz
         * @param normalXyz their unit normals, packed xyz
         * @param cell      the lattice step
         * @param low       a corner below every point, on every axis
         * @param reach     how far past {@code low} the points reach on every axis
         */
        public Level(double[] pointXyz, double[] normalXyz, double cell, double low, double reach) {
            Map<Long, Integer> clusterByCell = new HashMap<>();
            List<double[]> sums = new ArrayList<>();
            for (int point = 0; point * 3 < pointXyz.length; point++) {
                // A degenerate fan's normal is not a number; its point says nothing about orientation.
                if (!Double.isFinite(normalXyz[point * 3] + normalXyz[point * 3 + 1] + normalXyz[point * 3 + 2])) {
                    continue;
                }
                long key = 0L;
                for (int axis = 0; axis < 3; axis++) {
                    key = key << LATTICE_BITS | (long) Math.floor((pointXyz[point * 3 + axis] - low) / cell) & LATTICE_MASK;
                }
                double[] sum = sums.get(clusterByCell.computeIfAbsent(key, unused -> {
                    sums.add(new double[3 + 3 + 1]);
                    return sums.size() - 1;
                }));
                for (int axis = 0; axis < 3; axis++) {
                    sum[axis] += pointXyz[point * 3 + axis];
                    sum[3 + axis] += normalXyz[point * 3 + axis];
                }
                sum[3 + 3] += 1.0;
            }
            double[] xyz = new double[sums.size() * 3];
            double[] normals = new double[sums.size() * 3];
            int count = 0;
            for (double[] sum : sums) {
                double length = Math.sqrt(sum[3] * sum[3] + sum[3 + 1] * sum[3 + 1] + sum[3 + 2] * sum[3 + 2]);
                if (length < SMALLEST) {
                    continue;
                }
                for (int axis = 0; axis < 3; axis++) {
                    xyz[count * 3 + axis] = sum[axis] / sum[3 + 3];
                    normals[count * 3 + axis] = sum[3 + axis] / length;
                }
                count++;
            }
            centreXyz = Arrays.copyOf(xyz, count * 3);
            centreNormalXyz = Arrays.copyOf(normals, count * 3);
            local = new double[count * LOCAL_LENGTH];
            weight = new double[count];
            support = SUPPORT_IN_CELLS * cell;
            centres = new Buckets(centreXyz, support, low, reach);
        }

        /**
         * The level's basis function at a scaled distance.
         *
         * @param r distance over {@link #support}
         * @return phi(r), 1 at the centre and 0 from one support on
         */
        public static double basis(double r) {
            double rest3 = (1.0 - r) * (1.0 - r) * (1.0 - r);
            return r >= 1.0 ? 0.0 : rest3 * rest3 * (WENDLAND_SQUARE * r * r + WENDLAND_LINEAR * r
                    + WENDLAND_CONSTANT) / WENDLAND_CONSTANT;
        }
    }

    /**
     * Fit levels through oriented points, coarsest first.
     *
     * @param pointXyz       the points, packed xyz
     * @param normalXyz      their unit normals, packed xyz, outward
     * @param firstCell      the first level's lattice step; each level halves it
     * @param levelCount     how many levels
     * @param regularization OBS04's lambda, added to the basis system's diagonal
     */
    public ImplicitFit(double[] pointXyz, double[] normalXyz, double firstCell, int levelCount, double regularization) {
        double lowest = Double.POSITIVE_INFINITY;
        double highest = Double.NEGATIVE_INFINITY;
        for (double coordinate : pointXyz) {
            lowest = Math.min(lowest, coordinate);
            highest = Math.max(highest, coordinate);
        }
        for (int levelIndex = 0; levelIndex < levelCount; levelIndex++) {
            double cell = firstCell / (1 << levelIndex);
            double low = lowest - SUPPORT_IN_CELLS * cell;
            double reach = highest - lowest + 2.0 * SUPPORT_IN_CELLS * cell;
            Level level = new Level(pointXyz, normalXyz, cell, low, reach);
            Buckets points = new Buckets(pointXyz, level.support, low, reach);
            int count = level.weight.length;
            int fitted = levelIndex;
            // Each centre's local approximation of what the levels so far leave: the signed distance to
            // a quadratic height field over its tangent plane, fitted to the points within its support
            // facing its way and weighted by the basis, less the levels so far to second order.
            IntStream.range(0, count).parallel().forEach(centre -> {
                double[] normal = Arrays.copyOfRange(level.centreNormalXyz, centre * 3, centre * 3 + 3);
                double[] first = Math.abs(normal[0]) < 0.5 ? new double[] { 0.0, -normal[2], normal[1] }
                        : new double[] { -normal[2], 0.0, normal[0] };
                double firstLength = Math.sqrt(first[0] * first[0] + first[1] * first[1] + first[2] * first[2]);
                for (int axis = 0; axis < 3; axis++) {
                    first[axis] /= firstLength;
                }
                double[] second = { normal[1] * first[2] - normal[2] * first[1], normal[2] * first[0] - normal[0] * first[2],
                    normal[0] * first[1] - normal[1] * first[0] };
                float[][] matrix = new float[HEIGHT_UNKNOWNS][HEIGHT_UNKNOWNS];
                float[] rightSide = new float[HEIGHT_UNKNOWNS];
                int used = 0;
                int[] near = points.near(level.centreXyz, centre * 3, new int[NEIGHBOUR_CELLS]);
                for (int index = 0; index < near.length && near[index] >= 0; index++) {
                    int point = near[index];
                    double[] step = new double[3];
                    double facing = 0.0;
                    for (int axis = 0; axis < 3; axis++) {
                        step[axis] = pointXyz[point * 3 + axis] - level.centreXyz[centre * 3 + axis];
                        facing += normalXyz[point * 3 + axis] * normal[axis];
                    }
                    double r = Math.sqrt(step[0] * step[0] + step[1] * step[1] + step[2] * step[2]) / level.support;
                    double weight = Level.basis(r);
                    if (weight <= 0.0 || !(facing > 0.0)) {
                        continue;
                    }
                    // Tangent coordinates over the support, so the normal equations stay well conditioned.
                    double u = (step[0] * first[0] + step[1] * first[1] + step[2] * first[2]) / level.support;
                    double v = (step[0] * second[0] + step[1] * second[1] + step[2] * second[2]) / level.support;
                    double height = (step[0] * normal[0] + step[1] * normal[1] + step[2] * normal[2]) / level.support;
                    float[] row = { (float) (u * u), (float) (u * v), (float) (v * v), (float) u, (float) v, 1f };
                    for (int a = 0; a < HEIGHT_UNKNOWNS; a++) {
                        for (int b = 0; b < HEIGHT_UNKNOWNS; b++) {
                            matrix[a][b] += (float) weight * row[a] * row[b];
                        }
                        rightSide[a] += (float) (weight * row[a] * height);
                    }
                    used++;
                }
                float[] height = used >= HEIGHT_UNKNOWNS ? DenseLinearSystem.solve(matrix, rightSide,
                        HEIGHT_UNKNOWNS) : new float[HEIGHT_UNKNOWNS];
                // The centre moves onto its quadratic, h(0, 0) along the normal: a cluster's mean sits
                // inside a curved patch by an amount that varies cell to cell, and pinning the zero set
                // there would ripple the third derivatives. There D(s) = n.s - (h(e1.s, e2.s) - h(0, 0)),
                // with h's coefficients back in world units.
                double[] at = new double[LOCAL_LENGTH];
                double curvatureScale = 1.0 / level.support;
                for (int axis = 0; axis < 3; axis++) {
                    level.centreXyz[centre * 3 + axis] += height[5] * level.support * normal[axis];
                }
                for (int a = 0; a < 3; a++) {
                    at[GRADIENT_START + a] = normal[a] - height[3] * first[a] - height[4] * second[a];
                    for (int b = 0; b < 3; b++) {
                        at[HESSIAN_START + a * 3 + b] = -curvatureScale * (2.0 * height[0] * first[a] * first[b]
                                + height[1] * (first[a] * second[b] + second[a] * first[b]) + 2.0 * height[2] * second[a]
                                        * second[b]);
                    }
                }
                double[] jet = new double[JET_LENGTH];
                evaluate(fitted, level.centreXyz, centre * 3, SECOND, jet);
                for (int entry = 0; entry < LOCAL_LENGTH; entry++) {
                    level.local[centre * LOCAL_LENGTH + entry] = at[entry] - jet[entry];
                }
            });
            level.centres = new Buckets(level.centreXyz, level.support, low, reach);
            levels.add(level);
            // Weights pinning the zero set to the centres: (Phi + lambda I) c = -(levels so far + locals).
            double[] rightSide = new double[count];
            long[][] keysByRow = new long[count][];
            double[][] valuesByRow = new double[count][];
            IntStream.range(0, count).parallel().forEach(row -> {
                double[] jet = new double[JET_LENGTH];
                evaluate(fitted + 1, level.centreXyz, row * 3, VALUE, jet);
                rightSide[row] = -jet[0];
                int[] near = level.centres.near(level.centreXyz, row * 3, new int[NEIGHBOUR_CELLS]);
                List<Integer> columns = new ArrayList<>();
                for (int index = 0; index < near.length && near[index] >= 0; index++) {
                    if (near[index] > row) {
                        columns.add(near[index]);
                    }
                }
                columns.sort(null);
                keysByRow[row] = new long[columns.size()];
                valuesByRow[row] = new double[columns.size()];
                int entries = 0;
                for (int column : columns) {
                    double squared = 0.0;
                    for (int axis = 0; axis < 3; axis++) {
                        double step = level.centreXyz[column * 3 + axis] - level.centreXyz[row * 3 + axis];
                        squared += step * step;
                    }
                    double value = Level.basis(Math.sqrt(squared) / level.support);
                    if (value > 0.0) {
                        keysByRow[row][entries] = (long) row << NormalMatrix.KEY_ROW_SHIFT | column;
                        valuesByRow[row][entries++] = value;
                    }
                }
                keysByRow[row] = Arrays.copyOf(keysByRow[row], entries);
                valuesByRow[row] = Arrays.copyOf(valuesByRow[row], entries);
            });
            int entries = 0;
            for (long[] keys : keysByRow) {
                entries += keys.length;
            }
            long[] upperKeys = new long[entries];
            double[] upperValues = new double[entries];
            entries = 0;
            for (int row = 0; row < count; row++) {
                System.arraycopy(keysByRow[row], 0, upperKeys, entries, keysByRow[row].length);
                System.arraycopy(valuesByRow[row], 0, upperValues, entries, valuesByRow[row].length);
                entries += keysByRow[row].length;
            }
            double[] diagonal = new double[count];
            Arrays.fill(diagonal, Level.basis(0.0) + regularization);
            level.weight = DirectSolver.solve(new NormalMatrix(diagonal, upperKeys, upperValues, rightSide),
                    new double[count], new boolean[count], OrderingMethod.AMD);
        }
    }

    /**
     * Project a point onto F = 0 (OBS04 eq. 1) and read the principal curvatures there, positive where
     * convex, their directions, and each one's derivative along its direction (eq. 2).
     *
     * @param point the point, moved onto the surface
     * @param out   larger and smaller curvature, their unit directions (xyz each), and the two
     *              derivatives: {@link #CURVATURE_LENGTH} entries
     * @return false when the gradient vanishes and nothing was written
     */
    public boolean curvature(double[] point, double[] out) {
        double[] jet = new double[JET_LENGTH];
        for (int step = 0; step < PROJECTION_STEPS; step++) {
            evaluate(levels.size(), point, 0, FIRST, jet);
            double squared = jet[1] * jet[1] + jet[2] * jet[2] + jet[3] * jet[3];
            if (squared < SMALLEST || Math.abs(jet[0]) < PROJECTION_PRECISION * Math.sqrt(squared)) {
                break;
            }
            for (int axis = 0; axis < 3; axis++) {
                point[axis] -= jet[0] * jet[1 + axis] / squared;
            }
        }
        evaluate(levels.size(), point, 0, THIRD, jet);
        double gradientLength = Math.sqrt(jet[1] * jet[1] + jet[2] * jet[2] + jet[3] * jet[3]);
        if (gradientLength < SMALLEST) {
            return false;
        }
        double[] normal = { jet[1] / gradientLength, jet[2] / gradientLength, jet[3] / gradientLength };
        double[] first = Math.abs(normal[0]) < 0.5 ? new double[] { 0.0, -normal[2], normal[1] }
                : new double[] { -normal[2], 0.0, normal[0] };
        double firstLength = Math.sqrt(first[0] * first[0] + first[1] * first[1] + first[2] * first[2]);
        for (int axis = 0; axis < 3; axis++) {
            first[axis] /= firstLength;
        }
        double[] second = { normal[1] * first[2] - normal[2] * first[1], normal[2] * first[0] - normal[0] * first[2],
            normal[0] * first[1] - normal[1] * first[0] };
        // The shape operator in the tangent basis: [[s0, s1], [s1, s2]] = e_a^T H e_b / |grad F|.
        double[] shape = new double[3];
        for (int entry = 0; entry < 3; entry++) {
            double[] left = entry == 2 ? second : first;
            double[] right = entry == 0 ? first : second;
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    shape[entry] += left[row] * jet[HESSIAN_START + row * 3 + column] * right[column];
                }
            }
            shape[entry] /= gradientLength;
        }
        double mean = (shape[0] + shape[2]) / 2.0;
        double spread = Math.hypot((shape[0] - shape[2]) / 2.0, shape[1]);
        out[0] = mean + spread;
        out[1] = mean - spread;
        // The larger curvature's direction, from whichever row of S - k I is longer.
        double alongFirst = shape[1];
        double alongSecond = out[0] - shape[0];
        if (Math.abs(out[0] - shape[2]) > Math.abs(alongSecond)) {
            alongFirst = out[0] - shape[2];
            alongSecond = shape[1];
        }
        if (Math.abs(alongFirst) + Math.abs(alongSecond) < SMALLEST) {
            alongFirst = 1.0;
            alongSecond = 0.0;
        }
        double length = Math.hypot(alongFirst, alongSecond);
        for (int kind = 0; kind < 2; kind++) {
            int at = DIRECTIONS_START + kind * 3;
            for (int axis = 0; axis < 3; axis++) {
                out[at + axis] = kind == 0 ? (alongFirst * first[axis] + alongSecond * second[axis]) / length
                        : (-alongSecond * first[axis] + alongFirst * second[axis]) / length;
            }
            // dk/ds = (F_ijl t t t - 3 k F_ij t n) / |grad F|: OBS04 prints +3k, but with k = t H t / |grad F|
            // the normal's turn and |grad F|'s change both subtract, as a finite difference confirms.
            double third = 0.0;
            double mixed = 0.0;
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) {
                    mixed += jet[HESSIAN_START + a * 3 + b] * out[at + a] * normal[b];
                    for (int c = 0; c < 3; c++) {
                        third += jet[THIRD_START + a * 9 + b * 3 + c] * out[at + a] * out[at + b] * out[at + c];
                    }
                }
            }
            out[DERIVATIVES_START + kind] = (third - 3.0 * out[kind] * mixed) / gradientLength;
        }
        return true;
    }

    /**
     * The value and derivatives of the first levels at a point: each level's local approximations
     * blended N / W, and its weighted basis sum.
     *
     * @param fitted how many levels to sum
     * @param xyz    holds the point
     * @param offset where the point starts in {@code xyz}
     * @param order  the highest derivative wanted, {@link #VALUE} to {@link #THIRD}
     * @param jet    receives value, gradient, Hessian and third derivatives, row major
     */
    public void evaluate(int fitted, double[] xyz, int offset, int order, double[] jet) {
        Arrays.fill(jet, 0.0);
        int entries = JET_ENTRIES_BY_ORDER[order];
        double[][] scratch = SCRATCH.get();
        double[] basis = scratch[0];
        double[] numerator = scratch[1];
        double[] weights = scratch[2];
        double[] blend = scratch[3];
        double[] step = scratch[4];
        double[] slope = scratch[5];
        int[] near = NEAR.get();
        for (int levelIndex = 0; levelIndex < fitted; levelIndex++) {
            Level level = levels.get(levelIndex);
            Arrays.fill(numerator, 0.0);
            Arrays.fill(weights, 0.0);
            near = level.centres.near(xyz, offset, near);
            double support2 = level.support * level.support;
            for (int index = 0; index < near.length && near[index] >= 0; index++) {
                int centre = near[index];
                double squared = 0.0;
                for (int axis = 0; axis < 3; axis++) {
                    step[axis] = xyz[offset + axis] - level.centreXyz[centre * 3 + axis];
                    squared += step[axis] * step[axis];
                }
                double r = Math.sqrt(squared) / level.support;
                if (r >= 1.0) {
                    continue;
                }
                // Powers of 1 - r by multiplication: Math.pow here was a seventh of a stroke key's time.
                double rest = 1.0 - r;
                double rest3 = rest * rest * rest;
                double rest4 = rest3 * rest;
                basis[0] = rest3 * rest3 * (WENDLAND_SQUARE * r * r + WENDLAND_LINEAR * r + WENDLAND_CONSTANT)
                        / WENDLAND_CONSTANT;
                double radial1 = WENDLAND_FIRST * (WENDLAND_FIRST_SLOPE * r + 1.0) * rest4 * rest / WENDLAND_CONSTANT
                        / support2;
                double radial2 = WENDLAND_SECOND * rest4 / WENDLAND_CONSTANT / (support2 * support2);
                double radial3 = r < SMALLEST ? 0.0
                        : WENDLAND_THIRD * rest3 / WENDLAND_CONSTANT / (r * support2 * support2 * support2);
                // Derivatives are symmetric in their indices: only a <= b <= c are computed, every
                // term below reads only such entries, and the rest are mirrored at the end.
                for (int a = 0; a < 3 && order > VALUE; a++) {
                    basis[GRADIENT_START + a] = radial1 * step[a];
                    for (int b = a; b < 3 && order >= SECOND; b++) {
                        basis[HESSIAN_START + a * 3 + b] = radial2 * step[a] * step[b] + (a == b ? radial1 : 0.0);
                        for (int c = b; c < 3 && order == THIRD; c++) {
                            basis[THIRD_START + a * 9 + b * 3 + c] = radial3 * step[a] * step[b] * step[c] + radial2
                                    * ((a == b ? step[c] : 0.0) + (a == c ? step[b] : 0.0) + (b == c ? step[a] : 0.0));
                        }
                    }
                }
                // The local quadratic g(s) = g0 + g.s + s H s / 2, its gradient g + H s, its Hessian H.
                int at = centre * LOCAL_LENGTH;
                double[] local = level.local;
                double value = local[at];
                for (int a = 0; a < 3; a++) {
                    slope[a] = local[at + GRADIENT_START + a];
                    for (int b = 0; b < 3; b++) {
                        slope[a] += local[at + HESSIAN_START + a * 3 + b] * step[b];
                    }
                    value += (local[at + GRADIENT_START + a] + slope[a]) * step[a] / 2.0;
                }
                // N = g phi, by the product rule; g's third derivatives vanish.
                numerator[0] += value * basis[0];
                for (int a = 0; a < 3 && order > VALUE; a++) {
                    numerator[GRADIENT_START + a] += value * basis[GRADIENT_START + a] + slope[a] * basis[0];
                    for (int b = a; b < 3 && order >= SECOND; b++) {
                        numerator[HESSIAN_START + a * 3 + b] += value * basis[HESSIAN_START + a * 3 + b]
                                + slope[a] * basis[GRADIENT_START + b] + slope[b] * basis[GRADIENT_START + a]
                                + local[at + HESSIAN_START + a * 3 + b] * basis[0];
                        for (int c = b; c < 3 && order == THIRD; c++) {
                            numerator[THIRD_START + a * 9 + b * 3 + c] += value * basis[THIRD_START + a * 9 + b * 3 + c]
                                    + slope[a] * basis[HESSIAN_START + b * 3 + c] + slope[b] * basis[HESSIAN_START + a * 3 + c]
                                    + slope[c] * basis[HESSIAN_START + a * 3 + b]
                                    + local[at + HESSIAN_START + a * 3 + b] * basis[GRADIENT_START + c]
                                    + local[at + HESSIAN_START + a * 3 + c] * basis[GRADIENT_START + b]
                                    + local[at + HESSIAN_START + b * 3 + c] * basis[GRADIENT_START + a];
                        }
                    }
                }
                for (int entry = 0; entry < entries; entry++) {
                    weights[entry] += basis[entry];
                    jet[entry] += level.weight[centre] * basis[entry];
                }
            }
            if (weights[0] < SMALLEST) {
                continue;
            }
            // h = N / W: from N = h W, h_a = (N_a - h W_a) / W, and likewise up to third order.
            double total = weights[0];
            blend[0] = numerator[0] / total;
            for (int a = 0; a < 3 && order > VALUE; a++) {
                blend[GRADIENT_START + a] = (numerator[GRADIENT_START + a] - blend[0] * weights[GRADIENT_START + a]) / total;
            }
            for (int a = 0; a < 3 && order >= SECOND; a++) {
                for (int b = a; b < 3; b++) {
                    blend[HESSIAN_START + a * 3 + b] = (numerator[HESSIAN_START + a * 3 + b]
                            - blend[GRADIENT_START + a] * weights[GRADIENT_START + b]
                            - blend[GRADIENT_START + b] * weights[GRADIENT_START + a]
                            - blend[0] * weights[HESSIAN_START + a * 3 + b]) / total;
                }
            }
            for (int a = 0; a < 3 && order == THIRD; a++) {
                for (int b = a; b < 3; b++) {
                    for (int c = b; c < 3; c++) {
                        blend[THIRD_START + a * 9 + b * 3 + c] = (numerator[THIRD_START + a * 9 + b * 3 + c]
                                - blend[0] * weights[THIRD_START + a * 9 + b * 3 + c]
                                - blend[GRADIENT_START + a] * weights[HESSIAN_START + b * 3 + c]
                                - blend[GRADIENT_START + b] * weights[HESSIAN_START + a * 3 + c]
                                - blend[GRADIENT_START + c] * weights[HESSIAN_START + a * 3 + b]
                                - blend[HESSIAN_START + a * 3 + b] * weights[GRADIENT_START + c]
                                - blend[HESSIAN_START + a * 3 + c] * weights[GRADIENT_START + b]
                                - blend[HESSIAN_START + b * 3 + c] * weights[GRADIENT_START + a]) / total;
                    }
                }
            }
            for (int entry = 0; entry < entries; entry++) {
                jet[entry] += blend[entry];
            }
        }
        NEAR.set(near);
        for (int a = 0; a < 3 && order >= SECOND; a++) {
            for (int b = 0; b < 3; b++) {
                int low = Math.min(a, b);
                int high = Math.max(a, b);
                jet[HESSIAN_START + a * 3 + b] = jet[HESSIAN_START + low * 3 + high];
                for (int c = 0; c < 3 && order == THIRD; c++) {
                    int first = Math.min(low, c);
                    int last = Math.max(high, c);
                    int middle = a + b + c - first - last;
                    jet[THIRD_START + a * 9 + b * 3 + c] = jet[THIRD_START + first * 9 + middle * 3 + last];
                }
            }
        }
    }
}
