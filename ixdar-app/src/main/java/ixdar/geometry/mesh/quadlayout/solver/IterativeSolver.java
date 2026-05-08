package ixdar.geometry.mesh.quadlayout.solver;

import no.uib.cipr.matrix.DenseVector;
import no.uib.cipr.matrix.Vector;
import no.uib.cipr.matrix.sparse.AMG;
import no.uib.cipr.matrix.sparse.BiCGstab;
import no.uib.cipr.matrix.sparse.CG;
import no.uib.cipr.matrix.sparse.CompRowMatrix;
import no.uib.cipr.matrix.sparse.DefaultIterationMonitor;
import no.uib.cipr.matrix.sparse.DiagonalPreconditioner;
import no.uib.cipr.matrix.sparse.ICC;
import no.uib.cipr.matrix.sparse.IterativeSolverNotConvergedException;
import no.uib.cipr.matrix.sparse.Preconditioner;
import no.uib.cipr.matrix.sparse.SSOR;

/**
 * Iterative solver for {@link MtjSparseMatrix} systems too large for direct
 * factorization (PATCH-53). Backs the QGP IGM and cross-field LSQ stages once
 * the mesh size pushes the Hessian's stride past ojAlgo's 32-bit flat-index
 * limit (~46340).
 *
 * <p>Default schedule:
 * <ol>
 *   <li>CG with Jacobi (diagonal) preconditioner — assumes the input is SPD,
 *       which holds for normal-equation Hessians built by the IGM and rosy
 *       solvers (penalties are added on the diagonal so the system is at worst
 *       quasi-definite, and in practice symmetric-positive).
 *   <li>If CG diverges or fails to reach tolerance, retry with BiCGstab on the
 *       same preconditioner (handles slightly indefinite / asymmetric cases).
 *   <li>If BiCGstab also fails, return the best iterate the solver reached;
 *       the caller can downstream-inspect the residual.
 * </ol>
 *
 * <p>API: {@code solve(A, b, tol, maxIter) -> x}. All vectors and matrices are
 * MTJ-backed internally, but the public surface is plain {@code double[]}.
 */
public final class IterativeSolver {
    public static final String ICC = "icc";
    public static final double NUM_1e30 = 1e30;
    public static final double NUM_1e6 = 1e6;

    public static final double DEFAULT_TOL = 1e-6;
    /**
     * PATCH-134: bumped from 1000 to 10000. The IGM Hessian relaxed solve
     *  on rocker-arm-20k (N=22852) hit the 1000 cap WITHOUT converging,
     *  returning a partial iterate with residuals 1.4-3.4x larger than the
     *  target magnitude — that was the root cause of the 41% relaxed-solve
     *  flip rate (PATCH-127/128/130/132 traced this). Convergent solves
     *  still finish in 600-1000 iters on these meshes, so the bump costs
     *  almost nothing on those; it gives the harder relaxed solve room to
     */
    public static final int DEFAULT_MAX_ITER =
            Integer.getInteger("ixdar.quadlayout.solver.maxIter", 10000);

    /** Set {@code -Dixdar.quadlayout.solver.profile=true} to log iter count + time per solve. */
    private static final boolean PROFILE = Boolean.getBoolean("ixdar.quadlayout.solver.profile");

    private IterativeSolver() {}

    private static PrecondKind defaultPrecond() {
        String p = System.getProperty("ixdar.quadlayout.solver.precond", ICC);
        switch (p.toLowerCase()) {
            case "jacobi": return PrecondKind.JACOBI;
            case "ssor":   return PrecondKind.SSOR;
            case "amg":    return PrecondKind.AMG;
            case ICC:
            default:       return PrecondKind.ICC;
        }
    }

    /**
     * Convenience overload using {@link #DEFAULT_TOL} and {@link #DEFAULT_MAX_ITER}.
     *
     * @param A   square sparse system matrix
     * @param rhs right-hand-side vector
     * @return approximate solution {@code x} (best iterate if the solver did not converge)
     */
    public static double[] solve(MtjSparseMatrix A, double[] rhs) {
        return solve(A, rhs, DEFAULT_TOL, DEFAULT_MAX_ITER);
    }

    /**
     * Solve {@code A x = rhs} with the default schedule (CG with selected
     * preconditioner, falling back to BiCGstab on non-convergence).
     *
     * @param A       square sparse system matrix
     * @param rhs     right-hand-side vector of length {@code A.rows()}
     * @param tol     residual tolerance for the iteration monitor
     * @param maxIter iteration cap
     * @throws IllegalArgumentException if {@code A} is not square or {@code rhs} length mismatches
     * @return approximate solution; on total non-convergence, the best partial iterate
     */
    public static double[] solve(MtjSparseMatrix A, double[] rhs, double tol, int maxIter) {
        if (A.rows() != A.cols()) {
            throw new IllegalArgumentException("solve() requires square matrix");
        }
        if (rhs.length != A.rows()) {
            throw new IllegalArgumentException("rhs length mismatch");
        }

        long t0 = PROFILE ? System.nanoTime() : 0L;
        CompRowMatrix M = A.toCompRow();
        DenseVector b = new DenseVector(rhs);
        DenseVector x = new DenseVector(rhs.length);

        PrecondKind kind = defaultPrecond();
        long tPreStart = PROFILE ? System.nanoTime() : 0L;
        Preconditioner pre = buildPreconditioner(kind, M);
        long tPreEnd = PROFILE ? System.nanoTime() : 0L;

        // Try CG first — fastest on SPD systems, which is our common case.
        DefaultIterationMonitor mon1 = new DefaultIterationMonitor(maxIter, tol, 0.0, NUM_1e30);
        try {
            CG cg = new CG(x);
            cg.setPreconditioner(pre);
            cg.setIterationMonitor(mon1);
            Vector sol = cg.solve(M, b, x);
            if (PROFILE) {
                logProfile("CG", kind, M.numRows(), mon1.iterations(),
                        tPreEnd - tPreStart, System.nanoTime() - t0, true);
            }
            return toArray(sol, rhs.length);
        } catch (IterativeSolverNotConvergedException ignored) {
            // CG diverged or hit the iteration cap — fall through to BiCGstab.
        }
        if (PROFILE) {
            logProfile("CG-failed", kind, M.numRows(), mon1.iterations(),
                    tPreEnd - tPreStart, System.nanoTime() - t0, false);
        }

        // BiCGstab is more robust for nonsymmetric / indefinite systems.
        // Reset solution to zero so we don't carry CG's bad iterate.
        x.zero();
        DefaultIterationMonitor mon2 = new DefaultIterationMonitor(maxIter, tol, 0.0, NUM_1e30);
        try {
            BiCGstab bi = new BiCGstab(x);
            bi.setPreconditioner(pre);
            bi.setIterationMonitor(mon2);
            Vector sol = bi.solve(M, b, x);
            if (PROFILE) {
                logProfile("BiCGstab", kind, M.numRows(), mon2.iterations(),
                        tPreEnd - tPreStart, System.nanoTime() - t0, true);
            }
            return toArray(sol, rhs.length);
        } catch (IterativeSolverNotConvergedException notConverged) {
            // Best-effort: return the partial iterate. Caller should check
            // the residual themselves.
            if (PROFILE) {
                logProfile("BiCGstab-failed", kind, M.numRows(), mon2.iterations(),
                        tPreEnd - tPreStart, System.nanoTime() - t0, false);
            }
            return toArray(x, rhs.length);
        }
    }

    private static Preconditioner buildPreconditioner(PrecondKind kind, CompRowMatrix M) {
        switch (kind) {
            case JACOBI: {
                DiagonalPreconditioner p = new DiagonalPreconditioner(M.numRows());
                p.setMatrix(M);
                return p;
            }
            case SSOR: {
                SSOR p = new SSOR(M);
                p.setMatrix(M);
                return p;
            }
            case AMG: {
                AMG p = new AMG();
                p.setMatrix(M);
                return p;
            }
            case ICC:
            default: {
                ICC p = new ICC(M.copy());
                p.setMatrix(M);
                return p;
            }
        }
    }

    private static void logProfile(String solver, PrecondKind kind, int n,
                                   int iters, long preNs, long totalNs, boolean ok) {
        System.out.printf(
                "[IterativeSolver] %s/%s n=%d iters=%d pre=%.1fms total=%.1fms ok=%s%n",
                solver, kind, n, iters, preNs / NUM_1e6, totalNs / NUM_1e6, ok);
    }

    /**
     * Convenience wrapper that returns both the solution and the achieved
     * residual norm. Used by stress tests; production callers usually only
     * want the solution.
     *
     * @param A       square sparse system matrix
     * @param rhs     right-hand-side vector
     * @param tol     residual tolerance forwarded to {@link #solve(MtjSparseMatrix, double[], double, int)}
     * @param maxIter iteration cap
     * @return solution wrapped with absolute and relative residual norms
     */
    public static Result solveWithResidual(MtjSparseMatrix A, double[] rhs,
                                           double tol, int maxIter) {
        double[] x = solve(A, rhs, tol, maxIter);
        double[] check = A.multiply(x);
        double resNorm = 0.0;
        double bNorm = 0.0;
        for (int i = 0; i < rhs.length; i++) {
            double r = check[i] - rhs[i];
            resNorm += r * r;
            bNorm += rhs[i] * rhs[i];
        }
        return new Result(x, Math.sqrt(resNorm),
                bNorm > 0 ? Math.sqrt(resNorm / bNorm) : Math.sqrt(resNorm));
    }

    private static double[] toArray(Vector v, int n) {
        if (v instanceof DenseVector dv) {
            return dv.getData().clone();
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = v.get(i);
        return out;
    }

    /**
     * Preconditioner choice. {@code AUTO} picks ICC (fast SPD path); set via
     * system property {@code ixdar.quadlayout.solver.precond} to one of
     * {@code jacobi|icc|ssor|amg} for A/B benchmarking.
     */
    public enum PrecondKind { JACOBI, ICC, SSOR, AMG }

    public static final class Result {
        public final double[] x;
        public final double residualNorm;
        public final double relativeResidual;

        Result(double[] x, double residualNorm, double relativeResidual) {
            this.x = x;
            this.residualNorm = residualNorm;
            this.relativeResidual = relativeResidual;
        }
    }
}
