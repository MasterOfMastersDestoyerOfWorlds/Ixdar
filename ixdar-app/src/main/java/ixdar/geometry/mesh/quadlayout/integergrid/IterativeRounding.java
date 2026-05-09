package ixdar.geometry.mesh.quadlayout.integergrid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import ixdar.geometry.mesh.data.representation.ArrayMesh;

/**
 * Iterative rounding loop (Bommes-Campen-Ebke-Alliez-Kobbelt 2013) on top of
 * the relaxed aligned-parametrization solve. PATCH-54 retargeted to the
 * per-vertex / chart-vertex variable layout.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Solve unconstrained -> relaxed (u, v) per chart-vertex.</li>
 *   <li>Round every chart-vertex whose mesh vertex is a singularity to integer
 *       (u, v) and pin it. Re-solve.</li>
 *   <li>Loop:
 *     <ul>
 *       <li>Score every non-pinned, non-blacklisted chart-vertex by min
 *           fractional distance to integer for either u or v.</li>
 *       <li>Tentatively pin the closest one to its rounded value.</li>
 *       <li>Re-solve and check that every triangle's UV signed area is positive.</li>
 *       <li>If injectivity is preserved -> commit pin. Else -> revert and
 *           blacklist this chart-vertex+axis pair.</li>
 *     </ul>
 *   </li>
 *   <li>Stop when the candidate pool is exhausted.</li>
 * </ol>
 *
 * <p>Pin granularity is per chart-vertex (not per corner): all face corners
 * on the same vertex inside a chart share one variable, so pinning that
 * variable simultaneously fixes every corner that references it. This is
 * exactly the Bommes 2013 grain.
 */
final class IterativeRounding {
    public static final int NUM_3 = 3;
    public static final float NUM_0_5 = 0.5f;

    /** Minimum signed area required for a triangle to count as "positively oriented". */
    private static final float POSITIVE_AREA_EPS = 1e-7f;

    /**
     * PATCH-110 adaptive-threshold batch pinning thresholds. Mirror of
     *  PATCH-103 / GreedyRounding.solve(). Each pass commits all candidates
     *  with frac < threshold simultaneously; on injectivity failure, falls
     */
    private static final double[] BATCH_FRAC_THRESHOLDS = {
            0.01, 0.05, 0.10, 0.25, 0.50};

    private final IgmHessian H;
    private final ArrayMesh mesh;
    private final int F;
    private final int numCV;

    /** Pin / blacklist state addressed by chart-vertex id (not by corner). */
    private final boolean[] uPinned;
    private final boolean[] vPinned;
    private final double[] uPinVal;
    private final double[] vPinVal;
    private final boolean[] uBlacklist;
    private final boolean[] vBlacklist;

    private float[] uCV;   // [numCV]: current u per chart-vertex
    private float[] vCV;   // [numCV]: current v per chart-vertex
    private float[] uCornerCurrent;
    private float[] vCornerCurrent;

    IterativeRounding(IgmHessian H) {
        this.H = H;
        this.mesh = H.mesh;
        this.F = H.faceCount;
        this.numCV = H.chart.chartVertexCount;
        this.uPinned = new boolean[numCV];
        this.vPinned = new boolean[numCV];
        this.uPinVal = new double[numCV];
        this.vPinVal = new double[numCV];
        this.uBlacklist = new boolean[numCV];
        this.vBlacklist = new boolean[numCV];
    }

    int numCV() { return numCV; }

    /**
     * Pre-pin every chart-vertex whose mesh vertex is in
     * {@code singularityVertices}. The seed value comes from the relaxed
     * solve passed in via {@code uRelax}/{@code vRelax}, which are still
     * indexed per-corner — we look up one corner per chart-vertex and round
     * its relaxed (u, v) to the nearest integer.
     */
    void seedSingularityPins(int[] singularityVertices, float[] uRelax, float[] vRelax) {
        HashSet<Integer> singSet = new HashSet<>();
        for (int v : singularityVertices) singSet.add(v);
        boolean[] seeded = new boolean[numCV];
        for (int f = 0; f < F; f++) {
            for (int c = 0; c < NUM_3; c++) {
                int vid = mesh.faceVertexAt(f, c);
                if (!singSet.contains(vid)) continue;
                int cv = H.chart.chartVertexAt(f, c);
                if (seeded[cv]) continue;
                seeded[cv] = true;
                int corner = f * NUM_3 + c;
                uPinned[cv] = true;
                vPinned[cv] = true;
                uPinVal[cv] = Math.round(uRelax[corner]);
                vPinVal[cv] = Math.round(vRelax[corner]);
            }
        }
    }

    private int countPositive(float[] u, float[] v) {
        int positive = 0;
        for (int f = 0; f < F; f++) {
            int o = f * NUM_3;
            float a = NUM_0_5 * ((u[o + 1] - u[o]) * (v[o + 2] - v[o])
                            - (u[o + 2] - u[o]) * (v[o + 1] - v[o]));
            if (a > POSITIVE_AREA_EPS) positive++;
        }
        return positive;
    }

    Result run(int maxIterations) {
        // Initial solve with the singularity pins.
        if (!resolveAndExtract()) {
            return makeResult(0, false);
        }

        int iter = 0;
        for (double thresh : BATCH_FRAC_THRESHOLDS) {
            if (iter >= maxIterations) break;

            // Collect candidates: (cv, axis, rounded) for all unpinned,
            // un-blacklisted variables with frac < thresh.
            int[] candCv = new int[2 * numCV];
            int[] candAxis = new int[2 * numCV];
            double[] candRounded = new double[2 * numCV];
            int candN = 0;
            for (int cv = 0; cv < numCV; cv++) {
                if (!uPinned[cv] && !uBlacklist[cv]) {
                    double frac = fracDist(uCV[cv]);
                    if (frac < thresh) {
                        candCv[candN] = cv;
                        candAxis[candN] = 0;
                        candRounded[candN] = Math.round(uCV[cv]);
                        candN++;
                    }
                }
                if (!vPinned[cv] && !vBlacklist[cv]) {
                    double frac = fracDist(vCV[cv]);
                    if (frac < thresh) {
                        candCv[candN] = cv;
                        candAxis[candN] = 1;
                        candRounded[candN] = Math.round(vCV[cv]);
                        candN++;
                    }
                }
            }
            if (candN == 0) continue;

            // Snapshot state for whole-batch revert.
            float[] uBackup = uCV;
            float[] vBackup = vCV;
            float[] uCornerBackup = uCornerCurrent;
            float[] vCornerBackup = vCornerCurrent;
            boolean[] wasUPinned = uPinned.clone();
            double[] wasUPinVal = uPinVal.clone();
            boolean[] wasVPinned = vPinned.clone();
            double[] wasVPinVal = vPinVal.clone();
            int prevPos = countPositive(uCornerCurrent, vCornerCurrent);

            // Tentatively pin all candidates in this batch.
            for (int k = 0; k < candN; k++) {
                int cv = candCv[k];
                if (candAxis[k] == 0) {
                    uPinned[cv] = true;
                    uPinVal[cv] = candRounded[k];
                } else {
                    vPinned[cv] = true;
                    vPinVal[cv] = candRounded[k];
                }
            }
            iter += candN;
            boolean ok = resolveAndExtract();
            if (ok && countPositive(uCornerCurrent, vCornerCurrent) >= prevPos) {
                // Batch committed successfully.
                continue;
            }

            // Batch failed injectivity → revert, then fall back to per-pin
            // reject-and-retry within just this batch's candidates.
            System.arraycopy(wasUPinned, 0, uPinned, 0, numCV);
            System.arraycopy(wasUPinVal, 0, uPinVal, 0, numCV);
            System.arraycopy(wasVPinned, 0, vPinned, 0, numCV);
            System.arraycopy(wasVPinVal, 0, vPinVal, 0, numCV);
            uCV = uBackup;
            vCV = vBackup;
            uCornerCurrent = uCornerBackup;
            vCornerCurrent = vCornerBackup;
            // Restore solver state by re-solving with original pins.
            if (!resolveAndExtract()) {
                // Defensive: if we can't even re-establish prior state, stop.
                break;
            }

            for (int k = 0; k < candN; k++) {
                if (iter >= maxIterations) break;
                int cv = candCv[k];
                int axis = candAxis[k];
                if (axis == 0 && (uPinned[cv] || uBlacklist[cv])) continue;
                if (axis == 1 && (vPinned[cv] || vBlacklist[cv])) continue;

                float[] uBack2 = uCV;
                float[] vBack2 = vCV;
                float[] uCB2 = uCornerCurrent;
                float[] vCB2 = vCornerCurrent;
                int prevPos2 = countPositive(uCornerCurrent, vCornerCurrent);
                if (axis == 0) {
                    uPinned[cv] = true;
                    uPinVal[cv] = candRounded[k];
                } else {
                    vPinned[cv] = true;
                    vPinVal[cv] = candRounded[k];
                }
                boolean ok2 = resolveAndExtract();
                if (ok2 && countPositive(uCornerCurrent, vCornerCurrent) >= prevPos2) {
                    continue;
                }
                if (axis == 0) {
                    uPinned[cv] = false;
                    uPinVal[cv] = 0.0;
                    uBlacklist[cv] = true;
                } else {
                    vPinned[cv] = false;
                    vPinVal[cv] = 0.0;
                    vBlacklist[cv] = true;
                }
                uCV = uBack2;
                vCV = vBack2;
                uCornerCurrent = uCB2;
                vCornerCurrent = vCB2;
            }
        }

        boolean injective = allPositiveOrient(uCornerCurrent, vCornerCurrent);
        return makeResult(iter, injective);
    }

    private Result makeResult(int iter, boolean injective) {
        return new Result(uCornerCurrent, vCornerCurrent,
                Arrays.copyOf(uPinned, uPinned.length),
                Arrays.copyOf(vPinned, vPinned.length),
                iter, injective);
    }

    private boolean resolveAndExtract() {
        List<Integer> uIdx = new ArrayList<>();
        List<Double> uVal = new ArrayList<>();
        List<Integer> vIdx = new ArrayList<>();
        List<Double> vVal = new ArrayList<>();
        for (int cv = 0; cv < numCV; cv++) {
            if (uPinned[cv]) {
                uIdx.add(H.uBase + cv);
                uVal.add(uPinVal[cv]);
            }
            if (vPinned[cv]) {
                vIdx.add(H.vBase + cv);
                vVal.add(vPinVal[cv]);
            }
        }
        int[] uIdxArr = uIdx.stream().mapToInt(Integer::intValue).toArray();
        double[] uValArr = uVal.stream().mapToDouble(Double::doubleValue).toArray();
        int[] vIdxArr = vIdx.stream().mapToInt(Integer::intValue).toArray();
        double[] vValArr = vVal.stream().mapToDouble(Double::doubleValue).toArray();

        double[] x;
        try {
            x = H.solveWithPins(uIdxArr, uValArr, vIdxArr, vValArr);
        } catch (Exception ex) {
            return false;
        }
        float[] uChart = new float[numCV];
        float[] vChart = new float[numCV];
        for (int cv = 0; cv < numCV; cv++) {
            uChart[cv] = (float) x[H.uBase + cv];
            vChart[cv] = (float) x[H.vBase + cv];
            if (!Float.isFinite(uChart[cv]) || !Float.isFinite(vChart[cv])) return false;
        }
        // Project to per-corner for triangle-orientation checks + downstream API.
        int totalCorners = F * NUM_3;
        float[] uCorner = new float[totalCorners];
        float[] vCorner = new float[totalCorners];
        for (int f = 0; f < F; f++) {
            for (int c = 0; c < NUM_3; c++) {
                int cv = H.chart.chartVertexAt(f, c);
                uCorner[f * NUM_3 + c] = uChart[cv];
                vCorner[f * NUM_3 + c] = vChart[cv];
            }
        }
        uCV = uChart;
        vCV = vChart;
        uCornerCurrent = uCorner;
        vCornerCurrent = vCorner;
        return true;
    }

    private boolean allPositiveOrient(float[] u, float[] v) {
        for (int f = 0; f < F; f++) {
            int o = f * NUM_3;
            float u0 = u[o], v0 = v[o];
            float u1 = u[o + 1], v1 = v[o + 1];
            float u2 = u[o + 2], v2 = v[o + 2];
            float a = NUM_0_5 * ((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0));
            if (a <= POSITIVE_AREA_EPS) return false;
        }
        return true;
    }

    private static double fracDist(double x) {
        double r = Math.round(x);
        return Math.abs(x - r);
    }

    static final class Result {
        final float[] uCorner;
        final float[] vCorner;
        final boolean[] uPinnedCV;
        final boolean[] vPinnedCV;
        final int iterationCount;
        final boolean injective;

        Result(float[] uCorner, float[] vCorner,
               boolean[] uPinnedCV, boolean[] vPinnedCV,
               int iterationCount, boolean injective) {
            this.uCorner = uCorner;
            this.vCorner = vCorner;
            this.uPinnedCV = uPinnedCV;
            this.vPinnedCV = vPinnedCV;
            this.iterationCount = iterationCount;
            this.injective = injective;
        }
    }
}
