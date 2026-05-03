package ixdar.geometry.mesh.quadlayout;

import java.util.ArrayDeque;
import java.util.Arrays;

import ixdar.geometry.mesh.quadlayout.solver.SparseMatrix;

/**
 * Adaptive solver ladder for the mixed-integer systems described by
 * Bommes-Zimmer-Kobbelt 2009, Section 2.1.
 *
 * <p>
 * BZK09 solves a quadratic mixed-integer problem by repeatedly rounding one
 * integer variable, holding it fixed, and updating the remaining continuous
 * minimizer. Their adaptive strategy is:
 *
 * <ol>
 * <li>Start with a local Gauss-Seidel update seeded by the nonzero entries of
 * the rounded variable's matrix row.</li>
 * <li>If the local update does not converge, fall back to a global conjugate
 * gradient solve, warm-started from the previous solution.</li>
 * <li>If CG also fails, use a direct sparse solve.</li>
 * </ol>
 *
 * <p>
 * This class intentionally does not know about cross fields, period jumps, or
 * parametrization variables. It operates on a symmetric positive semi-definite
 * matrix plus a right-hand side, with caller-specified fixed variables. That
 * keeps it usable for both BZK09 cross-field smoothing and the later seamless
 * parametrization step.
 */
public final class AdaptiveSolver {

    private AdaptiveSolver() {
    }

    /**
     * Sparse row-access interface used by {@link AdaptiveSolver}.
     *
     * <p>
     * The matrix is expected to be symmetric for the CG/direct fallback to match
     * BZK09's normal-equation systems. Diagonal entries are supplied through
     * {@link #diag(int)}; the row-entry range is expected to contain off-diagonal
     * entries only.
     */
    public interface Matrix {
        /** @return number of rows and columns */
        int size();

        /** @return diagonal coefficient A[row,row] */
        double diag(int row);

        /** @return first row-entry cursor, inclusive */
        int rowStart(int row);

        /** @return last row-entry cursor, exclusive */
        int rowEnd(int row);

        /** @return column index for row-entry cursor */
        int column(int cursor);

        /** @return coefficient value for row-entry cursor */
        double value(int cursor);
    }

    /**
     * Solver tuning values. Defaults mirror BZK09's intent: cheap local updates
     * first, global iterative solve second, direct solve only as a last resort.
     */
    public static final class Options {
        /** Maximum local Gauss-Seidel row updates before falling back. */
        public int localMaxIterations = 5_000;

        /** Absolute per-row residual tolerance for local Gauss-Seidel. */
        public double localTolerance = 1e-6;

        /** Maximum conjugate-gradient iterations before direct fallback. */
        public int cgMaxIterations = 20_000;

        /** Relative residual tolerance for conjugate gradient. */
        public double cgTolerance = 1e-7;

        /** Whether to use the direct sparse fallback after CG failure. */
        public boolean useDirectFallback = true;
    }

    /** Which rung of the adaptive ladder returned the final solution. */
    public enum Method {
        LOCAL_GAUSS_SEIDEL,
        CONJUGATE_GRADIENT,
        DIRECT,
        FAILED
    }

    /**
     * Solver statistics for one adaptive update.
     *
     * @param method          method that returned the solution
     * @param converged       whether the returned solution met its method tolerance
     * @param localIterations local Gauss-Seidel updates performed
     * @param cgIterations    conjugate-gradient iterations performed
     * @param residualNorm    final Euclidean residual norm over free variables
     */
    public record Stats(Method method,
            boolean converged,
            int localIterations,
            int cgIterations,
            double residualNorm) {
    }

    /**
     * Result of one adaptive solve.
     *
     * @param x     full solution vector, including fixed variables
     * @param stats iteration and convergence data
     */
    public record Result(double[] x, Stats stats) {
    }

    /**
     * Solve a fixed-variable symmetric linear system using the BZK09 adaptive
     * ladder, seeded as a global update.
     *
     * <p>
     * This is appropriate for the initial all-continuous minimizer, before a
     * particular integer variable has just been rounded.
     *
     * @param matrix    symmetric system matrix A
     * @param rhs       right-hand side b
     * @param warmStart previous/full solution estimate
     * @param fixed     fixed[i] means x[i] is held at warmStart[i]
     * @param options   solver tolerances and iteration caps
     * @return solution and stats
     */
    public static Result solve(Matrix matrix,
            double[] rhs,
            double[] warmStart,
            boolean[] fixed,
            Options options) {
        return solveAfterRounding(matrix, rhs, warmStart, fixed, -1, options);
    }

    /**
     * Solve after one integer variable has been rounded and fixed.
     *
     * <p>
     * This is the paper-faithful BZK09 update: local Gauss-Seidel begins by pushing
     * every variable whose row depends on {@code roundedVariable}. If
     * {@code roundedVariable < 0}, all free variables are seeded instead.
     *
     * @param matrix          symmetric system matrix A
     * @param rhs             right-hand side b
     * @param warmStart       previous/full solution estimate; fixed values are read
     *                        from this array
     * @param fixed           fixed[i] means x[i] is held at warmStart[i]
     * @param roundedVariable index of the variable just rounded, or negative for
     *                        global seeding
     * @param options         solver tolerances and iteration caps
     * @return solution and stats
     */
    public static Result solveAfterRounding(Matrix matrix,
            double[] rhs,
            double[] warmStart,
            boolean[] fixed,
            int roundedVariable,
            Options options) {
        validateInputs(matrix, rhs, warmStart, fixed);
        Options opts = options == null ? new Options() : options;
        double[] x = warmStart.clone();

        LocalResult local = roundedVariable >= 0
                ? localGaussSeidel(matrix, rhs, x, fixed, roundedVariable, opts)
                : new LocalResult(x, 0, false);
        if (local.converged) {
            double residual = residualNorm(matrix, rhs, local.x, fixed);
            return new Result(local.x, new Stats(
                    Method.LOCAL_GAUSS_SEIDEL, true, local.iterations, 0, residual));
        }

        CgResult cg = conjugateGradient(matrix, rhs, local.x, fixed, opts);
        if (cg.converged) {
            double residual = residualNorm(matrix, rhs, cg.x, fixed);
            return new Result(cg.x, new Stats(
                    Method.CONJUGATE_GRADIENT, true, local.iterations, cg.iterations, residual));
        }

        if (opts.useDirectFallback) {
            double[] direct = directSolve(matrix, rhs, cg.x, fixed);
            double residual = residualNorm(matrix, rhs, direct, fixed);
            return new Result(direct, new Stats(
                    Method.DIRECT, true, local.iterations, cg.iterations, residual));
        }

        double residual = residualNorm(matrix, rhs, cg.x, fixed);
        return new Result(cg.x, new Stats(
                Method.FAILED, false, local.iterations, cg.iterations, residual));
    }

    private static LocalResult localGaussSeidel(Matrix matrix,
            double[] rhs,
            double[] start,
            boolean[] fixed,
            int roundedVariable,
            Options options) {
        int n = matrix.size();
        double[] x = start.clone();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        boolean[] inQueue = new boolean[n];

        if (roundedVariable >= 0) {
            enqueueDependents(matrix, roundedVariable, fixed, queue, inQueue);
        } else {
            for (int i = 0; i < n; i++) {
                if (!fixed[i]) {
                    queue.add(i);
                    inQueue[i] = true;
                }
            }
        }

        int iterations = 0;
        while (!queue.isEmpty() && iterations < options.localMaxIterations) {
            iterations++;
            int row = queue.poll();
            inQueue[row] = false;
            if (fixed[row]) {
                continue;
            }

            double residual = rhs[row] - rowDot(matrix, row, x);
            if (Math.abs(residual) <= options.localTolerance) {
                continue;
            }

            double diag = matrix.diag(row);
            if (Math.abs(diag) < 1e-30) {
                return new LocalResult(x, iterations, false);
            }

            x[row] += residual / diag;
            enqueueDependents(matrix, row, fixed, queue, inQueue);
        }

        return new LocalResult(x, iterations, queue.isEmpty());
    }

    private static CgResult conjugateGradient(Matrix matrix,
            double[] rhs,
            double[] start,
            boolean[] fixed,
            Options options) {
        int n = matrix.size();
        double[] x = start.clone();
        double[] r = new double[n];
        double rhsNormSq = 0.0;
        for (int i = 0; i < n; i++) {
            if (fixed[i]) {
                continue;
            }
            r[i] = rhs[i] - rowDot(matrix, i, x);
            rhsNormSq += rhs[i] * rhs[i];
        }

        double[] z = new double[n];
        double[] p = new double[n];
        double rzOld = 0.0;
        for (int i = 0; i < n; i++) {
            if (fixed[i]) {
                continue;
            }
            double diag = matrix.diag(i);
            z[i] = Math.abs(diag) > 1e-30 ? r[i] / diag : r[i];
            p[i] = z[i];
            rzOld += r[i] * z[i];
        }

        double toleranceSq = options.cgTolerance * options.cgTolerance * Math.max(rhsNormSq, 1.0);
        if (residualNormSquared(r, fixed) <= toleranceSq) {
            return new CgResult(x, 0, true);
        }

        double[] ap = new double[n];
        int iteration = 0;
        for (; iteration < options.cgMaxIterations; iteration++) {
            Arrays.fill(ap, 0.0);
            for (int i = 0; i < n; i++) {
                if (!fixed[i]) {
                    ap[i] = rowDot(matrix, i, p);
                }
            }

            double pAp = 0.0;
            for (int i = 0; i < n; i++) {
                if (!fixed[i]) {
                    pAp += p[i] * ap[i];
                }
            }
            if (Math.abs(pAp) < 1e-30) {
                break;
            }

            double alpha = rzOld / pAp;
            for (int i = 0; i < n; i++) {
                if (fixed[i]) {
                    continue;
                }
                x[i] += alpha * p[i];
                r[i] -= alpha * ap[i];
            }
            if (residualNormSquared(r, fixed) <= toleranceSq) {
                return new CgResult(x, iteration + 1, true);
            }

            double rzNew = 0.0;
            for (int i = 0; i < n; i++) {
                if (fixed[i]) {
                    continue;
                }
                double diag = matrix.diag(i);
                z[i] = Math.abs(diag) > 1e-30 ? r[i] / diag : r[i];
                rzNew += r[i] * z[i];
            }
            double beta = rzNew / rzOld;
            for (int i = 0; i < n; i++) {
                if (!fixed[i]) {
                    p[i] = z[i] + beta * p[i];
                }
            }
            rzOld = rzNew;
        }
        return new CgResult(x, iteration, false);
    }

    private static double[] directSolve(Matrix matrix,
            double[] rhs,
            double[] start,
            boolean[] fixed) {
        int n = matrix.size();
        int[] compactOf = new int[n];
        int[] fullOf = new int[n];
        Arrays.fill(compactOf, -1);
        int freeCount = 0;
        for (int i = 0; i < n; i++) {
            if (!fixed[i]) {
                compactOf[i] = freeCount;
                fullOf[freeCount] = i;
                freeCount++;
            }
        }
        if (freeCount == 0) {
            return start.clone();
        }

        SparseMatrix compact = new SparseMatrix(freeCount, freeCount);
        double[] compactRhs = new double[freeCount];
        for (int u = 0; u < freeCount; u++) {
            int row = fullOf[u];
            double value = rhs[row];
            compact.add(u, u, matrix.diag(row));
            for (int c = matrix.rowStart(row); c < matrix.rowEnd(row); c++) {
                int col = matrix.column(c);
                double a = matrix.value(c);
                if (fixed[col]) {
                    value -= a * start[col];
                } else {
                    compact.add(u, compactOf[col], a);
                }
            }
            compactRhs[u] = value;
        }

        double[] compactX = compact.solveLeft(compactRhs);
        double[] x = start.clone();
        for (int u = 0; u < freeCount; u++) {
            x[fullOf[u]] = compactX[u];
        }
        return x;
    }

    private static void enqueueDependents(Matrix matrix,
            int row,
            boolean[] fixed,
            ArrayDeque<Integer> queue,
            boolean[] inQueue) {
        if (row < 0 || row >= matrix.size()) {
            return;
        }
        for (int c = matrix.rowStart(row); c < matrix.rowEnd(row); c++) {
            int col = matrix.column(c);
            if (fixed[col] || inQueue[col]) {
                continue;
            }
            queue.add(col);
            inQueue[col] = true;
        }
    }

    private static double rowDot(Matrix matrix, int row, double[] x) {
        double sum = matrix.diag(row) * x[row];
        for (int c = matrix.rowStart(row); c < matrix.rowEnd(row); c++) {
            sum += matrix.value(c) * x[matrix.column(c)];
        }
        return sum;
    }

    private static double residualNorm(Matrix matrix, double[] rhs, double[] x, boolean[] fixed) {
        double sum = 0.0;
        for (int i = 0; i < matrix.size(); i++) {
            if (fixed[i]) {
                continue;
            }
            double residual = rhs[i] - rowDot(matrix, i, x);
            sum += residual * residual;
        }
        return Math.sqrt(sum);
    }

    private static double residualNormSquared(double[] residual, boolean[] fixed) {
        double sum = 0.0;
        for (int i = 0; i < residual.length; i++) {
            if (!fixed[i]) {
                sum += residual[i] * residual[i];
            }
        }
        return sum;
    }

    private static void validateInputs(Matrix matrix, double[] rhs, double[] warmStart, boolean[] fixed) {
        int n = matrix.size();
        if (rhs.length != n || warmStart.length != n || fixed.length != n) {
            throw new IllegalArgumentException("matrix/vector size mismatch");
        }
    }

    private record LocalResult(double[] x, int iterations, boolean converged) {
    }

    private record CgResult(double[] x, int iterations, boolean converged) {
    }
}
