package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.motorcycle.model.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.model.TraceAxis;
import ixdar.geometry.mesh.quadlayout.motorcycle.model.TracePort;

/**
 * A hand-authored trace that leaves its cone along −u and returns along −v, as on crawfish
 * IMG_4109, 4116 and 4117: LCK21a §3 stops it on the self-crossing that closes its sector.
 */
class SelfCrossingStopTest {

    private static final double ALPHA_BOUND_RADIANS = Math.toRadians(15.0);

    private static final double RETURN_LENGTH_AT_OTHER_TRACE = 9.0;
    private static final double OTHER_TRACE_LENGTH_AT_MEETING = 0.3;

    private static final double[][] RETURN_AND_FIRST_PASS_LENGTHS_PER_SELF_CROSSING = {
        { 9.2, 6.0 }, { 9.5, 0.5 } };

    private static final boolean[] ALIVE_AFTER_SELF_CROSSING = { true, false };

    private static final int OTHER_TRACE_VISIT = 2;
    private static final int FIRST_PASS_VISIT = 5;
    private static final int RETURN_VISIT = 110;

    @Test
    void theSelfCrossingThatClosesTheSectorStopsTheTrace() {
        Trace trace = new Trace(0, 0, 0, new TracePort(0, 0, 0, TraceAxis.U, -1), 0.0, 0.0, false);
        Trace other = new Trace(1, 1, 1, new TracePort(1, 0, 0, TraceAxis.U, 1), 0.0, 0.0, false);

        trace.recordMeeting(other, RETURN_LENGTH_AT_OTHER_TRACE, OTHER_TRACE_LENGTH_AT_MEETING,
                Trace.computeAlphaIj(TraceAxis.V, -1, TraceAxis.U, 1,
                        RETURN_LENGTH_AT_OTHER_TRACE, OTHER_TRACE_LENGTH_AT_MEETING),
                ALPHA_BOUND_RADIANS, TraceAxis.V, -1, TraceAxis.U, 1, RETURN_VISIT,
                OTHER_TRACE_VISIT);
        assertTrue(trace.alive, "the other trace closes the clockwise side only");

        for (int crossing = 0; crossing < ALIVE_AFTER_SELF_CROSSING.length; crossing++) {
            double returning = RETURN_AND_FIRST_PASS_LENGTHS_PER_SELF_CROSSING[crossing][0];
            double firstPass = RETURN_AND_FIRST_PASS_LENGTHS_PER_SELF_CROSSING[crossing][1];
            trace.recordMeeting(trace, returning, firstPass,
                    Trace.computeAlphaIj(TraceAxis.V, -1, TraceAxis.U, -1, returning, firstPass),
                    ALPHA_BOUND_RADIANS, TraceAxis.V, -1, TraceAxis.U, -1,
                    RETURN_VISIT + 1 + crossing, FIRST_PASS_VISIT);
            trace.recordMeeting(trace, firstPass, returning,
                    Trace.computeAlphaIj(TraceAxis.U, -1, TraceAxis.V, -1, firstPass, returning),
                    ALPHA_BOUND_RADIANS, TraceAxis.U, -1, TraceAxis.V, -1, FIRST_PASS_VISIT,
                    RETURN_VISIT + 1 + crossing);
            assertEquals(ALIVE_AFTER_SELF_CROSSING[crossing], trace.alive,
                    "self-crossing " + crossing + ": its own trail counts only inside the bound");
        }
    }
}
