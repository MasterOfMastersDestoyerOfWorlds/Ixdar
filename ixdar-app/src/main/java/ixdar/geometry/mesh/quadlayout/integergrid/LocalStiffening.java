package ixdar.geometry.mesh.quadlayout.integergrid;

import java.util.Arrays;

/**
 * Local Stiffening (Bommes-Zimmer-Kobbelt 2009 Sec 5.4) — the iteratively
 * reweighted least squares (IRLS) post-process Lyon 2021 inherits via its
 * BZK09-based parametrization stage. Replaces the old per-face log-barrier
 * Newton path: PATCH-49's {@code LogBarrier} was a re-invention not present
 * in either reference paper (BCEK*13 has no §4 log-barrier section; it uses
 * IPOPT). Lyon §7 ¶1 cites BZK09 explicitly.
 *
 * <p>Algorithm (BZK09 §5.4 verbatim):
 * <ol>
 *   <li>Initialize per-face stiffness {@code w(T) = 1}.</li>
 *   <li>Solve the weighted IGM QP → produces (u, v) per chart-vertex.</li>
 *   <li>Stop if every triangle is positively oriented and the worst HLS
 *       distortion is below {@link #DISTORTION_TOL}.</li>
 *   <li>For each face compute the 2×2 Jacobian {@code J = (∇u, ∇v)}, its
 *       singular values {@code σ₁ ≥ σ₂} and orientation sign {@code τ}; then
 *       <pre>
 *         λ(T) = |τ σ₁ / h − 1| + |τ σ₂ / h − 1|
 *       </pre>
 *       (Hormann-Lévy-Sheffer 2007 isometric distortion, BZK09 §5.4 eq.
 *       restated). {@code h} is the target singular value baked into the
 *       Hessian's gradient targets.</li>
 *   <li>Update weights {@code w(T) ← w(T) + min(c · 4 |λ(T)|, d)} with
 *       BZK09's constants {@code c = 1, d = 5}.</li>
 *   <li>Smooth weights on the dual mesh ({@link #SMOOTH_PASSES} uniform
 *       Laplacian passes) — BZK09 §5.4 "a few uniform smoothing steps"
 *       prevents stiffness discontinuities from wrecking the next QP.</li>
 *   <li>Re-solve and loop.</li>
 * </ol>
 *
 * <p>Each iteration is a convex QP (no non-convex barrier, no Newton line
 * search), so the path can't diverge to NaN the way the old log-barrier did
 * once {@code flipWeight} pushed the matrix past the iterative solver's
 * conditioning ceiling.
 */
final class LocalStiffening {

    /** Hard cap on IRLS iterations. BZK09 doesn't specify; 15 is generous. */
    static final int MAX_ITER = 15;
    /** BZK09 §5.4 stiffness-update constant c. */
    static final double STIFFEN_C = 1.0;
    /** BZK09 §5.4 stiffness-update cap d. PATCH-117 confirmed empirically
     *  that bumping this up to 100 makes IRLS oscillate without improving
     *  convergence on 20k+ meshes — the dense-flip regime can't be cleaned
     *  up by reweighting alone. Stay at paper default. */
    static final double STIFFEN_D = 5.0;
    /** BZK09 §5.4 "a few uniform smoothing steps". */
    static final int SMOOTH_PASSES = 3;
    /** Lambda assigned to a face whose Jacobian is non-finite (defensive). */
    static final double FLIP_LAMBDA_CAP = 5.0;
    /** Threshold for "positive UV signed area"; matches IterativeRounding. */
    static final double POS_AREA_EPS = 1e-9;

    private LocalStiffening() {}

    static final class Result {
        final float[] u;
        final float[] v;
        final int iterations;
        final boolean injective;

        Result(float[] u, float[] v, int iterations, boolean injective) {
            this.u = u;
            this.v = v;
            this.iterations = iterations;
            this.injective = injective;
        }
    }

    /**
     * Run IRLS on top of an already-relaxed (u, v). The Hessian's per-face
     * stiffness weights are mutated in place each iteration; the final weights
     * are left installed so any subsequent {@link IterativeRounding} re-solves
     * use the same biased system (BZK09's intent: distortion-aware weights
     * carry into the rounding stage).
     */
    static Result refine(IgmHessian H, float[] uIn, float[] vIn) {
        boolean diag = "true".equals(System.getProperty("ixdar.lyon.paramDiag"));
        int F = H.faceCount;
        int C = F * 3;
        float[] u = uIn.clone();
        float[] v = vIn.clone();

        if (F == 0) return new Result(u, v, 0, true);

        double hTarget = targetGradientMagnitude(H);
        int[][] faceNeighbors = buildFaceNeighbors(H);
        double[] weights = new double[F];
        Arrays.fill(weights, 1.0);

        if (diag) System.err.printf("[stiffening-diag] entry: F=%d hTarget=%.4f%n", F, hTarget);

        // BZK09 §5.4 doesn't drive λ to zero — IRLS just balances stiffness
        // across faces, so the right termination is "is the map injective
        // yet?" plus a best-so-far guard so a degraded final solve can't lose
        // an injective intermediate.
        float[] uBest = u.clone();
        float[] vBest = v.clone();
        int bestFlips = countNonPositive(u, v, F);

        int iter;
        boolean injective = false;
        for (iter = 0; iter < MAX_ITER; iter++) {
            int flips = countNonPositive(u, v, F);
            if (flips < bestFlips) {
                bestFlips = flips;
                uBest = u.clone();
                vBest = v.clone();
            }
            if (flips == 0) {
                injective = true;
                if (diag) System.err.printf(
                        "[stiffening-diag] iter=%d flips=0 → CONVERGED%n", iter);
                break;
            }

            double[] lambda = new double[F];
            double maxLambda = 0;
            for (int f = 0; f < F; f++) {
                lambda[f] = computeLambda(H, u, v, f, hTarget);
                if (lambda[f] > maxLambda) maxLambda = lambda[f];
            }
            if (diag) System.err.printf(
                    "[stiffening-diag] iter=%d flips=%d maxLambda=%.3f bestFlips=%d%n",
                    iter, flips, maxLambda, bestFlips);

            for (int f = 0; f < F; f++) {
                weights[f] += Math.min(STIFFEN_C * 4.0 * lambda[f], STIFFEN_D);
            }
            for (int p = 0; p < SMOOTH_PASSES; p++) {
                weights = smoothFaceLaplacian(weights, faceNeighbors);
            }

            H.setStiffening(weights);
            double[] x = H.solveWithPins(null, null, null, null);
            if (x == null || !allFinite(x)) {
                if (diag) System.err.printf(
                        "[stiffening-diag] iter=%d solver returned non-finite; bailing%n", iter);
                break;
            }
            for (int f = 0; f < F; f++) {
                for (int c = 0; c < 3; c++) {
                    int cv = H.chart.chartVertexAt(f, c);
                    int corner = f * 3 + c;
                    u[corner] = (float) x[H.uBase + cv];
                    v[corner] = (float) x[H.vBase + cv];
                }
            }
        }

        // Final check: if the loop's last iteration regressed, fall back to
        // the best (u, v) we saw.
        int finalFlips = countNonPositive(u, v, F);
        if (finalFlips > bestFlips) {
            u = uBest;
            v = vBest;
            finalFlips = bestFlips;
        }
        injective = (finalFlips == 0);
        if (diag) System.err.printf(
                "[stiffening-diag] EXIT iters=%d finalFlips=%d injective=%s%n",
                iter, finalFlips, injective);
        return new Result(u, v, iter, injective);
    }

    /** Count faces whose UV signed area is non-positive (flip or degenerate). */
    private static int countNonPositive(float[] u, float[] v, int F) {
        int n = 0;
        for (int f = 0; f < F; f++) {
            int o = f * 3;
            float a = 0.5f * ((u[o + 1] - u[o]) * (v[o + 2] - v[o])
                            - (u[o + 2] - u[o]) * (v[o + 1] - v[o]));
            if (!(a > POS_AREA_EPS)) n++;
        }
        return n;
    }

    /**
     * Magnitude of the gradient targets baked into {@link IgmHessian#uTarget} —
     * the ideal singular value of the per-face Jacobian. Equals the global
     * scale parameter (cos²+sin² = 1, then scaled by {@code 1/q}, post-rescale,
     * etc.). Falls back to 1.0 when the targets are zero.
     */
    private static double targetGradientMagnitude(IgmHessian H) {
        double tx = H.uTarget[0];
        double ty = H.uTarget[1];
        double s = Math.sqrt(tx * tx + ty * ty);
        return s > 1e-12 ? s : 1.0;
    }

    /** Build dual-mesh adjacency: up to 3 face-neighbors per face. */
    private static int[][] buildFaceNeighbors(IgmHessian H) {
        int F = H.faceCount;
        int[][] nbr = new int[F][3];
        int[] count = new int[F];
        for (int f = 0; f < F; f++) {
            nbr[f][0] = -1; nbr[f][1] = -1; nbr[f][2] = -1;
        }
        int Ei = H.interiorEdgeCount;
        for (int e = 0; e < Ei; e++) {
            int fa = H.field.edgeFaceA(e);
            int fb = H.field.edgeFaceB(e);
            if (fa < 0 || fb < 0) continue;
            if (count[fa] < 3) nbr[fa][count[fa]++] = fb;
            if (count[fb] < 3) nbr[fb][count[fb]++] = fa;
        }
        return nbr;
    }

    /** Uniform Laplacian smoothing: each face becomes the mean of itself and
     *  its (up-to-3) face neighbors. */
    private static double[] smoothFaceLaplacian(double[] w, int[][] nbr) {
        int F = w.length;
        double[] out = new double[F];
        for (int f = 0; f < F; f++) {
            double sum = w[f];
            int n = 1;
            for (int k = 0; k < 3; k++) {
                int nb = nbr[f][k];
                if (nb >= 0) {
                    sum += w[nb];
                    n++;
                }
            }
            out[f] = sum / n;
        }
        return out;
    }

    /**
     * Per-face HLS isometric distortion (BZK09 §5.4 / Hormann-Lévy-Sheffer
     * 2007). Computes the 2×2 Jacobian of the (u, v) → tangent-frame map from
     * the per-face local 2D coordinates {@link IgmHessian#localQ} and the
     * per-corner UVs, then returns
     * {@code |τ σ₁/h − 1| + |τ σ₂/h − 1|}.
     */
    private static double computeLambda(IgmHessian H, float[] u, float[] v, int f, double h) {
        int o = f * 6;
        float q0u = H.localQ[o],     q0v = H.localQ[o + 1];
        float q1u = H.localQ[o + 2], q1v = H.localQ[o + 3];
        float q2u = H.localQ[o + 4], q2v = H.localQ[o + 5];
        double sa = 0.5 * ((q1u - q0u) * (q2v - q0v) - (q2u - q0u) * (q1v - q0v));
        if (Math.abs(sa) < 1e-20) return 0.0;
        double inv2A = 1.0 / (2.0 * sa);
        double b0 = (q1v - q2v) * inv2A, c0 = (q2u - q1u) * inv2A;
        double b1 = (q2v - q0v) * inv2A, c1 = (q0u - q2u) * inv2A;
        double b2 = (q0v - q1v) * inv2A, c2 = (q1u - q0u) * inv2A;

        int oc = f * 3;
        double u0 = u[oc],     v0 = v[oc];
        double u1 = u[oc + 1], v1 = v[oc + 1];
        double u2 = u[oc + 2], v2 = v[oc + 2];

        double J11 = u0 * b0 + u1 * b1 + u2 * b2;
        double J12 = u0 * c0 + u1 * c1 + u2 * c2;
        double J21 = v0 * b0 + v1 * b1 + v2 * b2;
        double J22 = v0 * c0 + v1 * c1 + v2 * c2;

        double det = J11 * J22 - J12 * J21;
        double tau = det >= 0 ? 1.0 : -1.0;

        double[] sv = svd2x2(J11, J12, J21, J22);
        double s1 = sv[0];
        double s2 = sv[1];

        double l = Math.abs(tau * s1 / h - 1.0) + Math.abs(tau * s2 / h - 1.0);
        if (Double.isNaN(l) || Double.isInfinite(l)) return FLIP_LAMBDA_CAP;
        return Math.min(l, FLIP_LAMBDA_CAP);
    }

    /**
     * Singular values of a 2×2 matrix, largest first. Closed form via
     * eigendecomposition of MᵀM:
     * {@code σ₁² = ((‖M‖_F² + Δ)/2)}, {@code σ₂² = ((‖M‖_F² − Δ)/2)}
     * where {@code Δ = √(‖M‖_F⁴/4 − det²) = (σ₁² − σ₂²)/2}.
     */
    private static double[] svd2x2(double a, double b, double c, double d) {
        double frob2 = a * a + b * b + c * c + d * d;
        double det = a * d - b * c;
        double diff = Math.sqrt(Math.max(frob2 * frob2 / 4.0 - det * det, 0.0));
        double half = frob2 / 2.0;
        double sigma1Sq = Math.max(half + diff, 0.0);
        double sigma2Sq = Math.max(half - diff, 0.0);
        return new double[]{Math.sqrt(sigma1Sq), Math.sqrt(sigma2Sq)};
    }

    private static boolean allPositive(float[] u, float[] v, int F) {
        for (int f = 0; f < F; f++) {
            int o = f * 3;
            float a = 0.5f * ((u[o + 1] - u[o]) * (v[o + 2] - v[o])
                            - (u[o + 2] - u[o]) * (v[o + 1] - v[o]));
            if (!(a > POS_AREA_EPS)) return false;
        }
        return true;
    }

    private static boolean allFinite(double[] x) {
        for (double xi : x) if (!Double.isFinite(xi)) return false;
        return true;
    }
}
