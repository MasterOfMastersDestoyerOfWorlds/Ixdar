package ixdar.geometry.mesh.quadlayout.motorcycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Lyon 2021 §3 {@code ni*}: the closest meeting on ti whose companion-trace
 * origin sits inside ti's π/2-sector (equivalently {@code lji < lij}, or
 * {@code |αij| < π/4}). {@link Trace#firstSectorMeeting()} picks it from the
 * trace's recorded meetings.
 */
class TraceFirstSectorMeetingTest {

    @Test
    void returnsNullWhenNoMeetings() {
        Trace trace = newTrace();
        assertNull(trace.firstSectorMeeting());
    }

    @Test
    void returnsNullWhenAllMeetingsAreOutsideSector() {
        Trace trace = newTrace();
        trace.metOtherTraces.add(meeting(1, /* ours */ 2.0, /* theirs */ 5.0));
        trace.metOtherTraces.add(meeting(2, /* ours */ 3.0, /* theirs */ 7.0));
        assertNull(trace.firstSectorMeeting(),
                "meetings with theirLength >= ourLength fall outside ti's π/2-sector");
    }

    @Test
    void picksSingleSectorMeeting() {
        Trace trace = newTrace();
        MetOtherTraceEntry inside = meeting(1, 10.0, 2.0);
        trace.metOtherTraces.add(inside);
        assertSame(inside, trace.firstSectorMeeting());
    }

    @Test
    void picksClosestSectorMeetingNotJustFirstRecorded() {
        Trace trace = newTrace();
        // Recorded out of length order — selection must be by ourLength, not insertion order.
        MetOtherTraceEntry far = meeting(1, 12.0, 4.0);
        MetOtherTraceEntry near = meeting(2, 6.0, 3.0);
        trace.metOtherTraces.add(far);
        trace.metOtherTraces.add(near);
        assertSame(near, trace.firstSectorMeeting());
    }

    @Test
    void ignoresSectorMeetingsBeyondACloserOutsideMeeting() {
        // Out-of-sector meeting at ours=5 doesn't suppress the sector meeting at ours=8.
        Trace trace = newTrace();
        trace.metOtherTraces.add(meeting(1, 5.0, 9.0));
        MetOtherTraceEntry inside = meeting(2, 8.0, 3.0);
        trace.metOtherTraces.add(inside);
        assertSame(inside, trace.firstSectorMeeting());
    }

    @Test
    void recordedSignedAngleIsAccessibleOnSelection() {
        Trace trace = newTrace();
        trace.metOtherTraces.add(meeting(1, 5.0, 1.0, -Math.PI / 8.0));
        MetOtherTraceEntry pick = trace.firstSectorMeeting();
        assertNotNull(pick);
        assertEquals(-Math.PI / 8.0, pick.signedAngle, 1.0e-12);
    }

    private static Trace newTrace() {
        TracePort port = new TracePort(-1, 0, 0, TraceAxis.U, +1);
        return new Trace(0, 0, -1, port, 0f, 0f, false);
    }

    private static MetOtherTraceEntry meeting(int otherTraceId, double ourLength, double theirLength) {
        return meeting(otherTraceId, ourLength, theirLength, 0.0);
    }

    private static MetOtherTraceEntry meeting(int otherTraceId, double ourLength, double theirLength,
            double signedAngle) {
        return new MetOtherTraceEntry(otherTraceId, signedAngle, ourLength, theirLength);
    }
}
