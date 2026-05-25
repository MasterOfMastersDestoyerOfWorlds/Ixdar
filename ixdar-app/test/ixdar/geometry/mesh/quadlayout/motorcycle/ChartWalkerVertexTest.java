package ixdar.geometry.mesh.quadlayout.motorcycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * Lyon §3 vertex-aware traversal in {@link ChartWalker}.
 *
 * <p>
 * When a trace's iso-line exits a face exactly at one of its corners, the
 * next face's two non-incoming edges both share that corner; the basic
 * ray-segment test in {@link ChartWalker#nextEdgeHit} returns null because
 * every forward hit is at {@code t ≈ 0}. {@link ChartWalker#crossVertex}
 * walks the vertex fan to find the face whose interior wedge at the corner
 * contains the continuation direction.
 */
class ChartWalkerVertexTest {

    @Test
    void crossVertexContinuesThroughRegularVertexAlongPositiveU() {
        SeamlessParameterization seamless = ChartWalkerVertexFixtures.buildRegularVertexFan(false);
        ChartWalker walker = new ChartWalker(seamless);

        ChartWalker.State state = stateAtCenter(ChartWalkerVertexFixtures.F_Q2, TraceAxis.U, +1);
        ChartWalker.EdgeHit hit = cornerHitAt(state.activeFace, /* cornerLocalIndex */ 0);
        ChartWalker.State out = new ChartWalker.State(state);

        ChartWalker.CrossVertexResult result = walker.crossVertex(state, hit, out);

        assertEquals(ChartWalker.CrossVertexResult.FAN_TRANSITION, result,
                "trace heading +U from the regular vertex must continue into a fan face");
        assertNotEquals(state.activeFace, out.activeFace,
                "the continuation face must be a different fan triangle");
        assertEquals(TraceAxis.U, out.axis);
        assertEquals(+1, out.sign);
        assertTrue(out.activeFace == ChartWalkerVertexFixtures.F_Q1
                || out.activeFace == ChartWalkerVertexFixtures.F_Q4,
                "the continuation must land in the +U-side wedge (Q1 or Q4), got face " + out.activeFace);
    }

    @Test
    void crossVertexContinuesThroughRegularVertexAlongNegativeV() {
        SeamlessParameterization seamless = ChartWalkerVertexFixtures.buildRegularVertexFan(false);
        ChartWalker walker = new ChartWalker(seamless);

        ChartWalker.State state = stateAtCenter(ChartWalkerVertexFixtures.F_Q1, TraceAxis.V, -1);
        ChartWalker.EdgeHit hit = cornerHitAt(state.activeFace, 0);
        ChartWalker.State out = new ChartWalker.State(state);

        ChartWalker.CrossVertexResult result = walker.crossVertex(state, hit, out);

        assertEquals(ChartWalker.CrossVertexResult.FAN_TRANSITION, result);
        assertTrue(out.activeFace == ChartWalkerVertexFixtures.F_Q3
                || out.activeFace == ChartWalkerVertexFixtures.F_Q4,
                "the continuation must land in the -V-side wedge (Q3 or Q4), got face " + out.activeFace);
        assertEquals(TraceAxis.V, out.axis);
        assertEquals(-1, out.sign);
    }

    @Test
    void crossVertexTerminatesAtSingularity() {
        SeamlessParameterization seamless = ChartWalkerVertexFixtures.buildRegularVertexFan(true);
        ChartWalker walker = new ChartWalker(seamless);

        ChartWalker.State state = stateAtCenter(ChartWalkerVertexFixtures.F_Q1, TraceAxis.U, +1);
        ChartWalker.EdgeHit hit = cornerHitAt(state.activeFace, 0);
        ChartWalker.State out = new ChartWalker.State(state);

        ChartWalker.CrossVertexResult result = walker.crossVertex(state, hit, out);

        assertEquals(ChartWalker.CrossVertexResult.HIT_SINGULARITY, result,
                "trace passing through a singularity must terminate per Lyon §6");
    }

    private static ChartWalker.State stateAtCenter(int activeFace, TraceAxis axis, int sign) {
        // Fan center is at (0, 0) and sits at local corner 0 of every fan face.
        ChartWalker.State state = new ChartWalker.State(activeFace, 0f, 0f, axis, sign);
        state.incomingLocalEdgeIndex = -1;
        return state;
    }

    private static ChartWalker.EdgeHit cornerHitAt(int activeFace, int cornerLocalIndex) {
        return new ChartWalker.EdgeHit(0.0, 0f, 0f, /* localEdgeIndex */ cornerLocalIndex, false,
                cornerLocalIndex);
    }
}
