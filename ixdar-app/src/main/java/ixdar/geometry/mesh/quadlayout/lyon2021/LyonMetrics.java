package ixdar.geometry.mesh.quadlayout.lyon2021;

import java.util.List;

import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.extraction.TransitionMatrix;
import ixdar.geometry.mesh.quadlayout.tmesh.TArc;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;

/**
 * Lyon 2021 Table 1 metrics: {@code dmean}, {@code dmax}, {@code MSJavg}.
 *
 * <p><b>{@code dmean / dmax}</b> = parametric-length-weighted mean / max
 * angular deviation of a layout arc's per-step direction from its
 * prescribed cardinal direction (within the locally-rotated frame after
 * applying {@link TransitionMatrix#matching} across seams).
 *
 * <p>For an arc whose direction is {@code +u}, every per-step
 * {@code (Δu, Δv)} should lie on the {@code +u} axis; any {@code Δv}
 * component is deviation. Deviation is reported in degrees in
 * {@code [0°, 90°)}; the user's input bound {@code α} (typically 5°-45°)
 * caps it.
 *
 * <p>Walks the {@link QuadLayout#layoutArcs()} registry, distinguishing
 * INHERITED arcs (computed from underlying {@link TArc#stepUvs()}) from
 * INTERIOR arcs (computed from {@link LayoutArc#interiorPolyline()}).
 *
 * <p><b>Caveat vs Lyon paper Table 1.</b> The paper's {@code dmean} (e.g.
 * 3.7° for ROCKERARM) measures the resulting <i>parametrization</i>'s
 * deviation from the <i>input cross field</i>. Our metric measures layout
 * arcs' deviation from the <i>iso-lines of the same parametrization</i> —
 * which is 0° by construction since motorcycle traces follow those
 * iso-lines exactly. To replicate the paper's number we'd compute the
 * angle between each face's gradient(u, v) and the cross field's matched
 * direction. Tracked separately; this metric still reports a sane number
 * for sanity-checking layout-arc tangent stability.
 *
 * <p><b>{@code MSJavg}</b> = average minimum scaled Jacobian over the
 * dense visualization quads. Requires the LCBK19 dense-quad pass
 * (PATCH-75); reported as {@code NaN} until that lands.
 */
public final class LyonMetrics {
    public static final int NUM_4 = 4;
    public static final int NUM_3 = 3;
    public static final double NUM_1e_9 = 1e-9;
    public static final double NUM_1e_12 = 1e-12;

    private LyonMetrics() {}

    /**
     * Compute dmean / dmax over every layout arc that participates in any
     *  conforming layout patch.
     *
     *  <p>Pre-PATCH-78 overload: assumes per-step deltas are already in the
     *  arc's primary frame. Correct only for arcs that don't traverse seam
     *  edges — on real meshes prefer the {@link #compute(QuadLayout, TMesh,
     *  ArrayMesh, TransitionMatrix)} overload.
     *
     * @param layout the conforming Lyon 2021 layout
     * @param tmesh  T-mesh the layout was built from
     * @return result with {@code dmean / dmax} populated and {@code msjAvg}
     *         set to {@link Double#NaN}
     */
    public static Result compute(QuadLayout layout, TMesh tmesh) {
        return computeImpl(layout, tmesh, null, null);
    }

    /**
     * Compute dmean / dmax with TRS rotation applied across seams (PATCH-78).
     *  Each arc's prescribed direction is rotated when crossing seam half-edges
     *  using {@link TransitionMatrix#matching}, so per-step deviation is
     *  measured against the locally-correct cardinal in each face's frame.
     *
     * @param layout the conforming Lyon 2021 layout
     * @param tmesh  T-mesh the layout was built from
     * @param mesh   underlying triangle mesh (for half-edge index lookups)
     * @param trs    transition matrix providing per-half-edge matchings
     * @return result with {@code dmean / dmax} populated and {@code msjAvg}
     *         set to {@link Double#NaN}
     */
    public static Result compute(QuadLayout layout, TMesh tmesh,
                                  ArrayMesh mesh, TransitionMatrix trs) {
        return computeImpl(layout, tmesh, mesh, trs);
    }

    private static Result computeImpl(QuadLayout layout, TMesh tmesh,
                                       ArrayMesh mesh, TransitionMatrix trs) {
        // Mark which LayoutArc IDs participate (both quads + triangles).
        boolean[] inLayout = new boolean[layout.layoutArcs().size()];
        for (QuadLayoutPatch p : layout.patches()) {
            for (int s = 0; s < NUM_4; s++) {
                for (int la : p.arcsBySide()[s]) inLayout[la] = true;
            }
        }
        for (TrianglePatch t : layout.triangles()) {
            for (int s = 0; s < NUM_3; s++) {
                for (int la : t.arcsBySide()[s]) inLayout[la] = true;
            }
        }

        double weightedSum = 0;
        double totalLength = 0;
        double maxDev = 0;
        int arcsMeasured = 0;
        int stepsMeasured = 0;

        for (LayoutArc la : layout.layoutArcs()) {
            if (!inLayout[la.id()]) continue;
            arcsMeasured++;

            if (la.variant() == LayoutArc.Variant.INHERITED
                    || la.variant() == LayoutArc.Variant.DERIVED) {
                TArc tarc = tmesh.arcs().get(la.underlyingTArcId());
                List<float[]> steps = tarc.stepUvs();
                if (steps.isEmpty()) continue;
                var crossings = tarc.meshFaceCrossings();
                // Derive the initial cardinal from the first non-degenerate
                // step rather than trusting arc.direction() — Motorcycle.java
                // doesn't always set direction in the first-face's frame
                // (PATCH-78 finding). For subsequent steps we still apply
                // TRS rotation across seams.
                int currentDir = inferDirectionFromFirstStep(steps, la.direction());
                for (int i = 0; i < steps.size(); i++) {
                    if (i > 0 && trs != null) {
                        // Half-edge crossed exiting step i-1 = faceId * 3 +
                        // exitEdgeIndex. matching[h] gives the rotation FROM
                        // prev-face frame INTO this-face frame; apply it to
                        // currentDir.
                        int[] prev = crossings.get(i - 1);
                        if (prev[1] >= 0) {
                            int h = prev[0] * NUM_3 + prev[1];
                            currentDir = (currentDir + trs.matching[h]) & NUM_3;
                        }
                    }
                    float[] s = steps.get(i);
                    stepsMeasured++;
                    double du = s[2] - s[0];
                    double dv = s[NUM_3] - s[1];
                    double devRad = stepDeviation(du, dv, currentDir);
                    double len = Math.hypot(du, dv);
                    weightedSum += devRad * len;
                    totalLength += len;
                    if (devRad > maxDev) maxDev = devRad;
                }
            } else {
                // INTERIOR — polyline already in consistent traced frame, no
                // TRS rotation needed (SplitArcTracer applied it inline).
                int dir = la.direction();
                for (SplitEdge e : la.interiorPolyline()) {
                    stepsMeasured++;
                    double du = e.u2() - e.u1();
                    double dv = e.v2() - e.v1();
                    double devRad = stepDeviation(du, dv, dir);
                    double len = Math.hypot(du, dv);
                    weightedSum += devRad * len;
                    totalLength += len;
                    if (devRad > maxDev) maxDev = devRad;
                }
            }
        }
        double dmeanRad = totalLength > 0 ? weightedSum / totalLength : 0;
        return new Result(layout.patchCount(),
                Math.toDegrees(dmeanRad),
                Math.toDegrees(maxDev),
                Double.NaN,
                arcsMeasured,
                stepsMeasured);
    }

    /**
     * Pick the cardinal direction whose axis best matches the first
     * non-degenerate step's UV delta. Falls back to {@code declared} if every
     * step has zero length. Used to recover from arc.direction() values that
     * were set in a different frame than the first-face's local frame.
     *
     * @param steps    arc step UV deltas (each {@code [u1, v1, u2, v2]})
     * @param declared {@link LayoutArc#direction()} fallback when every step
     *                 is degenerate
     * @return a cardinal index in {@code {0, 1, 2, 3}} for {+u, +v, -u, -v}
     */
    private static int inferDirectionFromFirstStep(List<float[]> steps, int declared) {
        for (float[] s : steps) {
            double du = s[2] - s[0];
            double dv = s[NUM_3] - s[1];
            if (Math.hypot(du, dv) < NUM_1e_9) continue;
            double absDu = Math.abs(du);
            double absDv = Math.abs(dv);
            if (absDu >= absDv) return du >= 0 ? 0 : 2;   // +u or -u
            return dv >= 0 ? 1 : NUM_3;                        // +v or -v
        }
        return declared;
    }

    /**
     * Angular deviation (radians, ≥0) of a per-step UV delta from its
     * prescribed cardinal axis.
     * <ul>
     *   <li>{@code dir = 0 or 2} (u-axis): deviation = atan(|Δv| / |Δu|)</li>
     *   <li>{@code dir = 1 or 3} (v-axis): deviation = atan(|Δu| / |Δv|)</li>
     * </ul>
     *
     * @param du  per-step UV delta along u
     * @param dv  per-step UV delta along v
     * @param dir cardinal direction index in {@code {0, 1, 2, 3}}
     * @return non-negative deviation in radians, or {@code 0} if the step is
     *         degenerate (length below numerical threshold)
     */
    private static double stepDeviation(double du, double dv, int dir) {
        double along, ortho;
        if ((dir & 1) == 0) {        // u-axis
            along = Math.abs(du);
            ortho = Math.abs(dv);
        } else {                      // v-axis
            along = Math.abs(dv);
            ortho = Math.abs(du);
        }
        if (along < NUM_1e_12 && ortho < NUM_1e_12) return 0;
        return Math.atan2(ortho, along);
    }

    public record Result(int patchCount,
                         double dmeanDeg,
                         double dmaxDeg,
                         double msjAvg,
                         int arcsMeasured,
                         int stepsMeasured) {}
}
