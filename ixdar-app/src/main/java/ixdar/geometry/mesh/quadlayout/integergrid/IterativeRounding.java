package ixdar.geometry.mesh.quadlayout.integergrid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import ixdar.geometry.mesh.data.ArrayMesh;

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

    /** Minimum signed area required for a triangle to count as "positively oriented". */
    private static final float POSITIVE_AREA_EPS = 1e-7f;

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

    int numCV() { return numCV; }

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
            for (int c = 0; c < 3; c++) {
                int vid = mesh.faceVertexAt(f, c);
                if (!singSet.contains(vid)) continue;
                int cv = H.chart.chartVertexAt(f, c);
                if (seeded[cv]) continue;
                seeded[cv] = true;
                int corner = f * 3 + c;
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
            int o = f * 3;
            float a = 0.5f * ((u[o + 1] - u[o]) * (v[o + 2] - v[o])
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
        while (iter < maxIterations) {
            int bestCV = -1;
            int bestAxis = -1; // 0 = u, 1 = v
            double bestFrac = Double.POSITIVE_INFINITY;
            for (int cv = 0; cv < numCV; cv++) {
                if (!uPinned[cv] && !uBlacklist[cv]) {
                    double frac = fracDist(uCV[cv]);
                    if (frac < bestFrac) {
                        bestFrac = frac;
                        bestCV = cv;
                        bestAxis = 0;
                    }
                }
                if (!vPinned[cv] && !vBlacklist[cv]) {
                    double frac = fracDist(vCV[cv]);
                    if (frac < bestFrac) {
                        bestFrac = frac;
                        bestCV = cv;
                        bestAxis = 1;
                    }
                }
            }
            if (bestCV < 0) break;

            float currentVal = (bestAxis == 0) ? uCV[bestCV] : vCV[bestCV];
            double rounded = Math.round(currentVal);
            float[] uBackup = uCV;
            float[] vBackup = vCV;
            float[] uCornerBackup = uCornerCurrent;
            float[] vCornerBackup = vCornerCurrent;
            int prevPos = countPositive(uCornerCurrent, vCornerCurrent);
            if (bestAxis == 0) {
                uPinned[bestCV] = true;
                uPinVal[bestCV] = rounded;
            } else {
                vPinned[bestCV] = true;
                vPinVal[bestCV] = rounded;
            }
            iter++;

            boolean ok = resolveAndExtract();
            if (ok && countPositive(uCornerCurrent, vCornerCurrent) >= prevPos) {
                continue;
            }
            // Revert pin and blacklist this axis.
            if (bestAxis == 0) {
                uPinned[bestCV] = false;
                uPinVal[bestCV] = 0.0;
                uBlacklist[bestCV] = true;
            } else {
                vPinned[bestCV] = false;
                vPinVal[bestCV] = 0.0;
                vBlacklist[bestCV] = true;
            }
            uCV = uBackup;
            vCV = vBackup;
            uCornerCurrent = uCornerBackup;
            vCornerCurrent = vCornerBackup;
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
        int totalCorners = F * 3;
        float[] uCorner = new float[totalCorners];
        float[] vCorner = new float[totalCorners];
        for (int f = 0; f < F; f++) {
            for (int c = 0; c < 3; c++) {
                int cv = H.chart.chartVertexAt(f, c);
                uCorner[f * 3 + c] = uChart[cv];
                vCorner[f * 3 + c] = vChart[cv];
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
            int o = f * 3;
            float u0 = u[o], v0 = v[o];
            float u1 = u[o + 1], v1 = v[o + 1];
            float u2 = u[o + 2], v2 = v[o + 2];
            float a = 0.5f * ((u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0));
            if (a <= POSITIVE_AREA_EPS) return false;
        }
        return true;
    }

    private static double fracDist(double x) {
        double r = Math.round(x);
        return Math.abs(x - r);
    }
}
