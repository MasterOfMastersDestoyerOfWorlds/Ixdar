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
     * @param traceId    candidate trace id
     * @param activeFace active face index
     * @param entryU     chord entry u
     * @param entryV     chord entry v
     * @param exitU      chord exit u
     * @param exitV      chord exit v
     * @param axis       candidate axis
     * @return matched segment plus the (t, iu, iv) hit data, or {@code null}
     */
    public IntersectionHit earliestIntersection(int traceId, int activeFace,
            float entryU, float entryV, float exitU, float exitV, TraceAxis axis) {
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
            if (hit == null || hit[0] <= ChartWalker.ORIENT_COLLINEAR_EPSILON || hit[0] >= bestT) {
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

    private static double[] intersectSegments(
            float a0u, float a0v, float a1u, float a1v, TraceAxis axisA,
            float b0u, float b0v, float b1u, float b1v, TraceAxis axisB) {
        if (axisA == axisB) {
            return null;
        }
        float isoA = axisA.holdsUConstant() ? a0u : a0v;
        float isoB = axisB.holdsUConstant() ? b0u : b0v;
        // For perpendicular axis-aligned chords, the intersection sits at the
        // u-constant of whichever trace holds u constant, and the v-constant of
        // whichever trace holds v constant.
        float iu;
        float iv;
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

    private static boolean withinSpan(float iu, float iv,
            float e0u, float e0v, float e1u, float e1v, TraceAxis axis) {
        float span = axis.holdsUConstant() ? iv : iu;
        float s0 = axis.holdsUConstant() ? e0v : e0u;
        float s1 = axis.holdsUConstant() ? e1v : e1u;
        float lo = Math.min(s0, s1) - 1.0e-6f;
        float hi = Math.max(s0, s1) + 1.0e-6f;
        return span >= lo && span <= hi;
    }
}
