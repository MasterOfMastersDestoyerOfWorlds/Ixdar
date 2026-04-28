package ixdar.geometry.mesh.quadlayout.vectorfield;

import ixdar.geometry.mesh.quadlayout.solver.SparseMatrix;

/**
 * Greedy round-and-resolve for the Bommes-Zimmer-Kobbelt 2012 mixed-integer
 * cross-field problem (BZK12). Solves the angle-based smoothness energy
 *
 * <pre>
 *   E(theta, m) = sum_{e in E_int} ( theta_a - theta_b + kappa_e + m_e * pi/2 )^2
 * </pre>
 *
 * with theta_f real per face and m_e integer per interior edge. The integer
 * field is parameterized via the standard cycle-space basis: m_e is fixed at
 * zero on a spanning-tree of the dual graph; the remaining (E - F + 1)
 * non-tree edges (the chords) carry one free integer variable each. With this
 * gauge, the smoothness energy is unchanged and the integer DOF count matches
 * the first homology of the dual graph.
 *
 * <h3>Greedy schedule</h3>
 * <ol>
 *   <li>Relax all chord m's to reals, solve the linear system over (theta, m_chord).</li>
 *   <li>Pick the unpinned chord whose relaxed m is closest to an integer.</li>
 *   <li>Pin it to the nearest integer, re-solve.</li>
 *   <li>Repeat until every chord is pinned.</li>
 * </ol>
 *
 * <p>This is the same iterative pattern as {@code IterativeRounding} in the
 * IGM stage — solve, score by fractional distance, commit cheapest pin,
 * re-solve.
 *
 * <h3>Output</h3>
 * Per-face theta plus per-interior-edge integer m. Tree edges receive
 * m = round(-(theta_a - theta_b + kappa) / (pi/2)) computed from the final
 * theta so that downstream matching/holonomy logic sees the integer rotation
 * actually performed across every edge.
 */
final class MiGreedyRounding {

    private static final double PI_HALF = Math.PI * 0.5;
    private static final double TIKHONOV = 1e-6;
    private static final double GAUGE_PENALTY = 1e10;
    private static final double PIN_PENALTY = 1e10;

    static final class Result {
        final double[] theta;
        final int[] periodJump;
        final int chordCount;
        final int iterationCount;

        Result(double[] theta, int[] periodJump, int chordCount, int iterationCount) {
            this.theta = theta;
            this.periodJump = periodJump;
            this.chordCount = chordCount;
            this.iterationCount = iterationCount;
        }
    }

    private final int F;
    private final int E;
    private final int[] edgeFaceA;
    private final int[] edgeFaceB;
    private final double[] kappa;
    private final boolean[] isTreeEdge;

    private final int[] chordOfEdge;   // chord index in [0..C), or -1 for tree edges
    private final int[] edgeOfChord;   // chord c -> interior edge index
    private final int C;               // chord count

    private final boolean[] chordPinned;
    private final double[] chordPinValue;

    MiGreedyRounding(int F, int E,
                     int[] edgeFaceA, int[] edgeFaceB,
                     double[] kappa, boolean[] isTreeEdge) {
        this.F = F;
        this.E = E;
        this.edgeFaceA = edgeFaceA;
        this.edgeFaceB = edgeFaceB;
        this.kappa = kappa;
        this.isTreeEdge = isTreeEdge;

        int chords = 0;
        for (int e = 0; e < E; e++) if (!isTreeEdge[e]) chords++;
        this.C = chords;
        this.chordOfEdge = new int[E];
        this.edgeOfChord = new int[C];
        int c = 0;
        for (int e = 0; e < E; e++) {
            if (!isTreeEdge[e]) {
                chordOfEdge[e] = c;
                edgeOfChord[c] = e;
                c++;
            } else {
                chordOfEdge[e] = -1;
            }
        }
        this.chordPinned = new boolean[C];
        this.chordPinValue = new double[C];
    }

    Result solve() {
        if (F == 0) {
            return new Result(new double[0], new int[E], C, 0);
        }

        double[] x = solveRelaxed();
        if (x == null) {
            return fallback();
        }

        int iter = 0;
        while (true) {
            int bestChord = -1;
            double bestFrac = Double.POSITIVE_INFINITY;
            double bestRounded = 0.0;
            for (int c = 0; c < C; c++) {
                if (chordPinned[c]) continue;
                double v = x[F + c];
                double r = Math.round(v);
                double f = Math.abs(v - r);
                if (f < bestFrac) {
                    bestFrac = f;
                    bestChord = c;
                    bestRounded = r;
                }
            }
            if (bestChord < 0) break;

            chordPinned[bestChord] = true;
            chordPinValue[bestChord] = bestRounded;
            iter++;

            double[] xNext = solveRelaxed();
            if (xNext == null) {
                // Solver collapse — keep the prior solution and stop pinning.
                chordPinned[bestChord] = false;
                break;
            }
            x = xNext;
        }

        double[] theta = new double[F];
        System.arraycopy(x, 0, theta, 0, F);

        // Final m recovery: for every interior edge (tree or chord), round the
        // per-edge residual at the post-greedy theta to the nearest multiple of
        // pi/2. This reads off the actual integer rotation the cross field
        // performs across every edge, which is what downstream matching
        // (CombedField) and holonomy detection (SingularityFinder) need.
        // The greedy chord pinning matters because it determines the SHAPE of
        // theta — without it the smooth field over-relaxes and introduces
        // spurious cycle holonomy. With it, theta is consistent with an
        // achievable integer field on the cycle basis, so per-edge rounding
        // closes correctly around each primal vertex cycle.
        int[] periodJump = new int[E];
        for (int e = 0; e < E; e++) {
            double residual = theta[edgeFaceA[e]] - theta[edgeFaceB[e]] + kappa[e];
            periodJump[e] = (int) Math.round(-residual / PI_HALF);
        }
        return new Result(theta, periodJump, C, iter);
    }

    /**
     * Solve the LSQ normal equations over (theta_0..theta_{F-1}, m_chord_0..m_chord_{C-1})
     * with chord-pin constraints applied via penalty. Returns null on solver
     * failure.
     */
    private double[] solveRelaxed() {
        int N = F + C;
        SparseMatrix lhs = new SparseMatrix(N, N);
        double[] rhs = new double[N];

        for (int i = 0; i < N; i++) lhs.add(i, i, TIKHONOV);

        for (int e = 0; e < E; e++) {
            int fa = edgeFaceA[e];
            int fb = edgeFaceB[e];
            int c = chordOfEdge[e];
            double k = kappa[e];

            // r_e = theta_a - theta_b + k + (chord ? PI_HALF * m_c : 0)
            // d r_e / d theta_a = +1, d r_e / d theta_b = -1, d r_e / d m_c = PI_HALF
            // A^T A entries: dot products of row e with itself for indices used.
            //   (a,a)+=1, (b,b)+=1, (a,b)-=1, (b,a)-=1
            //   if chord: (a,F+c)+=PI_HALF, (F+c,a)+=PI_HALF
            //             (b,F+c)-=PI_HALF, (F+c,b)-=PI_HALF
            //             (F+c,F+c)+=PI_HALF^2
            // RHS = -A^T k:
            //   row a -= k, row b += k, if chord: row F+c -= PI_HALF * k
            lhs.add(fa, fa, 1.0);
            lhs.add(fb, fb, 1.0);
            lhs.add(fa, fb, -1.0);
            lhs.add(fb, fa, -1.0);
            rhs[fa] -= k;
            rhs[fb] += k;
            if (c >= 0) {
                int mc = F + c;
                lhs.add(fa, mc, PI_HALF);
                lhs.add(mc, fa, PI_HALF);
                lhs.add(fb, mc, -PI_HALF);
                lhs.add(mc, fb, -PI_HALF);
                lhs.add(mc, mc, PI_HALF * PI_HALF);
                rhs[mc] -= PI_HALF * k;
            }
        }

        // Gauge: theta[0] = 0.
        lhs.add(0, 0, GAUGE_PENALTY);

        // Pinned chords: m_c = chordPinValue[c].
        for (int c = 0; c < C; c++) {
            if (!chordPinned[c]) continue;
            int mc = F + c;
            lhs.add(mc, mc, PIN_PENALTY);
            rhs[mc] += PIN_PENALTY * chordPinValue[c];
        }

        try {
            return lhs.solveLeft(rhs);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Simplified PATCH-39-style fallback: solve theta only with m=0, then round
     * every interior edge to nearest pi/2. Used only when the joint solve
     * collapses on the very first call (degenerate mesh).
     */
    private Result fallback() {
        SparseMatrix lhs = new SparseMatrix(F, F);
        double[] rhs = new double[F];
        for (int i = 0; i < F; i++) lhs.add(i, i, TIKHONOV);
        for (int e = 0; e < E; e++) {
            int fa = edgeFaceA[e], fb = edgeFaceB[e];
            double k = kappa[e];
            lhs.add(fa, fa, 1.0);
            lhs.add(fb, fb, 1.0);
            lhs.add(fa, fb, -1.0);
            lhs.add(fb, fa, -1.0);
            rhs[fa] -= k;
            rhs[fb] += k;
        }
        lhs.add(0, 0, GAUGE_PENALTY);
        double[] theta;
        try {
            theta = lhs.solveLeft(rhs);
        } catch (RuntimeException ex) {
            theta = new double[F];
        }
        int[] periodJump = new int[E];
        for (int e = 0; e < E; e++) {
            double residual = theta[edgeFaceA[e]] - theta[edgeFaceB[e]] + kappa[e];
            periodJump[e] = (int) Math.round(-residual / PI_HALF);
        }
        return new Result(theta, periodJump, C, 0);
    }
}
