package ixdar.geometry.mesh.quadlayout.motorcycle.records;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-face index of trace segments for local intersection queries.
 */
public final class FaceSegmentIndex {

    /**
     * Visit-ordinal window within which two same-trace segments count as adjacent
     * and so cannot transversally cross. The skip is limited to this window rather
     * than the whole trace so that a trace wrapping back over its earlier path
     * still gets noded.
     */
    private static final int SELF_CROSS_ADJACENT_VISITS = 1;

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
     * All perpendicular crossings between a freshly laid segment and the segments
     * already on its face. Segments register only on face exit, so this
     * retroactive sweep is the only way crossings between concurrent traversals of
     * one face get noded.
     *
     * @param fresh segment that was just laid (already added to the index)
     * @return one hit per crossing, with {@code tAlongCandidate} measured from
     *         the fresh segment's entry
     */
    public List<IntersectionHit> crossingsOf(TraceSegment fresh) {
        List<IntersectionHit> hits = new ArrayList<>();
        for (TraceSegment existing : segmentsByFace.get(fresh.activeFace)) {
            if (existing == fresh || existing.axis == fresh.axis
                    || isAdjacentSelf(existing, fresh.traceId, fresh.visitId)) {
                continue;
            }
            double[] hit = intersectSegments(
                    fresh.entryU, fresh.entryV, fresh.exitU, fresh.exitV, fresh.axis,
                    existing.entryU, existing.entryV, existing.exitU, existing.exitV,
                    existing.axis);
            if (hit == null) {
                continue;
            }
            if (crossingAtTraceOrigin(fresh.parametricLengthAtEntry, hit[0], existing,
                    hit[1], hit[2])) {
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
     * @param traceId          candidate trace id
     * @param activeFace       active face index
     * @param entryU           chord entry u
     * @param entryV           chord entry v
     * @param exitU            chord exit u
     * @param exitV            chord exit v
     * @param axis             candidate axis
     * @param chordStartLength candidate trace's parametric length at the chord
     *                         entry; {@code 0.0} marks the spawn chord, whose
     *                         entry-point crossings are origin contacts owned
     *                         by the singularity node, not meetings
     * @param ourVisitId       candidate trace's current face-visit ordinal; a
     *                         hit whose chord pair (this visit, the existing
     *                         segment's visit) is already recorded as a meeting
     *                         is skipped — a pair of chords crosses at most
     *                         once, so this is an exact combinatorial dedupe.
     *                         Distinct crossings of the same trace pair (other
     *                         faces, other visits) must still meet, otherwise
     *                         the arrangement gets an unnoded crossing and the
     *                         four quadrants around it fuse into one invalid
     *                         cycle
     * @param metOtherList     the candidate trace's already-recorded meetings
     * @return matched segment plus the (t, iu, iv) hit data, or {@code null}
     */
    public IntersectionHit earliestIntersection(int traceId, int activeFace,
            double entryU, double entryV, double exitU, double exitV, TraceAxis axis,
            double chordStartLength, int ourVisitId, List<MetOtherTraceEntry> metOtherList) {
        double bestT = Double.POSITIVE_INFINITY;
        TraceSegment bestSegment = null;
        double bestU = 0.0;
        double bestV = 0.0;
        for (TraceSegment existing : segmentsByFace.get(activeFace)) {
            if (isAdjacentSelf(existing, traceId, ourVisitId)) {
                continue;
            }
            double[] hit = intersectSegments(
                    entryU, entryV, exitU, exitV, axis,
                    existing.entryU, existing.entryV, existing.exitU, existing.exitV, existing.axis);
            if (hit == null || hit[0] >= bestT) {
                continue;
            }
            if (alreadyMetChordPair(metOtherList, existing, ourVisitId)) {
                continue;
            }
            if (crossingAtTraceOrigin(chordStartLength, hit[0], existing, hit[1], hit[2])) {
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

    /**
     * Whether a crossing coincides exactly with either trace's origin. Such
     * contacts are owned by the singularity node's port fan and the vertex-pass
     * machinery and are never meetings. All comparisons are exact, so a crossing
     * any nonzero distance from an origin is unaffected.
     *
     * @param chordStartLength candidate trace's parametric length at its chord
     *                         entry ({@code 0.0} on the spawn chord)
     * @param tAlongCandidate  hit distance from the candidate chord's entry
     * @param existing         matched existing segment
     * @param hitU             crossing u
     * @param hitV             crossing v
     * @return whether the crossing sits exactly on either trace's origin
     */
    private static boolean crossingAtTraceOrigin(double chordStartLength, double tAlongCandidate,
            TraceSegment existing, double hitU, double hitV) {
        if (chordStartLength == 0.0 && tAlongCandidate == 0.0) {
            return true;
        }
        return existing.parametricLengthAtEntry == 0.0
                && hitU == existing.entryU && hitV == existing.entryV;
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
     * Whether an existing segment belongs to the candidate trace's own current
     * face visit or one within {@link #SELF_CROSS_ADJACENT_VISITS} of it, in which
     * case the two share an endpoint and meet only tangentially. Segments further
     * apart along the same trace are real self-crossings and must be noded.
     *
     * @param existing         candidate other-segment on the face
     * @param candidateTraceId the laying trace's id
     * @param candidateVisitId the laying trace's current face-visit ordinal
     * @return whether the existing segment is an adjacent self-segment to skip
     */
    private static boolean isAdjacentSelf(TraceSegment existing, int candidateTraceId,
            int candidateVisitId) {
        return existing.traceId == candidateTraceId
                && Math.abs(existing.visitId - candidateVisitId) <= SELF_CROSS_ADJACENT_VISITS;
    }

    /**
     * Whether the chord pair (candidate's current visit, the existing segment's
     * visit) already has a recorded meeting. Two chords cross at most once, so
     * this identity is an exact dedupe rather than a tolerance test.
     *
     * @param metOtherList the trace's metOtherTraces list (may be {@code null})
     * @param existing     candidate other-segment
     * @param ourVisitId   the candidate trace's current face-visit ordinal
     * @return whether a meeting for this chord pair is recorded
     */
    private static boolean alreadyMetChordPair(List<MetOtherTraceEntry> metOtherList,
            TraceSegment existing, int ourVisitId) {
        if (metOtherList == null) {
            return false;
        }
        for (MetOtherTraceEntry entry : metOtherList) {
            if (entry.otherTraceId == existing.traceId
                    && entry.ourVisitId == ourVisitId
                    && entry.otherVisitId == existing.visitId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Intersection of two axis-aligned chords, by exact comparison of levels and
     * span endpoints with no tolerance. Perpendicular chords meet where each level
     * falls inside the other's closed span; chords with bit-identical levels meet
     * at the first covered point ahead.
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
            if (isoA != isoB) {
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
                if (otherHigh < spanStart || otherLow > spanExit) {
                    return null;
                }
                contact = Math.max(spanStart, otherLow);
            } else {
                if (otherLow > spanStart || otherHigh < spanExit) {
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

    /**
     * Closed-interval containment of the crossing point along one chord's
     * varying coordinate, by exact comparison. A crossing exactly at a chord
     * endpoint counts for both adjacent chords; the chord-pair meeting dedupe
     * keeps that from double-noding within one visit, and across visits such
     * exact-endpoint crossings are generically absent.
     *
     * @param iu  crossing u
     * @param iv  crossing v
     * @param e0u chord entry u
     * @param e0v chord entry v
     * @param e1u chord exit u
     * @param e1v chord exit v
     * @param axis chord axis
     * @return whether the crossing lies within the chord's closed span
     */
    private static boolean withinSpan(double iu, double iv,
            double e0u, double e0v, double e1u, double e1v, TraceAxis axis) {
        double span = axis.holdsUConstant() ? iv : iu;
        double s0 = axis.holdsUConstant() ? e0v : e0u;
        double s1 = axis.holdsUConstant() ? e1v : e1u;
        return span >= Math.min(s0, s1) && span <= Math.max(s0, s1);
    }
}
