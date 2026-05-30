package ixdar.geometry.mesh.quadlayout.motorcycle;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-face index of trace segments for local intersection queries.
 */
public final class FaceSegmentIndex {

    private final List<List<TraceSegment>> segmentsByFace;

    /**
     * Allocates per-face segment lists for intersection queries.
     *
     * @param faceCount number of active faces
     */
    public FaceSegmentIndex(int faceCount) {
        segmentsByFace = new ArrayList<>(faceCount);
        for (int face = 0; face < faceCount; face++) {
            segmentsByFace.add(new ArrayList<>());
        }
    }

    /**
     * Returns the modifiable segment list for one face.
     *
     * @param activeFace active face index
     * @return modifiable segment list for that face
     */
    public List<TraceSegment> segmentsOnFace(int activeFace) {
        return segmentsByFace.get(activeFace);
    }

    /**
     * Add a segment to the face index.
     *
     * @param segment segment to register
     */
    public void add(TraceSegment segment) {
        segmentsByFace.get(segment.activeFace).add(segment);
    }

    /**
     * Find the earliest intersection between a candidate chord and existing
     * segments on the same face.
     *
     * @param traceId       candidate trace id
     * @param activeFace    active face index
     * @param entryU        chord entry u
     * @param entryV        chord entry v
     * @param exitU         chord exit u
     * @param exitV         chord exit v
     * @param axis          candidate axis
     * @param metOtherList  the candidate trace's already-recorded meetings;
     *                      segments owned by traces in this list are skipped to
     *                      prevent same-corner re-meeting loops when two
     *                      iso-lines share a chart coordinate to within float
     *                      precision
     * @return matched segment plus the (t, iu, iv) hit data, or {@code null}
     */
    public IntersectionHit earliestIntersection(int traceId, int activeFace,
            double entryU, double entryV, double exitU, double exitV, TraceAxis axis,
            List<MetOtherTraceEntry> metOtherList) {
        double bestT = Double.POSITIVE_INFINITY;
        TraceSegment bestSegment = null;
        double bestU = 0.0;
        double bestV = 0.0;
        for (TraceSegment existing : segmentsByFace.get(activeFace)) {
            if (existing.traceId == traceId) {
                continue;
            }
            if (alreadyMet(metOtherList, existing.traceId)) {
                continue;
            }
            double[] hit = intersectSegments(
                    entryU, entryV, exitU, exitV, axis,
                    existing.entryU, existing.entryV, existing.exitU, existing.exitV, existing.axis);
            if (hit == null || hit[0] <= MotorcycleGraph.PARAMETRIC_EPS || hit[0] >= bestT) {
                continue;
            }
            bestT = hit[0];
            bestSegment = existing;
            bestU = hit[1];
            bestV = hit[2];
        }
        if (bestSegment == null) {
            return null;
        }
        return new IntersectionHit(bestSegment, bestT, bestU, bestV);
    }

    /** Result of {@link #earliestIntersection}: matched segment plus chord hit data. */
    public static final class IntersectionHit {
        public final TraceSegment otherSegment;
        public final double tAlongCandidate;
        public final double intersectionU;
        public final double intersectionV;

        /**
         * One earliest-intersection record between a candidate chord and an
         * existing trace segment.
         *
         * @param otherSegment     matched existing segment
         * @param tAlongCandidate  parametric distance along the candidate from
         *                         its entry to the intersection
         * @param intersectionU    intersection u in the face chart
         * @param intersectionV    intersection v in the face chart
         */
        public IntersectionHit(TraceSegment otherSegment, double tAlongCandidate,
                double intersectionU, double intersectionV) {
            this.otherSegment = otherSegment;
            this.tAlongCandidate = tAlongCandidate;
            this.intersectionU = intersectionU;
            this.intersectionV = intersectionV;
        }
    }

    /**
     * Linear-scan membership check over a trace's prior meetings. Per-call cost
     * is O(metOtherList.size()); on ELK this peaks around a few dozen
     * meetings per trace, much smaller than the per-face segment count we'd
     * otherwise re-test against.
     *
     * @param metOtherList the trace's metOtherTraces list (may be {@code null})
     * @param otherTraceId trace id of a candidate other-segment
     * @return whether {@code otherTraceId} appears in {@code metOtherList}
     */
    private static boolean alreadyMet(List<MetOtherTraceEntry> metOtherList, int otherTraceId) {
        if (metOtherList == null) {
            return false;
        }
        for (MetOtherTraceEntry entry : metOtherList) {
            if (entry.otherTraceId == otherTraceId) {
                return true;
            }
        }
        return false;
    }

    private static double[] intersectSegments(
            double a0u, double a0v, double a1u, double a1v, TraceAxis axisA,
            double b0u, double b0v, double b1u, double b1v, TraceAxis axisB) {
        if (axisA == axisB) {
            return null;
        }
        double isoA = axisA.holdsUConstant() ? a0u : a0v;
        double isoB = axisB.holdsUConstant() ? b0u : b0v;
        // For perpendicular axis-aligned chords, the intersection sits at the
        // u-constant of whichever trace holds u constant, and the v-constant of
        // whichever trace holds v constant.
        double iu;
        double iv;
        if (axisA == TraceAxis.U) {
            iu = isoB;
            iv = isoA;
        } else {
            iu = isoA;
            iv = isoB;
        }
        double tA = axisA == TraceAxis.U ? Math.abs(iu - a0u) : Math.abs(iv - a0v);
        if (!withinSpan(iu, iv, a0u, a0v, a1u, a1v, axisA)) {
            return null;
        }
        if (!withinSpan(iu, iv, b0u, b0v, b1u, b1v, axisB)) {
            return null;
        }
        return new double[] { tA, iu, iv };
    }

    private static boolean withinSpan(double iu, double iv,
            double e0u, double e0v, double e1u, double e1v, TraceAxis axis) {
        double span = axis.holdsUConstant() ? iv : iu;
        double s0 = axis.holdsUConstant() ? e0v : e0u;
        double s1 = axis.holdsUConstant() ? e1v : e1u;
        double lo = Math.min(s0, s1) - 1.0e-6;
        double hi = Math.max(s0, s1) + 1.0e-6;
        return span >= lo && span <= hi;
    }
}
