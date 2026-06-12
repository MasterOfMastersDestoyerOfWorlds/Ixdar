package ixdar.geometry.mesh.quadlayout.motorcycle;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-face index of trace segments for local intersection queries.
 */
public final class FaceSegmentIndex {

    /**
     * Parametric tolerance for recognizing a candidate hit as a re-detection
     * of an already-recorded meeting with the same trace. Distinct crossings
     * of one trace pair sit whole face-chords apart, far above this.
     */
    public static final double RE_MEETING_EPS = 1.0e-5;

    /** Chart-space tolerance for span containment. */
    private static final double SPAN_EPS = 1.0e-6;

    /**
     * Iso-coordinate tolerance for treating two same-axis tracks as one line.
     * Must exceed {@link ChartWalker}'s corner-snap proximity (1e-5 of an edge
     * length): a trace that would snap-terminate onto a singular vertex runs
     * up to that far off the vertex's own separatrix iso-line, and the two
     * tracks must still crash instead of doubling. Distinct separatrices are
     * separated on the order of the unit grid spacing, far above this.
     */
    private static final double COLLINEAR_ISO_EPS = 1.0e-4;

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
     * All perpendicular crossings between a freshly laid segment and the
     * segments already on its face. Segments register only when a trace exits
     * a face, so two traces traversing one face in the same time window are
     * mutually blind in the event queue — this retroactive sweep is how such
     * crossings get noded at all.
     *
     * @param fresh segment that was just laid (already added to the index)
     * @return one hit per crossing, with {@code tAlongCandidate} measured from
     *         the fresh segment's entry
     */
    public List<IntersectionHit> crossingsOf(TraceSegment fresh) {
        List<IntersectionHit> hits = new ArrayList<>();
        for (TraceSegment existing : segmentsByFace.get(fresh.activeFace)) {
            if (existing == fresh || existing.traceId == fresh.traceId
                    || existing.axis == fresh.axis) {
                continue;
            }
            double[] hit = intersectSegments(
                    fresh.entryU, fresh.entryV, fresh.exitU, fresh.exitV, fresh.axis,
                    existing.entryU, existing.entryV, existing.exitU, existing.exitV,
                    existing.axis);
            if (hit == null || hit[0] <= MotorcycleGraph.PARAMETRIC_EPS) {
                continue;
            }
            hits.add(new IntersectionHit(existing, hit[0], hit[1], hit[2]));
        }
        return hits;
    }

    /**
     * Find the earliest intersection between a candidate chord and existing
     * segments on the same face.
     *
     * @param traceId                candidate trace id
     * @param activeFace             active face index
     * @param entryU                 chord entry u
     * @param entryV                 chord entry v
     * @param exitU                  chord exit u
     * @param exitV                  chord exit v
     * @param axis                   candidate axis
     * @param parametricLengthAtEntry candidate trace's parametric length at the
     *                               chord entry, used to compare hits against
     *                               already-recorded meeting positions
     * @param metOtherList           the candidate trace's already-recorded
     *                               meetings; a hit at (approximately) the same
     *                               parametric position as a recorded meeting
     *                               with the same trace is skipped to prevent
     *                               same-point re-meeting loops — two traces
     *                               crossing again somewhere else must still
     *                               meet, otherwise the arrangement gets an
     *                               unnoded crossing and the four quadrants
     *                               around it fuse into one invalid cycle
     * @return matched segment plus the (t, iu, iv) hit data, or {@code null}
     */
    public IntersectionHit earliestIntersection(int traceId, int activeFace,
            double entryU, double entryV, double exitU, double exitV, TraceAxis axis,
            double parametricLengthAtEntry, List<MetOtherTraceEntry> metOtherList) {
        double bestT = Double.POSITIVE_INFINITY;
        TraceSegment bestSegment = null;
        double bestU = 0.0;
        double bestV = 0.0;
        for (TraceSegment existing : segmentsByFace.get(activeFace)) {
            if (existing.traceId == traceId) {
                continue;
            }
            double[] hit = intersectSegments(
                    entryU, entryV, exitU, exitV, axis,
                    existing.entryU, existing.entryV, existing.exitU, existing.exitV, existing.axis);
            if (hit == null || hit[0] >= bestT) {
                continue;
            }
            // The parametric floor guards perpendicular re-meeting loops at one
            // shared point; a collinear hit at t≈0 is the rider standing on
            // another's track at face entry (segments register at face exit, so
            // head-on opponents only ever see each other this way) and must be
            // reported so the caller can crash the rider on the spot.
            if (existing.axis != axis && hit[0] <= MotorcycleGraph.PARAMETRIC_EPS) {
                continue;
            }
            if (alreadyMetAt(metOtherList, existing.traceId, parametricLengthAtEntry + hit[0])) {
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
     * Linear-scan check whether a meeting with {@code otherTraceId} is already
     * recorded at (approximately) the given parametric position along the
     * candidate trace. Per-call cost is O(metOtherList.size()); on ELK this
     * peaks around a few dozen meetings per trace, much smaller than the
     * per-face segment count we'd otherwise re-test against.
     *
     * @param metOtherList    the trace's metOtherTraces list (may be {@code null})
     * @param otherTraceId    trace id of a candidate other-segment
     * @param candidateLength parametric length along the candidate trace at the
     *                        candidate hit
     * @return whether a meeting with that trace at that position is recorded
     */
    private static boolean alreadyMetAt(List<MetOtherTraceEntry> metOtherList, int otherTraceId,
            double candidateLength) {
        if (metOtherList == null) {
            return false;
        }
        for (MetOtherTraceEntry entry : metOtherList) {
            if (entry.otherTraceId == otherTraceId
                    && Math.abs(entry.ourParametricLength - candidateLength) <= RE_MEETING_EPS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Intersection of two axis-aligned chords. Perpendicular chords meet at
     * the obvious iso-coordinate pair. Collinear chords (same axis, same iso
     * value within {@link #SPAN_EPS}) model a motorcycle running along an
     * iso-line already covered by another trace — classical head-on/rear-end
     * semantics say it crashes at the first covered point ahead, so that
     * contact point is returned; without it, two opposing riders on one
     * separatrix line ride through each other and the T-mesh gets a doubled,
     * overlapping arc track.
     *
     * @param a0u   candidate chord entry u
     * @param a0v   candidate chord entry v
     * @param a1u   candidate chord exit u
     * @param a1v   candidate chord exit v
     * @param axisA candidate chord axis
     * @param b0u   existing segment entry u
     * @param b0v   existing segment entry v
     * @param b1u   existing segment exit u
     * @param b1v   existing segment exit v
     * @param axisB existing segment axis
     * @return {t along candidate, hit u, hit v}, or {@code null} when the
     *         chords do not touch
     */
    private static double[] intersectSegments(
            double a0u, double a0v, double a1u, double a1v, TraceAxis axisA,
            double b0u, double b0v, double b1u, double b1v, TraceAxis axisB) {
        if (axisA == axisB) {
            double isoA = axisA.holdsUConstant() ? a0u : a0v;
            double isoB = axisB.holdsUConstant() ? b0u : b0v;
            if (Math.abs(isoA - isoB) > COLLINEAR_ISO_EPS) {
                return null;
            }
            double spanStart = axisA.holdsUConstant() ? a0v : a0u;
            double spanExit = axisA.holdsUConstant() ? a1v : a1u;
            double otherLow = Math.min(axisB.holdsUConstant() ? b0v : b0u,
                    axisB.holdsUConstant() ? b1v : b1u);
            double otherHigh = Math.max(axisB.holdsUConstant() ? b0v : b0u,
                    axisB.holdsUConstant() ? b1v : b1u);
            double contact;
            if (spanExit >= spanStart) {
                if (otherHigh < spanStart - SPAN_EPS || otherLow > spanExit + SPAN_EPS) {
                    return null;
                }
                contact = Math.max(spanStart, otherLow);
            } else {
                if (otherLow > spanStart + SPAN_EPS || otherHigh < spanExit - SPAN_EPS) {
                    return null;
                }
                contact = Math.min(spanStart, otherHigh);
            }
            double hitU = axisA.holdsUConstant() ? isoA : contact;
            double hitV = axisA.holdsUConstant() ? contact : isoA;
            return new double[] { Math.abs(contact - spanStart), hitU, hitV };
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
        double lo = Math.min(s0, s1) - SPAN_EPS;
        double hi = Math.max(s0, s1) + SPAN_EPS;
        return span >= lo && span <= hi;
    }
}
