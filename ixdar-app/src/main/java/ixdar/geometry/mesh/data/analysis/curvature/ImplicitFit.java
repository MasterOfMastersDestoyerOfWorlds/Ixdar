package ixdar.geometry.mesh.data.analysis.curvature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.volume.VoxelGrid;
import ixdar.numeric.DenseLinearSystem;
import ixdar.numeric.sparse.DirectSolver;
import ixdar.numeric.sparse.matrix.NormalMatrix;
import ixdar.numeric.sparse.ordering.OrderingMethod;

/**
 * A multi-level implicit surface F = 0 through oriented points, positive outside, with exact derivatives
 * to third order (OBS04 section 2).
 */
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

    public static final int LOCAL_LENGTH = 13;

    public static final int NEIGHBOUR_CELLS = 27;

    public static final int LATTICE_BITS = 21;

    public static final long LATTICE_MASK = (1L << LATTICE_BITS) - 1;

    public static final int HEIGHT_UNKNOWNS = 6;

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

    private static final ThreadLocal<double[][]> SCRATCH = ThreadLocal.withInitial(() -> new double[][] {
        new double[JET_LENGTH], new double[JET_LENGTH], new double[JET_LENGTH], new double[JET_LENGTH], new double[3],
        new double[3] });

    private static final ThreadLocal<int[]> NEAR = ThreadLocal.withInitial(() -> new int[NEIGHBOUR_CELLS]);

    /** The levels, coarsest first. */
    public final List<Level> levels = new ArrayList<>();

    /** Points bucketed on a lattice, for those within one step of a place. */
    public static final class Buckets {

        /** The points. */
        public final PackedPoints points;

        /** The lattice, one step per cell. */
        public final VoxelGrid lattice;

        /** Start of each cell's run in {@link #members}, one past the last cell at the end. */
        public final int[] cellStart;

        /** Point indices grouped by cell. */
        public final int[] members;

        /**
         * Bucket points.
         *
         * @param points the points
         * @param step   the lattice step, the farthest a query looks
         * @param low    a corner below every point on every axis
         * @param reach  how far past {@code low} the points reach on every axis
         */
        public Buckets(PackedPoints points, double step, double low, double reach) {
            this.points = points;
            int side = (int) Math.ceil(reach / step) + 1;
            lattice = new VoxelGrid(new int[] { side, side, side }, new Vector3f((float) low),
                    (float) step);
            int count = points.size();
            int[] cellOf = new int[count];
            cellStart = new int[lattice.values.length + 1];
            for (int point = 0; point < count; point++) {
                cellOf[point] = lattice.index((int) ((points.get(point, 0) - low) / step),
                        (int) ((points.get(point, 1) - low) / step), (int) ((points.get(point, 2) - low) / step));
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
         * @param place the place
         * @param found receives the points; replaced by a longer copy when too short
         * @return {@code found} or its longer copy, terminated by -1 when not full
         */
        public int[] near(Vector3f place, int[] found) {
            int[] result = found;
            int count = 0;
            int[] at = new int[3];
            for (int axis = 0; axis < 3; axis++) {
                at[axis] = (int) Math.floor(((double) place.get(axis) - lattice.origin.get(axis)) / lattice.spacing);
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
         * @param place  the place
         * @param within the farthest a found point may be, at most one lattice step
         * @param skip   rejects points by index, or null to take any
         * @return the point's index, or -1 when none is near enough
         */
        public int nearest(Vector3f place, double within, IntPredicate skip) {
            int[] found = near(place, new int[NEIGHBOUR_CELLS]);
            int best = -1;
            double bestSquared = within * within;
            for (int index = 0; index < found.length && found[index] >= 0; index++) {
                int point = found[index];
                if (skip != null && skip.test(point)) {
                    continue;
                }
                double squared = points.distanceSquared(point, place);
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

        /** Cluster centres. */
        public final PackedPoints centreXyz;

        /** Each centre's unit normal. */
        public final PackedPoints centreNormalXyz;

        /** Each centre's local approximation: value, gradient and Hessian at the centre, {@link #LOCAL_LENGTH} each. */
        public final double[] local;

        /** Each centre's basis weight. */
        public double[] weight;

        /** The centres, bucketed one support per cell; rebuilt once they move onto their local fits. */
        public Buckets centres;

        /**
         * Cluster points on a lattice: one centre per occupied cell, its points' mean and mean normal.
         *
         * @param pointXyz  the points
         * @param normalXyz their unit normals
         * @param cell      the lattice step
         * @param low       a corner below every point, on every axis
         * @param reach     how far past {@code low} the points reach on every axis
         */
        public Level(PackedPoints pointXyz, PackedPoints normalXyz, double cell, double low, double reach) {
            Map<Long, Integer> clusterByCell = new HashMap<>();
            List<double[]> sums = new ArrayList<>();
            for (int point = 0; point < pointXyz.size(); point++) {
                if (!Double.isFinite((double) normalXyz.get(point, 0) + normalXyz.get(point, 1) + normalXyz.get(point, 2))) {
                    continue;
                }
                long key = 0L;
                for (int axis = 0; axis < 3; axis++) {
                    key = key << LATTICE_BITS | (long) Math.floor((pointXyz.get(point, axis) - low) / cell) & LATTICE_MASK;
                }
                double[] sum = sums.get(clusterByCell.computeIfAbsent(key, unused -> {
                    sums.add(new double[3 + 3 + 1]);
                    return sums.size() - 1;
                }));
                for (int axis = 0; axis < 3; axis++) {
                    sum[axis] += pointXyz.get(point, axis);
                    sum[3 + axis] += normalXyz.get(point, axis);
                }
                sum[3 + 3] += 1.0;
            }
            centreXyz = new PackedPoints();
            centreNormalXyz = new PackedPoints();
            for (double[] sum : sums) {
                double length = Math.sqrt(sum[3] * sum[3] + sum[3 + 1] * sum[3 + 1] + sum[3 + 2] * sum[3 + 2]);
                if (length < SMALLEST) {
                    continue;
                }
                double members = sum[3 + 3];
                centreXyz.append((float) (sum[0] / members), (float) (sum[1] / members), (float) (sum[2] / members));
                centreNormalXyz.append((float) (sum[3] / length), (float) (sum[3 + 1] / length),
                        (float) (sum[3 + 2] / length));
            }
            int count = centreXyz.size();
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
     * @param pointXyz       the points
     * @param normalXyz      their unit normals, outward
     * @param firstCell      the first level's lattice step; each level halves it
     * @param levelCount     how many levels
     * @param regularization OBS04's lambda, added to the basis system's diagonal
     */
    public ImplicitFit(PackedPoints pointXyz, PackedPoints normalXyz, double firstCell, int levelCount,
            double regularization) {
        PackedPoints box = pointXyz.bounds();
        double lowest = Math.min(box.get(0, 0), Math.min(box.get(0, 1), box.get(0, 2)));
        double highest = Math.max(box.get(1, 0), Math.max(box.get(1, 1), box.get(1, 2)));
        for (int levelIndex = 0; levelIndex < levelCount; levelIndex++) {
            double cell = firstCell / (1 << levelIndex);
            double low = lowest - SUPPORT_IN_CELLS * cell;
            double reach = highest - lowest + 2.0 * SUPPORT_IN_CELLS * cell;
            Level level = new Level(pointXyz, normalXyz, cell, low, reach);
            Buckets points = new Buckets(pointXyz, level.support, low, reach);
            int count = level.weight.length;
            int fitted = levelIndex;
            IntStream.range(0, count).parallel().forEach(centre -> {
                double[] normal = { level.centreNormalXyz.get(centre, 0), level.centreNormalXyz.get(centre, 1),
                    level.centreNormalXyz.get(centre, 2) };
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
                Vector3f centreAt = level.centreXyz.at(centre);
                int[] near = points.near(centreAt, new int[NEIGHBOUR_CELLS]);
                for (int index = 0; index < near.length && near[index] >= 0; index++) {
                    int point = near[index];
                    double[] step = new double[3];
                    double facing = 0.0;
                    for (int axis = 0; axis < 3; axis++) {
                        step[axis] = (double) pointXyz.get(point, axis) - centreAt.get(axis);
                        facing += normalXyz.get(point, axis) * normal[axis];
                    }
                    double r = Math.sqrt(step[0] * step[0] + step[1] * step[1] + step[2] * step[2]) / level.support;
                    double weight = Level.basis(r);
                    if (weight <= 0.0 || !(facing > 0.0)) {
                        continue;
                    }
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
                double[] at = new double[LOCAL_LENGTH];
                double curvatureScale = 1.0 / level.support;
                double lift = height[5] * level.support;
                centreAt.set((float) (centreAt.x + lift * normal[0]), (float) (centreAt.y + lift * normal[1]),
                        (float) (centreAt.z + lift * normal[2]));
                level.centreXyz.set(centre, centreAt);
                for (int a = 0; a < 3; a++) {
                    at[GRADIENT_START + a] = normal[a] - height[3] * first[a] - height[4] * second[a];
                    for (int b = 0; b < 3; b++) {
                        at[HESSIAN_START + a * 3 + b] = -curvatureScale * (2.0 * height[0] * first[a] * first[b]
                                + height[1] * (first[a] * second[b] + second[a] * first[b]) + 2.0 * height[2] * second[a]
                                        * second[b]);
                    }
                }
                double[] jet = new double[JET_LENGTH];
                evaluate(fitted, centreAt, SECOND, jet);
                for (int entry = 0; entry < LOCAL_LENGTH; entry++) {
                    level.local[centre * LOCAL_LENGTH + entry] = at[entry] - jet[entry];
                }
            });
            level.centres = new Buckets(level.centreXyz, level.support, low, reach);
            levels.add(level);
            double[] rightSide = new double[count];
            long[][] keysByRow = new long[count][];
            double[][] valuesByRow = new double[count][];
            IntStream.range(0, count).parallel().forEach(row -> {
                double[] jet = new double[JET_LENGTH];
                Vector3f rowAt = level.centreXyz.at(row);
                evaluate(fitted + 1, rowAt, VALUE, jet);
                rightSide[row] = -jet[0];
                int[] near = level.centres.near(rowAt, new int[NEIGHBOUR_CELLS]);
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
                    double squared = level.centreXyz.distanceSquared(column, rowAt);
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
    public boolean curvature(Vector3f point, double[] out) {
        double[] jet = new double[JET_LENGTH];
        for (int step = 0; step < PROJECTION_STEPS; step++) {
            evaluate(levels.size(), point, FIRST, jet);
            double squared = jet[1] * jet[1] + jet[2] * jet[2] + jet[3] * jet[3];
            if (squared < SMALLEST || Math.abs(jet[0]) < PROJECTION_PRECISION * Math.sqrt(squared)) {
                break;
            }
            point.set((float) (point.x - jet[0] * jet[1] / squared), (float) (point.y - jet[0] * jet[2] / squared),
                    (float) (point.z - jet[0] * jet[3] / squared));
        }
        evaluate(levels.size(), point, THIRD, jet);
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
     * @param point  the point
     * @param order  the highest derivative wanted, {@link #VALUE} to {@link #THIRD}
     * @param jet    receives value, gradient, Hessian and third derivatives, row major
     */
    public void evaluate(int fitted, Vector3f point, int order, double[] jet) {
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
            near = level.centres.near(point, near);
            double support2 = level.support * level.support;
            for (int index = 0; index < near.length && near[index] >= 0; index++) {
                int centre = near[index];
                double squared = 0.0;
                for (int axis = 0; axis < 3; axis++) {
                    step[axis] = (double) point.get(axis) - level.centreXyz.get(centre, axis);
                    squared += step[axis] * step[axis];
                }
                double r = Math.sqrt(squared) / level.support;
                if (r >= 1.0) {
                    continue;
                }
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
