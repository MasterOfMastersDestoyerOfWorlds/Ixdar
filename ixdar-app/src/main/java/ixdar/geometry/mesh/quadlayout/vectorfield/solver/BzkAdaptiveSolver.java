package ixdar.geometry.mesh.quadlayout.vectorfield.solver;

import ixdar.geometry.mesh.quadlayout.solver.IterativeSolver;
import ixdar.geometry.mesh.quadlayout.solver.MtjSparseMatrix;
import ixdar.geometry.mesh.quadlayout.solver.SparseMatrix;

/**
 * BZK09 §2.1 adaptive solver ladder for the BZK system. Solves
 *
 * <pre>
 *   (A_unpinned) · x_unpinned = b_eff
 * </pre>
 *
 * where pinned variables (gauge fix, directional constraints, chord pins) are
 * eliminated by substitution, NOT penalty. Used for both:
 * <ul>
 *   <li><b>Bootstrap</b>: x0 = zeros, only gauge + directional constraints
 *       pinned; no chord pins yet.</li>
 *   <li><b>Per-pin re-solves</b>: x0 = warm-start (previous solution), one
 *       additional chord pinned.</li>
 * </ul>
 *
 * <p>Ladder per BZK09 §2.1: optional Local Gauss-Seidel (Algorithm 1) →
 * Jacobi-preconditioned Conjugate Gradient → ojAlgo SparseLU fallback.
 */
public final class BzkAdaptiveSolver {

    /** Iteration / convergence stats for one solve(). */
    public static final class Stats {
        public boolean gsConverged;
        public boolean cgConverged;
        public boolean usedDirect;
        public int gsIters;
        public int cgIters;
    }

    public static final class Options {
        public boolean useGs = false;            // BZK09 GS on small pins; default off
        // PATCH-103: ICC default OFF — MTJ's ICC factorizes the entire
        //   matrix per call, dominating runtime when called 10K times in
        //   the greedy loop. Useful only for one-shot solves on big systems.
        //   Enable via -Dixdar.bzk09.useIcc=true if amortized externally.
        public boolean useIcc = false;
        public boolean useCg = true;             // Jacobi-PCG (PATCH-101 default)
        public int gsMaxIter = 5000;
        public double gsTolerance = 1e-6;
        public int cgMaxIter = 5000;
        public double cgTolerance = 1e-7;
        public int iccMaxIter = 2000;
        public double iccTolerance = 1e-7;
    }

    private BzkAdaptiveSolver() {}

    /**
     * Solve the unpinned subsystem warm-started from {@code x0}. Returns a
     * fresh array of length N where {@code result[k] = pinVal[k]} for pinned
     * k, and the solved value otherwise.
     *
     * @param sys     immutable BZK system
     * @param x0      warm-start vector of length N (may be zeros for bootstrap)
     * @param pinned  which variables are eliminated (gauge / constraint / chord pin)
     * @param pinVal  values for pinned variables
     * @param opts    solver knobs
     * @param stats   output stats (may be null)
     */
    public static double[] solve(BzkSystem sys, double[] x0,
                                 boolean[] pinned, double[] pinVal,
                                 Options opts, Stats stats) {
        if (stats == null) stats = new Stats();
        int N = sys.variableCount();

        double[] x = x0.clone();
        for (int k = 0; k < N; k++) if (pinned[k]) x[k] = pinVal[k];

        double[] xNext;

        if (opts.useGs) {
            xNext = tryLocalGs(sys, x, pinned, pinVal, opts, stats);
            if (xNext != null) {
                stats.gsConverged = true;
                return xNext;
            }
        }
        // PATCH-103: MTJ ICC (Incomplete Cholesky) preconditioned CG. Much
        //   stronger than Jacobi for this Laplacian-like system; cuts iter
        //   count from O(1000) to O(10-50) on rocker-arm-scale meshes.
        if (opts.useIcc) {
            xNext = tryIccCg(sys, x, pinned, pinVal, opts, stats);
            if (xNext != null) {
                stats.cgConverged = true;
                return xNext;
            }
        }
        if (opts.useCg) {
            xNext = tryPcg(sys, x, pinned, pinVal, opts, stats);
            if (xNext != null) {
                stats.cgConverged = true;
                return xNext;
            }
        }
        // Direct fallback.
        stats.usedDirect = true;
        return solveDirect(sys, pinned, pinVal);
    }

    /**
     * PATCH-103 MTJ + ICC preconditioned CG. Builds the unpinned subsystem
     * as an MTJ {@link MtjSparseMatrix}, then solves via
     * {@link IterativeSolver#solve} which uses Incomplete Cholesky
     * preconditioning by default. Much faster convergence than Jacobi PCG
     * on Laplacian-like SPD systems (5-10× fewer iterations typical).
     *
     * <p>The MTJ matrix build cost is amortized by the dramatic iteration
     * reduction. Returns null if MTJ's solver doesn't converge to tolerance,
     * leaving the caller to fall back further.
     */
    private static double[] tryIccCg(BzkSystem sys, double[] x,
                                      boolean[] pinned, double[] pinVal,
                                      Options opts, Stats stats) {
        int N = sys.variableCount();
        // Compactify the unpinned indices into [0..U).
        int[] toCompact = new int[N];
        int[] fromCompact = new int[N];
        int U = 0;
        for (int k = 0; k < N; k++) {
            if (pinned[k]) {
                toCompact[k] = -1;
            } else {
                toCompact[k] = U;
                fromCompact[U] = k;
                U++;
            }
        }
        if (U == 0) {
            // All pinned — nothing to solve.
            double[] full = new double[N];
            for (int k = 0; k < N; k++) full[k] = pinVal[k];
            return full;
        }

        MtjSparseMatrix m = new MtjSparseMatrix(U, U);
        double[] rhs = new double[U];
        for (int u = 0; u < U; u++) {
            int k = fromCompact[u];
            m.add(u, u, sys.diag(k));
            rhs[u] = sys.effectiveRhs(k, pinned, pinVal);
            int rs = sys.rowStart(k);
            int re = sys.rowEnd(k);
            for (int p = rs; p < re; p++) {
                int j = sys.rowCol(p);
                if (pinned[j]) continue;
                int uj = toCompact[j];
                m.add(u, uj, sys.rowVal(p));
            }
        }
        try {
            double[] xCompact = IterativeSolver.solve(m, rhs, opts.iccTolerance, opts.iccMaxIter);
            // Verify residual; treat non-convergence as null.
            double[] check = m.multiply(xCompact);
            double resSq = 0, bSq = 0;
            for (int u = 0; u < U; u++) {
                double r = check[u] - rhs[u];
                resSq += r * r;
                bSq += rhs[u] * rhs[u];
            }
            double relRes = Math.sqrt(resSq / Math.max(bSq, 1e-30));
            if (relRes > opts.iccTolerance * 100) {
                // Solver returned best-effort, didn't actually converge.
                stats.cgIters = opts.iccMaxIter;
                return null;
            }
            stats.cgIters = -1;  // MTJ doesn't expose iter count via this API
            double[] full = new double[N];
            for (int k = 0; k < N; k++) full[k] = pinVal[k];
            for (int u = 0; u < U; u++) full[fromCompact[u]] = xCompact[u];
            return full;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Convenience: bootstrap solve (x0 = zeros). */
    public static double[] bootstrap(BzkSystem sys,
                                     boolean[] pinned, double[] pinVal,
                                     Options opts, Stats stats) {
        double[] x0 = new double[sys.variableCount()];
        return solve(sys, x0, pinned, pinVal, opts, stats);
    }

    /**
     * BZK09 Algorithm 1: Local Gauss-Seidel. After a pin commits, propagate
     * residuals through dependent variables via single-variable updates.
     * Returns null if not converged within {@code gsMaxIter}.
     */
    private static double[] tryLocalGs(BzkSystem sys, double[] x,
                                       boolean[] pinned, double[] pinVal,
                                       Options opts, Stats stats) {
        int N = sys.variableCount();
        double[] xLocal = x.clone();

        // Seed queue with all unpinned variables that touch a pinned variable.
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        boolean[] inQueue = new boolean[N];
        for (int k = 0; k < N; k++) {
            if (pinned[k]) continue;
            int rs = sys.rowStart(k);
            int re = sys.rowEnd(k);
            for (int p = rs; p < re; p++) {
                if (pinned[sys.rowCol(p)]) {
                    queue.add(k);
                    inQueue[k] = true;
                    break;
                }
            }
        }

        int iter = 0;
        while (!queue.isEmpty() && iter < opts.gsMaxIter) {
            iter++;
            int k = queue.poll();
            inQueue[k] = false;
            double bEff = sys.effectiveRhs(k, pinned, pinVal);
            double sum = sys.rowDotUnpinned(k, xLocal, pinned);
            double rk = bEff - sum;
            if (Math.abs(rk) <= opts.gsTolerance) continue;
            double d = sys.diag(k);
            if (Math.abs(d) < 1e-30) {
                stats.gsIters = iter;
                return null;
            }
            xLocal[k] += rk / d;
            int rs = sys.rowStart(k);
            int re = sys.rowEnd(k);
            for (int p = rs; p < re; p++) {
                int j = sys.rowCol(p);
                if (pinned[j] || j == k) continue;
                if (!inQueue[j]) {
                    queue.add(j);
                    inQueue[j] = true;
                }
            }
        }
        stats.gsIters = iter;
        if (iter >= opts.gsMaxIter) return null;
        return xLocal;
    }

    /**
     * Jacobi-preconditioned Conjugate Gradient on the unpinned subspace,
     * warm-started from {@code x}. Returns null if not converged within
     * {@code cgMaxIter}.
     *
     * <p>Standard PCG (Saad, Iterative Methods §9.2):
     * <pre>
     *   r = b_eff − A x;  z = M⁻¹ r;  p = z;  r·z accumulated.
     *   each iter: α = (r·z)/(p·Ap); x += αp; r −= αAp; z = M⁻¹ r;
     *              β = (r·z)_new / (r·z)_old; p = z + βp.
     * </pre>
     */
    private static double[] tryPcg(BzkSystem sys, double[] x,
                                   boolean[] pinned, double[] pinVal,
                                   Options opts, Stats stats) {
        int N = sys.variableCount();
        double[] xLocal = x.clone();

        // Jacobi preconditioner M⁻¹ = diag(1/A_kk).
        double[] mInv = new double[N];
        for (int k = 0; k < N; k++) {
            if (pinned[k]) continue;
            double d = sys.diag(k);
            mInv[k] = (Math.abs(d) > 1e-30) ? 1.0 / d : 1.0;
        }

        // r = b_eff − A x (unpinned only).
        double[] r = new double[N];
        for (int k = 0; k < N; k++) {
            if (pinned[k]) continue;
            r[k] = sys.effectiveRhs(k, pinned, pinVal) - sys.rowDotUnpinned(k, xLocal, pinned);
        }
        double rNormSq = 0;
        for (int k = 0; k < N; k++) if (!pinned[k]) rNormSq += r[k] * r[k];
        if (Math.sqrt(rNormSq) < opts.cgTolerance) {
            stats.cgIters = 0;
            return xLocal;
        }

        double[] z = new double[N];
        for (int k = 0; k < N; k++) if (!pinned[k]) z[k] = mInv[k] * r[k];
        double rzOld = 0;
        for (int k = 0; k < N; k++) if (!pinned[k]) rzOld += r[k] * z[k];
        double[] dir = z.clone();
        double[] Ap = new double[N];

        int it;
        for (it = 0; it < opts.cgMaxIter; it++) {
            for (int k = 0; k < N; k++) {
                Ap[k] = pinned[k] ? 0.0 : sys.rowDotUnpinned(k, dir, pinned);
            }
            double pAp = 0;
            for (int k = 0; k < N; k++) if (!pinned[k]) pAp += dir[k] * Ap[k];
            if (Math.abs(pAp) < 1e-30) break;
            double alpha = rzOld / pAp;
            double rNewNormSq = 0;
            for (int k = 0; k < N; k++) {
                if (pinned[k]) continue;
                xLocal[k] += alpha * dir[k];
                r[k] -= alpha * Ap[k];
                rNewNormSq += r[k] * r[k];
            }
            if (Math.sqrt(rNewNormSq) < opts.cgTolerance) {
                stats.cgIters = it + 1;
                return xLocal;
            }
            double rzNew = 0;
            for (int k = 0; k < N; k++) {
                if (pinned[k]) continue;
                z[k] = mInv[k] * r[k];
                rzNew += r[k] * z[k];
            }
            double beta = rzNew / rzOld;
            for (int k = 0; k < N; k++) {
                if (pinned[k]) continue;
                dir[k] = z[k] + beta * dir[k];
            }
            rzOld = rzNew;
        }
        stats.cgIters = it;
        return null;
    }

    /**
     * Direct sparse fallback. Builds the unpinned subsystem as a
     * {@link SparseMatrix} and calls {@code solveLeft}. Used only when both
     * GS and PCG fail to converge — typically a sign of an ill-conditioned
     * matrix near rank deficiency, not normal operation.
     */
    private static double[] solveDirect(BzkSystem sys,
                                        boolean[] pinned, double[] pinVal) {
        int N = sys.variableCount();
        // Map unpinned indices to compact range.
        int[] toCompact = new int[N];
        int[] fromCompact = new int[N];
        int U = 0;
        for (int k = 0; k < N; k++) {
            if (pinned[k]) {
                toCompact[k] = -1;
            } else {
                toCompact[k] = U;
                fromCompact[U] = k;
                U++;
            }
        }
        SparseMatrix lhs = new SparseMatrix(U, U);
        double[] rhs = new double[U];
        for (int u = 0; u < U; u++) {
            int k = fromCompact[u];
            lhs.add(u, u, sys.diag(k));
            rhs[u] = sys.effectiveRhs(k, pinned, pinVal);
            int rs = sys.rowStart(k);
            int re = sys.rowEnd(k);
            for (int p = rs; p < re; p++) {
                int j = sys.rowCol(p);
                if (pinned[j]) continue;
                int uj = toCompact[j];
                lhs.add(u, uj, sys.rowVal(p));
            }
        }
        double[] xCompact = lhs.solveLeft(rhs);
        double[] x = new double[N];
        for (int k = 0; k < N; k++) x[k] = pinVal[k];
        for (int u = 0; u < U; u++) x[fromCompact[u]] = xCompact[u];
        return x;
    }
}
