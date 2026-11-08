package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.tools.ring.RingTool;

/**
 * A ring's number is its position in the overlay, not anything its label spells, so the drawn
 * number and the ring's place in the graph agree whatever the labels are called.
 */
class RingLabelNumberTest {

    // Enough graph rings to cross the zero-padding boundary.
    private static final int ROWS = 12;

    private static final int CONFIRMED = 2;

    // The label spline_ring leaves when a statement names none.
    private static final String SPLINE_RING = "spline_ring";

    @Test
    void everyRingDrawsItsPositionInTheGraph() {
        Map<String, boolean[]> marksByLabel = new LinkedHashMap<>();
        for (int ring = 0; ring < ROWS; ring++) {
            String label = String.format(Locale.ROOT, "ring_%02d", ring);
            assertTrue(RingTool.isRingLabel(label), label + " should draw as a ring");
            marksByLabel.put(label, new boolean[0]);
        }
        assertNumberedFromZero(RingTool.ringNumberTexts(marksByLabel.keySet(), 0), ROWS);
    }

    @Test
    void theNumberFollowsTheMapsOrderRatherThanTheLabelsDigits() {
        Map<String, boolean[]> marksByLabel = new LinkedHashMap<>();
        marksByLabel.put("ring_07", new boolean[0]);
        marksByLabel.put("ring_03", new boolean[0]);
        marksByLabel.put(SPLINE_RING, new boolean[0]);
        assertNumberedFromZero(RingTool.ringNumberTexts(marksByLabel.keySet(), 0),
                marksByLabel.size());
    }

    @Test
    void confirmedRingsAreNumberedAfterTheGraphsInConfirmOrder() {
        List<String> graphRings = List.of("ring_00", "ring_01");
        assertNumberedFromZero(RingTool.ringNumberTexts(graphRings, CONFIRMED),
                graphRings.size() + CONFIRMED);
    }

    @Test
    void aGraphWithNoRingStatementsNumbersTheToolsRingsFromZero() {
        assertNumberedFromZero(RingTool.ringNumberTexts(List.of(), CONFIRMED), CONFIRMED);
    }

    @Test
    void aMaskThatMerelySpellsRingIsNoRing() {
        assertFalse(RingTool.isRingLabel("spring_edges"), "a spring mask is not a ring");
        assertFalse(RingTool.isRingLabel("ring_seed"), "a seed walk draws as a feature edge");
        assertFalse(RingTool.isRingLabel("ring_tightened"));
        assertFalse(RingTool.isRingLabel("herring"));
        assertFalse(RingTool.isRingLabel("ring_"));
        assertFalse(RingTool.isRingLabel("ring_1a"));
        assertFalse(RingTool.isRingLabel(null));
        assertFalse(RingTool.isRingLabel("ring_1234567890"),
                "a number no ring count can reach is not a ring label");
    }

    @Test
    void theNodesDefaultRingLabelsStillDrawThick() {
        assertTrue(RingTool.isRingLabel(SPLINE_RING));
        assertTrue(RingTool.isRingLabel("ring"));
        assertTrue(RingTool.isRingLabel("ring7"), "the writer's bare statement id is a ring");
    }

    /**
     * Every ring in a drawing order draws its own 0-based position, which is the whole of the
     * numbering contract.
     *
     * @param drawn          the texts the overlay would draw, in drawing order
     * @param expectedLength rings the overlay should be drawing
     */
    private static void assertNumberedFromZero(String[] drawn, int expectedLength) {
        assertEquals(expectedLength, drawn.length, "the overlay drew a different number of rings");
        for (int ring = 0; ring < drawn.length; ring++) {
            assertEquals(String.valueOf(ring), drawn[ring],
                    "the ring at position " + ring + " drew " + drawn[ring]);
        }
    }
}
