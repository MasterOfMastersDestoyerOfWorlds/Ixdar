package ixdar.geometry.mesh.quadlayout.motorcycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Per-face segment intersection queries used by motorcycle simulation.
 *
 * <p>
 * The earlier {@code intersectSegments} had {@code iu/iv} reversed: when trace
 * A held v constant and trace B held u constant, the returned point swapped
 * u and v. Downstream {@code withinSpan} compared u-values against v-ranges
 * and almost always rejected the hit, so on ELK only 11 of ~2000 expected
 * intersections were detected and the remainder were patched up by
 * {@code finalizeOpenTraces} as fake boundary nodes.
 */
class FaceSegmentIndexTest {

    private static final int ACTIVE_FACE = 0;
    private static final int TRACE_A = 100;
    private static final int TRACE_B = 200;
    private static final double TOLERANCE = 1.0e-5;

    @Test
    void perpendicularCrossingChordsReportTheCorrectPoint() {
        FaceSegmentIndex index = new FaceSegmentIndex(1);
        index.add(new TraceSegment(TRACE_B, ACTIVE_FACE,
                3f, 0f, 3f, 10f, TraceAxis.V, +1, 0.0));

        FaceSegmentIndex.IntersectionHit hit = index.earliestIntersection(
                TRACE_A, ACTIVE_FACE, 0f, 7f, 10f, 7f, TraceAxis.U);

        assertNotNull(hit, "perpendicular chords through the same face should intersect");
        assertEquals(TRACE_B, hit.otherSegment.traceId);
        assertEquals(3.0, hit.intersectionU, TOLERANCE);
        assertEquals(7.0, hit.intersectionV, TOLERANCE);
        assertEquals(3.0, hit.tAlongCandidate, TOLERANCE);
    }

    @Test
    void perpendicularChordsWithCandidateAlongVReportCorrectPoint() {
        FaceSegmentIndex index = new FaceSegmentIndex(1);
        index.add(new TraceSegment(TRACE_B, ACTIVE_FACE,
                0f, 4f, 10f, 4f, TraceAxis.U, +1, 0.0));

        FaceSegmentIndex.IntersectionHit hit = index.earliestIntersection(
                TRACE_A, ACTIVE_FACE, 6f, 0f, 6f, 10f, TraceAxis.V);

        assertNotNull(hit, "perpendicular chords through the same face should intersect");
        assertEquals(6.0, hit.intersectionU, TOLERANCE);
        assertEquals(4.0, hit.intersectionV, TOLERANCE);
        assertEquals(4.0, hit.tAlongCandidate, TOLERANCE);
    }

    @Test
    void parallelChordsDoNotIntersect() {
        FaceSegmentIndex index = new FaceSegmentIndex(1);
        index.add(new TraceSegment(TRACE_B, ACTIVE_FACE,
                0f, 4f, 10f, 4f, TraceAxis.U, +1, 0.0));

        FaceSegmentIndex.IntersectionHit hit = index.earliestIntersection(
                TRACE_A, ACTIVE_FACE, 0f, 7f, 10f, 7f, TraceAxis.U);

        assertNull(hit, "parallel axis-U chords must not produce an intersection");
    }

    @Test
    void crossingOutsideExistingSpanIsRejected() {
        FaceSegmentIndex index = new FaceSegmentIndex(1);
        index.add(new TraceSegment(TRACE_B, ACTIVE_FACE,
                3f, 0f, 3f, 2f, TraceAxis.V, +1, 0.0));

        FaceSegmentIndex.IntersectionHit hit = index.earliestIntersection(
                TRACE_A, ACTIVE_FACE, 0f, 7f, 10f, 7f, TraceAxis.U);

        assertNull(hit, "candidate at v=7 must not cross a B chord covering v in [0, 2]");
    }
}
