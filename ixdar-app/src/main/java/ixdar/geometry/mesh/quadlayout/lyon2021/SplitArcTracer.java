package ixdar.geometry.mesh.quadlayout.lyon2021;

import java.util.ArrayList;
import java.util.List;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.extraction.TransitionMatrix;
import ixdar.geometry.mesh.quadlayout.tmesh.TArc;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;
import ixdar.geometry.mesh.quadlayout.tmesh.TPatch;

/**
 * PATCH-65 (Lyon 2021 Stage C): trace an iso-line through the surface
 * from a SplitElem on one Tpatch side to the mirrored SplitElem on the
 * opposite side. Mirrors metriko's
 * {@code visualizer::gen_split_arc} (gen_qgp_edge.h ~140 LOC).
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Same-face fast path — if both split-elems live in one face,
 *       emit a single {@link SplitEdge}.</li>
 *   <li>Compute the iso-line direction via the parametric-distance
 *       offsets {@code diff_bgn_side / diff_mid_side / diff_end_side}:
 *       <pre>arg = -atan((diff_end - diff_bgn) / diff_mid)</pre>
 *       Direction = perpendicular to side-0's edge direction, rotated by
 *       {@code arg}.</li>
 *   <li>Walk face by face: find which face edge the ray exits, advance
 *       across the half-edge applying TRS rotation/translation, repeat
 *       until we hit the destination face. Emit a {@link SplitEdge}
 *       per face traversed.</li>
 * </ol>
 *
 * <p>Perf: per-arc cost is O(faces traversed) = typically 1-10 hops.
 * Per-Tpatch cost is O(arcs1 × arcs2) traces.
 */
public final class SplitArcTracer {

    private static final double EPS = 1e-9;
    private static final int MAX_HOPS = 256;

    private SplitArcTracer() {}

    /** A traced split arc: ordered list of per-face {@link SplitEdge}s. */
    public record SplitArc(List<SplitEdge> edges) {}

    /**
     * Trace one iso-line from {@code from} on side {@code beginSide} of
     * {@code patch} to {@code to} on side {@code (beginSide+2) % 4}.
     *
     * <p>This patch-aware overload assumes single-arc-per-side patches (the
     * common pre-T-junction-extension case). Multi-arc-side callers should
     * use the {@link #traceFromArc} overload that takes the begin-side arc
     * and corner explicitly.
     */
    public static SplitArc trace(TMesh tmesh, ArrayMesh mesh,
                                  TPatch patch, double[] sideRSum,
                                  float[] uCorner, float[] vCorner,
                                  TransitionMatrix trs,
                                  int beginSide,
                                  SplitElem from, SplitElem to) {
        TArc beginSideArc = tmesh.arcs().get(patch.arcIds()[beginSide]);
        int beginSideCornerNodeId = patch.cornerNodeIds()[beginSide];
        return traceFromArc(tmesh, mesh, beginSideArc, beginSideCornerNodeId,
                sideRSum, uCorner, vCorner, trs, beginSide, from, to);
    }

    /**
     * Trace one iso-line from {@code from} to {@code to} given the specific
     * arc the begin-side starts with (used by PATCH-77 for multi-arc-side
     * patches where the T-junction terminates only the first arc on a side).
     *
     * @param beginSideArc       the T-arc whose endpoint is the T-junction
     *                           (i.e. the first arc on side {@code beginSide}
     *                           walking from corner {@code beginSide})
     * @param beginSideCornerNodeId  TNode id at corner {@code beginSide}
     * @param sideRSum           per-side total parametric length of the patch
     *                           (sum of underlying TArc parametric lengths)
     * @param beginSide          0..3; only used to index {@code sideRSum} for
     *                           the mid/end side lookups
     */
    public static SplitArc traceFromArc(TMesh tmesh, ArrayMesh mesh,
                                         TArc beginSideArc,
                                         int beginSideCornerNodeId,
                                         double[] sideRSum,
                                         float[] uCorner, float[] vCorner,
                                         TransitionMatrix trs,
                                         int beginSide,
                                         SplitElem from, SplitElem to) {
        ArrayList<SplitEdge> result = new ArrayList<>();

        TArc fromArc = tmesh.arcs().get(from.arcId());
        int fromFace = fromArc.meshFaceCrossings().get(from.stepIndex())[0];
        TArc toArc = tmesh.arcs().get(to.arcId());
        int toFace = toArc.meshFaceCrossings().get(to.stepIndex())[0];

        // Same-face fast path.
        if (fromFace == toFace) {
            result.add(new SplitEdge(fromFace, from.u(), from.v(), to.u(), to.v()));
            return new SplitArc(result);
        }

        // Compute iso-line direction.
        // diff_mid_side = total parametric length of side (beginSide+1)%4
        // diff_bgn_side = from.distance (along side beginSide)
        // diff_end_side = side(beginSide+2).totalLength - to.distance
        int midSide = (beginSide + 1) % 4;
        int endSide = (beginSide + 2) % 4;
        double diffBgn = from.distance();
        double diffMid = sideRSum[midSide];
        double diffEnd = sideRSum[endSide] - to.distance();
        double arg = (Math.abs(diffMid) < EPS) ? 0.0
                : -Math.atan((diffEnd - diffBgn) / diffMid);

        // beginSideArc gives the edge direction reference.
        boolean canonical = beginSideArc.startNode() == beginSideCornerNodeId;
        // Edge direction = beginSideArc's net (uOut - uIn) over its first step.
        float[] firstStepUv = beginSideArc.stepUvs().isEmpty()
                ? new float[]{0f, 0f, 1f, 0f}
                : beginSideArc.stepUvs().get(0);
        double edgeDx = (canonical ? 1 : -1) * (firstStepUv[2] - firstStepUv[0]);
        double edgeDy = (canonical ? 1 : -1) * (firstStepUv[3] - firstStepUv[1]);
        double edgeLen = Math.hypot(edgeDx, edgeDy);
        if (edgeLen < EPS) {
            // Degenerate first step — try summing the whole arc.
            edgeDx = (canonical ? 1 : -1)
                    * sumStepDelta(beginSideArc, true);
            edgeDy = (canonical ? 1 : -1)
                    * sumStepDelta(beginSideArc, false);
            edgeLen = Math.hypot(edgeDx, edgeDy);
            if (edgeLen < EPS) return new SplitArc(result);
        }
        edgeDx /= edgeLen;
        edgeDy /= edgeLen;

        // Rotate edge direction by (PI/2 + arg) — perpendicular into patch interior.
        double totalAngle = Math.PI / 2 + arg;
        double cosA = Math.cos(totalAngle);
        double sinA = Math.sin(totalAngle);
        double dirX = edgeDx * cosA - edgeDy * sinA;
        double dirY = edgeDx * sinA + edgeDy * cosA;
        // Long ray for intersection robustness (mirror metriko's "* 1000").
        double rayDx = dirX * 1000.0;
        double rayDy = dirY * 1000.0;

        // Walk faces. Start at fromFace.
        int curFace = fromFace;
        double curU = from.u();
        double curV = from.v();
        int entryHalfEdge = -1;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            // Find which face edge the ray exits.
            int exitHe = -1;
            double exitT = Double.POSITIVE_INFINITY;
            double exitTSeg = 0;
            for (int c = 0; c < 3; c++) {
                int he = curFace * 3 + c;
                if (he == entryHalfEdge) continue;
                float aU = uCorner[curFace * 3 + c];
                float aV = vCorner[curFace * 3 + c];
                float bU = uCorner[curFace * 3 + (c + 1) % 3];
                float bV = vCorner[curFace * 3 + (c + 1) % 3];
                double[] rs = raySegmentIntersect(curU, curV, rayDx, rayDy,
                        aU, aV, bU, bV);
                if (rs == null) continue;
                if (rs[0] > EPS && rs[0] < exitT) {
                    exitT = rs[0];
                    exitTSeg = rs[1];
                    exitHe = he;
                }
            }
            if (exitHe < 0) break;
            int c = exitHe % 3;
            float aU = uCorner[curFace * 3 + c];
            float aV = vCorner[curFace * 3 + c];
            float bU = uCorner[curFace * 3 + (c + 1) % 3];
            float bV = vCorner[curFace * 3 + (c + 1) % 3];
            double hitU = aU + exitTSeg * (bU - aU);
            double hitV = aV + exitTSeg * (bV - aV);

            int twin = mesh.halfEdgeTwin(exitHe);
            if (twin < 0) break;
            int nextFace = mesh.halfEdgeFace(twin);
            if (nextFace < 0) break;

            // If we've reached the destination face, emit final segment to `to`.
            if (nextFace == toFace) {
                result.add(new SplitEdge(curFace,
                        (float) curU, (float) curV,
                        (float) hitU, (float) hitV));
                // Transform the hit point into nextFace's frame for the
                // closing edge, then connect to (to.u, to.v).
                float[] uv = {(float) hitU, (float) hitV};
                trs.transformPoint(exitHe, uv);
                result.add(new SplitEdge(nextFace,
                        uv[0], uv[1],
                        to.u(), to.v()));
                return new SplitArc(result);
            }

            // Otherwise, emit the segment to the exit point, transform
            // (uv, dir) via TRS, continue.
            result.add(new SplitEdge(curFace,
                    (float) curU, (float) curV,
                    (float) hitU, (float) hitV));
            float[] uv = {(float) hitU, (float) hitV};
            float[] d = {(float) rayDx, (float) rayDy};
            trs.transformPoint(exitHe, uv);
            trs.transformDirection(exitHe, d);
            curU = uv[0];
            curV = uv[1];
            rayDx = d[0];
            rayDy = d[1];
            curFace = nextFace;
            entryHalfEdge = twin;
        }
        return new SplitArc(result);
    }

    private static double sumStepDelta(TArc arc, boolean uAxis) {
        double s = 0;
        for (float[] step : arc.stepUvs()) {
            s += uAxis ? (step[2] - step[0]) : (step[3] - step[1]);
        }
        return s;
    }

    /** Standard 2D ray-segment intersection (same as QuadEdgeGenerator). */
    private static double[] raySegmentIntersect(double px, double py,
                                                 double dx, double dy,
                                                 double ax, double ay,
                                                 double bx, double by) {
        double sx = bx - ax;
        double sy = by - ay;
        double denom = dy * sx - dx * sy;
        if (Math.abs(denom) < EPS) return null;
        double tRay = ((ay - py) * sx - (ax - px) * sy) / denom;
        double tSeg = (dx * (ay - py) - dy * (ax - px)) / denom;
        if (tRay <= EPS) return null;
        if (tSeg < -EPS || tSeg > 1.0 + EPS) return null;
        return new double[]{tRay, tSeg};
    }
}
