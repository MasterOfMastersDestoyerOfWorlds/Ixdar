package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.solver.InteriorPointQp;
import ixdar.geometry.mesh.quadlayout.solver.NormalMatrix;

/**
 * {@link InteriorPointQp} correctness: hand-solvable projections and a random
 * convex QP checked against exhaustive active-set enumeration, asserting primal
 * feasibility, KKT satisfaction, and objective optimality on both Cholesky
 * backends (the forced pure-Java path always runs; the default path uses
 * PARDISO when it loads).
 */
public final class InteriorPointQpTest {

    private static final double SOLUTION_TOLERANCE = 1.0e-6;
    private static final double FEASIBILITY_TOLERANCE = 1.0e-7;
    private static final double ENUMERATION_TOLERANCE = 1.0e-9;
    private static final long RANDOM_SEED = 987654321L;
    private static final int RANDOM_DIMENSION = 5;
    private static final int RANDOM_CONSTRAINT_COUNT = 3;

    /**
     * Identity-Hessian QP {@code min ½‖x‖² − 1'x} with one constraint
     * {@code x₀ + x₁ ≥ 3}: the unconstrained optimum (1, 1) projects onto the
     * constraint at (1.5, 1.5) with multiplier 0.5.
     *
     * @param forcePureJava whether to force the EJML backend
     */
    private static void activeConstraintProjects(boolean forcePureJava) {
        NormalMatrix hessian = identityHessian(2, new double[] { 1.0, 1.0 });
        InteriorPointQp solver = new InteriorPointQp(hessian,
                new int[][] { { 0, 1 } },
                new double[][] { { 1.0, 1.0 } },
                new double[] { 3.0 });
        solver.forcePureJavaBackend = forcePureJava;
        double[] x = new double[2];
        solver.solve(x);
        assertTrue(solver.converged, "solver did not converge");
        assertEquals(1.5, x[0], SOLUTION_TOLERANCE);
        assertEquals(1.5, x[1], SOLUTION_TOLERANCE);
        assertEquals(0.5, solver.multiplier[0], SOLUTION_TOLERANCE);
    }

    /**
     * Same QP with the constraint moved to {@code x₀ + x₁ ≥ 1}: inactive, so the
     * unconstrained optimum (1, 1) stands and the multiplier vanishes.
     *
     * @param forcePureJava whether to force the EJML backend
     */
    private static void inactiveConstraintKeepsUnconstrainedOptimum(boolean forcePureJava) {
        NormalMatrix hessian = identityHessian(2, new double[] { 1.0, 1.0 });
        InteriorPointQp solver = new InteriorPointQp(hessian,
                new int[][] { { 0, 1 } },
                new double[][] { { 1.0, 1.0 } },
                new double[] { 1.0 });
        solver.forcePureJavaBackend = forcePureJava;
        double[] x = new double[2];
        solver.solve(x);
        assertTrue(solver.converged, "solver did not converge");
        assertEquals(1.0, x[0], SOLUTION_TOLERANCE);
        assertEquals(1.0, x[1], SOLUTION_TOLERANCE);
        assertEquals(0.0, solver.multiplier[0], SOLUTION_TOLERANCE);
    }

    /**
     * Random strictly convex QP: the interior-point solution must match the
     * optimum found by enumerating every constraint subset as an equality set,
     * and satisfy feasibility and stationarity.
     *
     * @param forcePureJava whether to force the EJML backend
     */
    private static void randomQpMatchesEnumeration(boolean forcePureJava) {
        Random random = new Random(RANDOM_SEED);
        int n = RANDOM_DIMENSION;
        int m = RANDOM_CONSTRAINT_COUNT;
        double[][] denseHessian = randomSpdMatrix(random, n);
        double[] linear = new double[n];
        for (int row = 0; row < n; row++) {
            linear[row] = random.nextDouble() * 2.0 - 1.0;
        }
        double[] unconstrained = solveDense(kktMatrixOf(denseHessian, new double[0][], 0),
                appended(linear, new double[0]));
        double[][] rows = new double[m][n];
        double[] bounds = new double[m];
        int[][] rowDofs = new int[m][n];
        for (int constraint = 0; constraint < m; constraint++) {
            double rowDotUnconstrained = 0.0;
            for (int column = 0; column < n; column++) {
                rows[constraint][column] = random.nextDouble() * 2.0 - 1.0;
                rowDofs[constraint][column] = column;
                rowDotUnconstrained += rows[constraint][column] * unconstrained[column];
            }
            // Bounds straddle the unconstrained optimum so some constraints bind.
            bounds[constraint] = rowDotUnconstrained + random.nextDouble() - 0.25;
        }

        double[] reference = enumerateOptimum(denseHessian, linear, rows, bounds);
        assertNotNull(reference, "active-set enumeration found no feasible KKT point");

        NormalMatrix hessian = denseToNormalMatrix(denseHessian, linear);
        InteriorPointQp solver = new InteriorPointQp(hessian, rowDofs, rows, bounds);
        solver.forcePureJavaBackend = forcePureJava;
        double[] x = new double[n];
        solver.solve(x);
        assertTrue(solver.converged, "solver did not converge");
        for (int constraint = 0; constraint < m; constraint++) {
            double value = 0.0;
            for (int column = 0; column < n; column++) {
                value += rows[constraint][column] * x[column];
            }
            assertTrue(value >= bounds[constraint] - FEASIBILITY_TOLERANCE,
                    "constraint " + constraint + " violated by " + (bounds[constraint] - value));
        }
        for (int row = 0; row < n; row++) {
            double stationarity = -linear[row];
            for (int column = 0; column < n; column++) {
                stationarity += denseHessian[row][column] * x[column];
            }
            for (int constraint = 0; constraint < m; constraint++) {
                stationarity -= solver.multiplier[constraint] * rows[constraint][row];
            }
            assertEquals(0.0, stationarity, SOLUTION_TOLERANCE,
                    "KKT stationarity residual at row " + row);
        }
        double objectiveGap = objective(denseHessian, linear, x)
                - objective(denseHessian, linear, reference);
        assertTrue(Math.abs(objectiveGap) < SOLUTION_TOLERANCE,
                "objective gap to enumerated optimum: " + objectiveGap);
        for (int row = 0; row < n; row++) {
            assertEquals(reference[row], x[row], SOLUTION_TOLERANCE,
                    "solution mismatch at row " + row);
        }
    }

    @Test
    public void activeConstraintProjectsDefaultBackend() {
        activeConstraintProjects(false);
    }

    @Test
    public void activeConstraintProjectsPureJavaBackend() {
        activeConstraintProjects(true);
    }

    @Test
    public void inactiveConstraintDefaultBackend() {
        inactiveConstraintKeepsUnconstrainedOptimum(false);
    }

    @Test
    public void inactiveConstraintPureJavaBackend() {
        inactiveConstraintKeepsUnconstrainedOptimum(true);
    }

    @Test
    public void randomQpDefaultBackend() {
        randomQpMatchesEnumeration(false);
    }

    @Test
    public void randomQpPureJavaBackend() {
        randomQpMatchesEnumeration(true);
    }

    /**
     * Identity-Hessian {@link NormalMatrix} with the given linear term.
     *
     * @param n      dimension
     * @param linear linear term b
     * @return the assembled system
     */
    private static NormalMatrix identityHessian(int n, double[] linear) {
        double[] diagonal = new double[n];
        Arrays.fill(diagonal, 1.0);
        return new NormalMatrix(diagonal, new HashMap<>(), linear);
    }

    /**
     * Random symmetric positive definite matrix via diagonal dominance.
     *
     * @param random seeded source
     * @param n      dimension
     * @return dense SPD matrix
     */
    private static double[][] randomSpdMatrix(Random random, int n) {
        double[][] matrix = new double[n][n];
        for (int row = 0; row < n; row++) {
            for (int column = row + 1; column < n; column++) {
                double value = random.nextDouble() * 2.0 - 1.0;
                matrix[row][column] = value;
                matrix[column][row] = value;
            }
        }
        for (int row = 0; row < n; row++) {
            double offDiagonalSum = 0.0;
            for (int column = 0; column < n; column++) {
                if (column != row) {
                    offDiagonalSum += Math.abs(matrix[row][column]);
                }
            }
            matrix[row][row] = offDiagonalSum + 1.0;
        }
        return matrix;
    }

    /**
     * Convert a dense SPD matrix and linear term into the sparse
     * {@link NormalMatrix} form the solver consumes.
     *
     * @param dense  dense SPD matrix
     * @param linear linear term b, stored as the right-hand side
     * @return the sparse system
     */
    private static NormalMatrix denseToNormalMatrix(double[][] dense, double[] linear) {
        int n = dense.length;
        double[] diagonal = new double[n];
        Map<Long, Double> upper = new HashMap<>();
        for (int row = 0; row < n; row++) {
            diagonal[row] = dense[row][row];
            for (int column = row + 1; column < n; column++) {
                if (dense[row][column] != 0.0) {
                    upper.put(((long) row << NormalMatrix.KEY_ROW_SHIFT) | column,
                            dense[row][column]);
                }
            }
        }
        return new NormalMatrix(diagonal, upper, linear);
    }

    /**
     * Optimum of the QP by exhaustive active-set enumeration: for every subset
     * of constraints treated as equalities, solve the equality KKT system and
     * accept the unique candidate that is primal feasible with non-negative
     * multipliers.
     *
     * @param hessian dense Hessian H
     * @param linear  linear term b
     * @param rows    constraint rows A
     * @param bounds  constraint bounds c
     * @return the optimal x, or null if no subset yields a feasible KKT point
     */
    private static double[] enumerateOptimum(double[][] hessian, double[] linear,
            double[][] rows, double[] bounds) {
        int n = hessian.length;
        int m = rows.length;
        for (int subset = 0; subset < (1 << m); subset++) {
            int activeSize = Integer.bitCount(subset);
            double[][] activeRows = new double[activeSize][];
            double[] activeBounds = new double[activeSize];
            int cursor = 0;
            for (int constraint = 0; constraint < m; constraint++) {
                if ((subset & (1 << constraint)) != 0) {
                    activeRows[cursor] = rows[constraint];
                    activeBounds[cursor] = bounds[constraint];
                    cursor++;
                }
            }
            double[] solution = solveDense(kktMatrixOf(hessian, activeRows, activeSize),
                    appended(linear, activeBounds));
            if (solution == null) {
                continue;
            }
            boolean acceptable = true;
            for (int active = 0; active < activeSize && acceptable; active++) {
                acceptable = solution[n + active] >= -ENUMERATION_TOLERANCE;
            }
            for (int constraint = 0; constraint < m && acceptable; constraint++) {
                double value = 0.0;
                for (int column = 0; column < n; column++) {
                    value += rows[constraint][column] * solution[column];
                }
                acceptable = value >= bounds[constraint] - ENUMERATION_TOLERANCE;
            }
            if (acceptable) {
                return Arrays.copyOf(solution, n);
            }
        }
        return null;
    }

    /**
     * The symmetric-indefinite equality KKT matrix {@code [H A'; A 0]}.
     *
     * @param hessian    dense Hessian H
     * @param activeRows equality rows A
     * @param activeSize number of equality rows
     * @return the dense KKT matrix
     */
    private static double[][] kktMatrixOf(double[][] hessian, double[][] activeRows,
            int activeSize) {
        int n = hessian.length;
        double[][] kkt = new double[n + activeSize][n + activeSize];
        for (int row = 0; row < n; row++) {
            System.arraycopy(hessian[row], 0, kkt[row], 0, n);
        }
        for (int active = 0; active < activeSize; active++) {
            for (int column = 0; column < n; column++) {
                kkt[column][n + active] = -activeRows[active][column];
                kkt[n + active][column] = activeRows[active][column];
            }
        }
        return kkt;
    }

    /**
     * Concatenate two vectors.
     *
     * @param first  leading entries
     * @param second trailing entries
     * @return {@code [first, second]}
     */
    private static double[] appended(double[] first, double[] second) {
        double[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    /**
     * Dense Gaussian elimination with partial pivoting.
     *
     * @param matrix square system matrix; a copy is factored
     * @param rhs    right-hand side
     * @return the solution, or null if the matrix is singular
     */
    private static double[] solveDense(double[][] matrix, double[] rhs) {
        int n = rhs.length;
        double[][] work = new double[n][];
        for (int row = 0; row < n; row++) {
            work[row] = matrix[row].clone();
        }
        double[] solution = rhs.clone();
        for (int pivot = 0; pivot < n; pivot++) {
            int bestRow = pivot;
            for (int row = pivot + 1; row < n; row++) {
                if (Math.abs(work[row][pivot]) > Math.abs(work[bestRow][pivot])) {
                    bestRow = row;
                }
            }
            if (Math.abs(work[bestRow][pivot]) < ENUMERATION_TOLERANCE) {
                return null;
            }
            double[] swapRow = work[pivot];
            work[pivot] = work[bestRow];
            work[bestRow] = swapRow;
            double swapValue = solution[pivot];
            solution[pivot] = solution[bestRow];
            solution[bestRow] = swapValue;
            for (int row = pivot + 1; row < n; row++) {
                double factor = work[row][pivot] / work[pivot][pivot];
                if (factor == 0.0) {
                    continue;
                }
                for (int column = pivot; column < n; column++) {
                    work[row][column] -= factor * work[pivot][column];
                }
                solution[row] -= factor * solution[pivot];
            }
        }
        for (int row = n - 1; row >= 0; row--) {
            double value = solution[row];
            for (int column = row + 1; column < n; column++) {
                value -= work[row][column] * solution[column];
            }
            solution[row] = value / work[row][row];
        }
        return solution;
    }

    /**
     * QP objective {@code ½x'Hx − b'x}.
     *
     * @param hessian dense Hessian H
     * @param linear  linear term b
     * @param x       evaluation point
     * @return the objective value
     */
    private static double objective(double[][] hessian, double[] linear, double[] x) {
        double quadratic = 0.0;
        for (int row = 0; row < x.length; row++) {
            for (int column = 0; column < x.length; column++) {
                quadratic += x[row] * hessian[row][column] * x[column];
            }
        }
        double linearTerm = 0.0;
        for (int row = 0; row < x.length; row++) {
            linearTerm += linear[row] * x[row];
        }
        return quadratic / 2.0 - linearTerm;
    }
}
