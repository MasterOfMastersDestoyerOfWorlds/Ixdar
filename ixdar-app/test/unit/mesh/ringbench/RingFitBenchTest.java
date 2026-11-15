package unit.mesh.ringbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The bench scored with no fit: every fixture runs on its own and carries its rings, every case
 * asks a fit to move something, and the scores are where the rings were drawn.
 */
class RingFitBenchTest {

    private static final double SECTION = 2.25;

    private static final double QUARTER_TURN = 90.0;

    private static final double NEARLY_OPPOSITE = 166.5;

    @Test
    void asDrawnNoCasePassesAndTheRingsScoreWhereTheyWereDrawn() {
        List<RingFitBench.Result> results = RingFitBench.run(RingFitBench.NO_FIT_RINGS_AS_DRAWN);
        assertEquals(RingFitBench.CASES_IN_RING_ANCHOR_FIT_TEST_ORDER.size(), results.size());
        for (RingFitBench.Result result : results) {
            assertFalse(result.passes(), "passes with no fit: " + result);
        }
        assertEquals(QUARTER_TURN, worst(results, "one_groove"), SECTION);
        assertEquals(NEARLY_OPPOSITE, worst(results, "one_groove_far"), SECTION);
    }

    private static double worst(List<RingFitBench.Result> results, String fixture) {
        return results.stream().filter(result -> result.fixture().equals(fixture)).findFirst().orElseThrow()
                .scores().get(0).worst();
    }
}
