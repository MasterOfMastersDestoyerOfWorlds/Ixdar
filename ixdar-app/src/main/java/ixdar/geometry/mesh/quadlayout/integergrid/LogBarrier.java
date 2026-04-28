package ixdar.geometry.mesh.quadlayout.integergrid;

import ixdar.geometry.mesh.quadlayout.solver.IterativeSolver;
import ixdar.geometry.mesh.quadlayout.solver.MtjSparseMatrix;
import ixdar.geometry.mesh.quadlayout.solver.SparseLu;
import ixdar.geometry.mesh.quadlayout.solver.SparseMatrix;

/**
 * Log-barrier injectivity refinement (Bommes 2013 Sec 4) for the relaxed
 * aligned-parametrization solve — PATCH-49, retargeted to the per-vertex
 * variable layout (PATCH-54).
 *
 * <p>Augments the IGM energy
 * <pre>
 *   E(u, v) = sum_t || grad_t u - u_t/q ||^2 + || grad_t v - v_t/q ||^2
 * </pre>
 * with the per-triangle log-barrier
 * <pre>
 *   B(u, v) = -w * sum_t log( 2 * uvSignedArea(t) )
 * </pre>
 * which diverges as any triangle approaches degeneracy.
 *
 * <p>The Newton step solves the augmented system on the chart-vertex layout
 * exposed by {@link IgmHessian} (variables indexed by chart-vertex id, not
 * face corner). Triangle-level Jacobians address chart-vertex columns via
 * {@code H.chart.chartVertexAt(face, cornerIdx)}.
 *
 * <p>Linear systems use ojAlgo's {@link SparseLu} direct solver — at
 * per-vertex sizes (N ≤ 33k for Hand-30k, ≤ 23k for rocker-arm-20k) this is
 * decisively faster and more reliable than the MTJ iterative path used
 * pre-PATCH-54.
 */
final class LogBarrier {

    static final double DEFAULT_WEIGHT = 1e-3;
    static final int MAX_NEWTON = 20;
    static final int MAX_FLIP_NEWTON = 30;
    static final double MAX_FLIP_WEIGHT = 1e6;
    static final double FLIP_EPS = 1e-6;
    static final double TOL = 1e-6;
    static final double MIN_STEP = 1.0 / 1024.0;
    static final double POS_AREA_EPS = 1e-9;

    private LogBarrier() {}

    /** Result of a Newton refinement. */
    static final class Result {
        final float[] u;
        final float[] v;
        final int iterations;
        final boolean converged;
        final boolean injective;

        Result(float[] u, float[] v, int iterations, boolean converged, boolean injective) {
            this.u = u;
            this.v = v;
            this.iterations = iterations;
            this.converged = converged;
            this.injective = injective;
        }
    }

    static Result refine(IgmHessian H,
                         float[] uIn, float[] vIn,
                         int[] uPinIdx, double[] uPinVal,
                         int[] vPinIdx, double[] vPinVal,
                         double weight) {
        int F = H.faceCount;
        int C = F * 3;
        float[] u = uIn.clone();
        float[] v = vIn.clone();

        // Continuation phase: clear flipped triangles via quadratic penalty.
        int flipIter = 0;
        double flipWeight = 1.0;
        for (int k = 0; k < MAX_FLIP_NEWTON; k++) {
            int flips = countFlipped(u, v, F);
            if (flips == 0) break;
            double[] step = flipPenaltyStep(H, u, v,
                    uPinIdx, uPinVal, vPinIdx, vPinVal, flipWeight);
            if (step == null) break;

            double alpha = 1.0;
            float[] uTry = new float[C];
            float[] vTry = new float[C];
            int prevFlips = flips;
            boolean accepted = false;
            while (alpha >= MIN_STEP) {
                applyStep(uTry, vTry, u, v, step, alpha, H);
                int newFlips = countFlipped(uTry, vTry, F);
                if (newFlips < prevFlips
                        || (newFlips == prevFlips && newFlips < flips)) {
                    accepted = true;
                    break;
                }
                alpha *= 0.5;
            }
            if (!accepted) {
                if (flipWeight >= MAX_FLIP_WEIGHT) break;
                flipWeight = Math.min(flipWeight * 4.0, MAX_FLIP_WEIGHT);
                continue;
            }
            u = uTry;
            v = vTry;
            flipIter++;
        }

        if (!allPositive(u, v, F)) {
            return new Result(u, v, flipIter, false, false);
        }

        int iter;
        boolean converged = false;
        for (iter = 0; iter < MAX_NEWTON; iter++) {
            double[] step = newtonStep(H, u, v, uPinIdx, uPinVal, vPinIdx, vPinVal, weight);
            if (step == null) break;

            double alpha = 1.0;
            float[] uTry = new float[C];
            float[] vTry = new float[C];
            boolean accepted = false;
            while (alpha >= MIN_STEP) {
                applyStep(uTry, vTry, u, v, step, alpha, H);
                if (allPositive(uTry, vTry, F)) {
                    accepted = true;
                    break;
                }
                alpha *= 0.5;
            }
            if (!accepted) break;

            double stepNorm = 0.0;
            for (int c = 0; c < C; c++) {
                double du = uTry[c] - u[c];
                double dv = vTry[c] - v[c];
                stepNorm += du * du + dv * dv;
            }
            stepNorm = Math.sqrt(stepNorm);
            u = uTry;
            v = vTry;
            if (stepNorm < TOL) {
                converged = true;
                iter++;
                break;
            }
        }
        return new Result(u, v, iter, converged, allPositive(u, v, F));
    }

    /**
     * Apply a chart-vertex step to per-corner arrays. {@code step} is indexed
     * by the IgmHessian variable layout (chart-vertex u then v); each face
     * corner picks its update from its chart-vertex slot.
     */
    private static void applyStep(float[] uTry, float[] vTry,
                                  float[] u, float[] v,
                                  double[] step, double alpha, IgmHessian H) {
        int F = H.faceCount;
        for (int f = 0; f < F; f++) {
            for (int c = 0; c < 3; c++) {
                int corner = f * 3 + c;
                int cv = H.chart.chartVertexAt(f, c);
                uTry[corner] = (float) (u[corner] + alpha * step[H.uBase + cv]);
                vTry[corner] = (float) (v[corner] + alpha * step[H.vBase + cv]);
            }
        }
    }

    private static double[] newtonStep(IgmHessian H, float[] u, float[] v,
                                       int[] uPinIdx, double[] uPinVal,
                                       int[] vPinIdx, double[] vPinVal,
                                       double weight) {
        int N = H.N;
        boolean direct = "direct".equals(System.getProperty("ixdar.quadlayout.integergrid.solver", "iterative"));
        SparseMatrix Adirect = direct ? new SparseMatrix(N, N) : null;
        MtjSparseMatrix Aiter = direct ? null : new MtjSparseMatrix(N, N);
        double[] rhs = new double[N];
        if (direct) {
            H.copyBaseInto(Adirect, rhs);
            H.addGaugeAndPins(Adirect, rhs, uPinIdx, uPinVal, vPinIdx, vPinVal);
        } else {
            H.copyBaseIntoMtj(Aiter, rhs);
            H.addGaugeAndPinsMtj(Aiter, rhs, uPinIdx, uPinVal, vPinIdx, vPinVal);
        }

        for (int f = 0; f < H.faceCount; f++) {
            int o = f * 3;
            double u0 = u[o],     v0 = v[o];
            double u1 = u[o + 1], v1 = v[o + 1];
            double u2 = u[o + 2], v2 = v[o + 2];
            double At = 0.5 * ((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0));
            if (At <= POS_AREA_EPS) continue;

            double jU0 = 0.5 * (v1 - v2);
            double jU1 = 0.5 * (v2 - v0);
            double jU2 = 0.5 * (v0 - v1);
            double jV0 = 0.5 * (u2 - u1);
            double jV1 = 0.5 * (u0 - u2);
            double jV2 = 0.5 * (u1 - u0);

            int cv0 = H.chart.chartVertexAt(f, 0);
            int cv1 = H.chart.chartVertexAt(f, 1);
            int cv2 = H.chart.chartVertexAt(f, 2);
            int[] cols = new int[]{
                    H.uBase + cv0, H.uBase + cv1, H.uBase + cv2,
                    H.vBase + cv0, H.vBase + cv1, H.vBase + cv2
            };
            double[] js = new double[]{jU0, jU1, jU2, jV0, jV1, jV2};

            double s = weight / (At * At);
            double linRhs = weight / At;

            double jx = jU0 * u0 + jU1 * u1 + jU2 * u2
                      + jV0 * v0 + jV1 * v1 + jV2 * v2;

            for (int i = 0; i < 6; i++) {
                for (int k = 0; k < 6; k++) {
                    if (direct) Adirect.add(cols[i], cols[k], s * js[i] * js[k]);
                    else        Aiter.add(cols[i], cols[k], s * js[i] * js[k]);
                }
                rhs[cols[i]] += s * js[i] * jx + linRhs * js[i];
            }
        }

        double[] x;
        if (direct) {
            SparseLu lu = new SparseLu();
            if (!lu.decompose(Adirect) || !lu.isSolvable()) return null;
            x = lu.solve(rhs);
        } else {
            x = IterativeSolver.solve(Aiter, rhs);
        }
        return computeDelta(x, u, v, H);
    }

    /**
     * Convert absolute chart-vertex solution into an additive step relative to
     * current per-corner (u, v). The step entries on chart-vertex columns are
     * `xAbs - currentValue`, where currentValue at chart-vertex `cv` is read
     * from any corner that maps to `cv` (all such corners have the same value
     * inside one chart by definition).
     */
    private static double[] computeDelta(double[] xAbs, float[] u, float[] v, IgmHessian H) {
        double[] delta = xAbs.clone();
        int F = H.faceCount;
        int numCV = H.chart.chartVertexCount;
        boolean[] seen = new boolean[numCV];
        for (int f = 0; f < F; f++) {
            for (int c = 0; c < 3; c++) {
                int cv = H.chart.chartVertexAt(f, c);
                if (seen[cv]) continue;
                seen[cv] = true;
                int corner = f * 3 + c;
                delta[H.uBase + cv] -= u[corner];
                delta[H.vBase + cv] -= v[corner];
            }
        }
        return delta;
    }

    private static boolean allPositive(float[] u, float[] v, int F) {
        for (int f = 0; f < F; f++) {
            int o = f * 3;
            float a = 0.5f * ((u[o + 1] - u[o]) * (v[o + 2] - v[o])
                            - (u[o + 2] - u[o]) * (v[o + 1] - v[o]));
            if (a <= POS_AREA_EPS) return false;
        }
        return true;
    }

    private static int countFlipped(float[] u, float[] v, int F) {
        int flipped = 0;
        for (int f = 0; f < F; f++) {
            int o = f * 3;
            float a = 0.5f * ((u[o + 1] - u[o]) * (v[o + 2] - v[o])
                            - (u[o + 2] - u[o]) * (v[o + 1] - v[o]));
            if (a <= FLIP_EPS) flipped++;
        }
        return flipped;
    }

    private static double[] flipPenaltyStep(IgmHessian H, float[] u, float[] v,
                                            int[] uPinIdx, double[] uPinVal,
                                            int[] vPinIdx, double[] vPinVal,
                                            double flipWeight) {
        int N = H.N;
        boolean direct = "direct".equals(System.getProperty("ixdar.quadlayout.integergrid.solver", "iterative"));
        SparseMatrix Adirect = direct ? new SparseMatrix(N, N) : null;
        MtjSparseMatrix Aiter = direct ? null : new MtjSparseMatrix(N, N);
        double[] rhs = new double[N];
        if (direct) {
            H.copyBaseInto(Adirect, rhs);
            H.addGaugeAndPins(Adirect, rhs, uPinIdx, uPinVal, vPinIdx, vPinVal);
        } else {
            H.copyBaseIntoMtj(Aiter, rhs);
            H.addGaugeAndPinsMtj(Aiter, rhs, uPinIdx, uPinVal, vPinIdx, vPinVal);
        }

        double targetArea = 0.05;
        for (int f = 0; f < H.faceCount; f++) {
            int o = f * 3;
            double u0 = u[o],     v0 = v[o];
            double u1 = u[o + 1], v1 = v[o + 1];
            double u2 = u[o + 2], v2 = v[o + 2];
            double At = 0.5 * ((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0));
            if (At >= targetArea) continue;

            double jU0 = 0.5 * (v1 - v2);
            double jU1 = 0.5 * (v2 - v0);
            double jU2 = 0.5 * (v0 - v1);
            double jV0 = 0.5 * (u2 - u1);
            double jV1 = 0.5 * (u0 - u2);
            double jV2 = 0.5 * (u1 - u0);

            int cv0 = H.chart.chartVertexAt(f, 0);
            int cv1 = H.chart.chartVertexAt(f, 1);
            int cv2 = H.chart.chartVertexAt(f, 2);
            int[] cols = new int[]{
                    H.uBase + cv0, H.uBase + cv1, H.uBase + cv2,
                    H.vBase + cv0, H.vBase + cv1, H.vBase + cv2
            };
            double[] js = new double[]{jU0, jU1, jU2, jV0, jV1, jV2};

            double targetTwoA = 2.0 * targetArea;
            for (int i = 0; i < 6; i++) {
                for (int k = 0; k < 6; k++) {
                    if (direct) Adirect.add(cols[i], cols[k], flipWeight * js[i] * js[k]);
                    else        Aiter.add(cols[i], cols[k], flipWeight * js[i] * js[k]);
                }
                rhs[cols[i]] += flipWeight * js[i] * targetTwoA;
            }
        }

        double[] x;
        if (direct) {
            SparseLu lu = new SparseLu();
            if (!lu.decompose(Adirect) || !lu.isSolvable()) return null;
            x = lu.solve(rhs);
        } else {
            x = IterativeSolver.solve(Aiter, rhs);
        }
        return computeDelta(x, u, v, H);
    }
}
