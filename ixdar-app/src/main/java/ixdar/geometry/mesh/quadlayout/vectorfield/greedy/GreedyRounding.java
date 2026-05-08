package ixdar.geometry.mesh.quadlayout.vectorfield.greedy;

import ixdar.geometry.mesh.quadlayout.vectorfield.solver.BzkAdaptiveSolver;
import ixdar.geometry.mesh.quadlayout.vectorfield.solver.BzkSystem;

/**
 * Greedy round-and-resolve outer loop for the BZK09 mixed-integer cross-field
 * problem. Owns the chord-pin commitments; delegates every linear solve to
 * {@link BzkAdaptiveSolver}.
 *
 * <p>Schedule (BZK09 §2):
 * <ol>
 *   <li>Bootstrap: solve with all chord m_chord relaxed to ℝ; gauge θ[0]=0
 *       and any caller-supplied directional constraints already pinned.</li>
 *   <li>Pick the unpinned chord whose relaxed m is closest to an integer.</li>
 *   <li>Pin it to that integer and re-solve, warm-started from the previous
 *       solution.</li>
 *   <li>Repeat until every chord is pinned.</li>
 * </ol>
 *
 * <p>Tree-edge integers are recovered post-greedy by reading off the residual
 * of each interior edge against the final θ — required for downstream
 * {@code CombedField} matching and {@code SingularityFinder} holonomy.
 */
public final class GreedyRounding {
    public static final double NUM_0_01 = 0.01;
    public static final double NUM_0_05 = 0.05;
    public static final double NUM_0_1 = 0.1;
    public static final double NUM_0_2 = 0.2;
    public static final double NUM_0_3 = 0.3;
    public static final double NUM_0_5 = 0.5;

    private static final double PI_HALF = Math.PI * 0.5;

    private GreedyRounding() {}

    /**
     * TODO: document {@code solve}.
     *
     * @param sys TODO: describe
     * @param opts TODO: describe
     * @return TODO: describe
     */
    public static Result solve(BzkSystem sys, Options opts) {
        int F = sys.faceCount();
        int E = sys.edgeCount();
        int C = sys.chordCount();
        int N = sys.variableCount();
        if (F == 0) return new Result(new double[0], new int[E], C, 0, 0, 0, 0, 0);

        // Gauge + directional constraints become permanent pins.
        boolean[] pinned = new boolean[N];
        double[] pinVal = new double[N];
        boolean face0Constrained = opts.constrained != null && opts.constrained[0];
        if (!face0Constrained) {
            pinned[0] = true;
            pinVal[0] = 0.0;
        }
        if (opts.thetaConstraint != null && opts.constrained != null) {
            for (int f = 0; f < F; f++) {
                if (opts.constrained[f]) {
                    pinned[f] = true;
                    pinVal[f] = opts.thetaConstraint[f];
                }
            }
        }

        // Bootstrap solve: x0 = zeros, only gauge + constraints pinned.
        BzkAdaptiveSolver.Stats st0 = new BzkAdaptiveSolver.Stats();
        double[] x = BzkAdaptiveSolver.bootstrap(sys, pinned, pinVal, opts.solverOpts, st0);

        boolean[] chordPinned = new boolean[C];
        int gsConv = 0, cgConv = 0, direct = 0, totalCg = 0;
        if (st0.gsConverged) gsConv++;
        else if (st0.cgConverged) cgConv++;
        else if (st0.usedDirect) direct++;
        totalCg += st0.cgIters;

        // PATCH-103 batch pin commits: instead of pinning ONE chord and
        //   re-solving (10K iterations), commit ALL chords whose fractional
        //   value is below an adaptive threshold {fracThresh} simultaneously,
        //   then re-solve. Drops re-solve count from O(C) to O(log C).
        //   BZK09 §6 hints at this when it says they round "tens of thousands
        //   of variables" efficiently — they're not literally one-at-a-time.
        //
        //   fracThresh starts very small (only commit chords already nearly
        //   integer) and grows geometrically: 0.01 → 0.05 → 0.1 → ...
        //   → 0.5 → infinity (commit everything else).
        double[] fracThresholds = {NUM_0_01, NUM_0_05, NUM_0_1, NUM_0_2, NUM_0_3, NUM_0_5, Double.POSITIVE_INFINITY};
        int iter = 0;
        for (double fracThresh : fracThresholds) {
            int committedThisRound = 0;
            for (int c = 0; c < C; c++) {
                if (chordPinned[c]) continue;
                double v = x[F + c];
                double r = Math.round(v);
                double f = Math.abs(v - r);
                if (f <= fracThresh) {
                    chordPinned[c] = true;
                    int idx = F + c;
                    pinned[idx] = true;
                    pinVal[idx] = r;
                    committedThisRound++;
                }
            }
            if (committedThisRound == 0) continue;
            iter++;

            BzkAdaptiveSolver.Stats st = new BzkAdaptiveSolver.Stats();
            x = BzkAdaptiveSolver.solve(sys, x, pinned, pinVal, opts.solverOpts, st);
            if (st.gsConverged) gsConv++;
            else if (st.cgConverged) cgConv++;
            else if (st.usedDirect) direct++;
            totalCg += st.cgIters;
        }

        double[] theta = new double[F];
        System.arraycopy(x, 0, theta, 0, F);

        // Recover integer m on every interior edge (tree or chord) by
        // rounding the per-edge residual at the post-greedy theta. For
        // chord edges this matches the pinned value; tree edges fall out
        // from the smooth solve.
        int[] periodJump = new int[E];
        for (int e = 0; e < E; e++) {
            double residual = theta[sys.edgeFaceA(e)] - theta[sys.edgeFaceB(e)] + sys.kappa(e);
            periodJump[e] = (int) Math.round(-residual / PI_HALF);
        }
        return new Result(theta, periodJump, C, iter, gsConv, cgConv, direct, totalCg);
    }

    public static final class Result {
        public final double[] theta;
        public final int[] periodJump;
        public final int chordCount;
        public final int iterations;
        public final int gsConverged;
        public final int cgConverged;
        public final int directFallbacks;
        public final int totalCgIters;

        Result(double[] theta, int[] periodJump, int chordCount, int iterations,
               int gsConverged, int cgConverged, int directFallbacks, int totalCgIters) {
            this.theta = theta;
            this.periodJump = periodJump;
            this.chordCount = chordCount;
            this.iterations = iterations;
            this.gsConverged = gsConverged;
            this.cgConverged = cgConverged;
            this.directFallbacks = directFallbacks;
            this.totalCgIters = totalCgIters;
        }
    }

    public static final class Options {
        public final BzkAdaptiveSolver.Options solverOpts;
        /** Optional per-face θ hard-constraint values (radians). May be null. */
        public final double[] thetaConstraint;
        /** Companion mask; constrained[f]=true means thetaConstraint[f] is active. May be null. */
        public final boolean[] constrained;

        /**
         * TODO: document {@code Options}.
         */
        public Options() {
            this(new BzkAdaptiveSolver.Options(), null, null);
        }

        /**
         * TODO: document {@code Options}.
         *
         * @param solverOpts TODO: describe
         * @param thetaConstraint TODO: describe
         * @param constrained TODO: describe
         */
        public Options(BzkAdaptiveSolver.Options solverOpts,
                       double[] thetaConstraint, boolean[] constrained) {
            this.solverOpts = solverOpts;
            this.thetaConstraint = thetaConstraint;
            this.constrained = constrained;
        }
    }
}
