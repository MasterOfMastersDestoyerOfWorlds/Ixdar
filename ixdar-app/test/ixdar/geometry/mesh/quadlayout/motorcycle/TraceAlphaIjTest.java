package ixdar.geometry.mesh.quadlayout.motorcycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Lyon 2021 §3 αij: signed angle at singularity i of the right triangle formed
 * by Sij and Sji at the intersection of traces ti and tj.
 *
 * <p>
 * αij is the angle from ti's forward direction to the direction
 * (intersection − lji · tj.forward) − (intersection − lij · ti.forward) =
 * lij · ti.forward − lji · tj.forward. It is positive when singularity j lies
 * on ti's ccw side and lives in (−π/2, π/2) for non-collinear configurations.
 *
 * <p>
 * The Lyon stopping criterion fires when on each side of ti there exists at
 * least one met trace with |αij| ≤ α. The cases below verify both the
 * geometric value of {@code computeAlphaIj} and the stopping behaviour driven
 * by it.
 */
class TraceAlphaIjTest {

    private static final double TOLERANCE = 1.0e-9;

    @Test
    void perpendicularEqualLegsGiveNegativeFortyFiveDegrees() {
        double alpha = Trace.computeAlphaIj(
                TraceAxis.U, +1, TraceAxis.V, +1, 1.0, 1.0);
        assertEquals(-Math.PI / 4.0, alpha, TOLERANCE);
    }

    @Test
    void perpendicularLongTiShortTjGivesSmallNegativeAlpha() {
        double alpha = Trace.computeAlphaIj(
                TraceAxis.U, +1, TraceAxis.V, +1, 10.0, 1.0);
        assertEquals(-Math.atan2(1.0, 10.0), alpha, TOLERANCE);
    }

    @Test
    void perpendicularWithOppositePerpAxisFlipsAlphaSign() {
        double alpha = Trace.computeAlphaIj(
                TraceAxis.U, +1, TraceAxis.V, -1, 1.0, 1.0);
        assertEquals(+Math.PI / 4.0, alpha, TOLERANCE);
    }

    @Test
    void parallelSameDirectionGivesZeroAlphaWhenIBehindJ() {
        double alpha = Trace.computeAlphaIj(
                TraceAxis.U, +1, TraceAxis.U, +1, 5.0, 2.0);
        assertEquals(0.0, alpha, TOLERANCE);
    }

    @Test
    void antiparallelGivesZeroAlpha() {
        double alpha = Trace.computeAlphaIj(
                TraceAxis.U, +1, TraceAxis.U, -1, 3.0, 4.0);
        assertEquals(0.0, alpha, TOLERANCE);
    }

    @Test
    void tiReversedFlipsAlphaSignVsTiForward() {
        double forward = Trace.computeAlphaIj(
                TraceAxis.U, +1, TraceAxis.V, +1, 3.0, 1.0);
        double reversed = Trace.computeAlphaIj(
                TraceAxis.U, -1, TraceAxis.V, +1, 3.0, 1.0);
        assertEquals(Math.atan2(1.0, 3.0), reversed, TOLERANCE);
        assertEquals(-reversed, forward, TOLERANCE);
    }

    @Test
    void lyonStoppingFiresOnlyWhenBothSidesHaveCloseEnoughTrace() {
        double alphaBound = Math.toRadians(15.0);
        Trace trace = newTrace(TraceAxis.U, +1);

        trace.metOtherTraces.add(new MetOtherTraceEntry(1, -Math.toRadians(10.0), 1.0, 1.0));
        assertFalse(trace.satisfiesLyonStop(alphaBound),
                "one ccw-side trace alone must not stop ti");

        trace.metOtherTraces.add(new MetOtherTraceEntry(2, +Math.toRadians(8.0), 1.0, 1.0));
        assertTrue(trace.satisfiesLyonStop(alphaBound),
                "ti must stop once both sides each contribute a within-α trace");
    }

    @Test
    void lyonStoppingDoesNotFireWhenBothMetsAreSameSide() {
        double alphaBound = Math.toRadians(15.0);
        Trace trace = newTrace(TraceAxis.U, +1);
        trace.metOtherTraces.add(new MetOtherTraceEntry(1, +Math.toRadians(5.0), 1.0, 1.0));
        trace.metOtherTraces.add(new MetOtherTraceEntry(2, +Math.toRadians(10.0), 1.0, 1.0));
        assertFalse(trace.satisfiesLyonStop(alphaBound),
                "two ccw-side mets must not satisfy the two-sided test");
    }

    @Test
    void lyonStoppingIgnoresMetsBeyondAlphaBound() {
        double alphaBound = Math.toRadians(15.0);
        Trace trace = newTrace(TraceAxis.U, +1);
        trace.metOtherTraces.add(new MetOtherTraceEntry(1, +Math.toRadians(40.0), 1.0, 1.0));
        trace.metOtherTraces.add(new MetOtherTraceEntry(2, -Math.toRadians(40.0), 1.0, 1.0));
        assertFalse(trace.satisfiesLyonStop(alphaBound),
                "neither met is within α; ti must keep going");
    }

    private static Trace newTrace(TraceAxis axis, int sign) {
        TracePort port = new TracePort(-1, 0, 0, axis, sign);
        return new Trace(0, 0, -1, port, 0f, 0f, false);
    }
}
