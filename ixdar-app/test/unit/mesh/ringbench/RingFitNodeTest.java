package unit.mesh.ringbench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.rings.RegionClusterFit;
import ixdar.nodegraph.nodes.selection.RingFitNode;
import ixdar.nodegraph.runtime.GraphStepper;
import ixdar.nodegraph.runtime.GraphStepper.Action;
import ixdar.nodegraph.runtime.GraphStepper.Move;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * The {@code ring_fit} node: stepped a pass at a time in the graph debugger it records every pass
 * and ends with the ring where the whole fit leaves it.
 */
class RingFitNodeTest {

    private static final String RING = "ring_00";

    private static final String FITTED = "fitted_rings";

    private static final String FIT_STATEMENT = "\n" + FITTED + " = ring_fit(geometry=" + RING
            + ".geometry, metric=surface.metric, creases=creases.creases)\n";

    @Test
    void steppedAPassAtATimeTheFitEndsWhereTheWholeFitEnds() throws Exception {
        String source;
        try (InputStream stream = RingFitNodeTest.class.getClassLoader()
                .getResourceAsStream(RingFitBench.FIXTURE_RESOURCE_DIRECTORY + "one_groove.dsl")) {
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8) + FIT_STATEMENT;
        }
        NodeGraphRuntime whole = NodeGraphRuntime.fromSource(source);
        boolean[] wholeLoop = ringMarks(whole.executeGraphResult(FITTED, NodeGraphRuntime.GEOMETRY));

        GraphStepper stepper = new GraphStepper(NodeGraphRuntime.fromSource(source), Map.of());
        while (!(stepper.next().node instanceof RingFitNode)) {
            assertTrue(stepper.apply(new Action(Move.OVER, 0)), stepper.lastStep);
        }
        assertTrue(stepper.apply(new Action(Move.INTO, 0)), stepper.lastStep);
        assertNotNull(stepper.inner, "the fit node opens its inner steps");
        RegionClusterFit fit = (RegionClusterFit) stepper.inner.stepShown();
        assertEquals(1, fit.loopsByPass.size(), "before any pass only the ring as drawn is recorded");
        boolean[] drawnLoop = ringMarks(fit.drawn);

        int steps = 0;
        while (stepper.inner != null) {
            assertSame(fit, stepper.inner.stepShown(), "every step shows the one fit");
            assertTrue(stepper.apply(new Action(Move.INNER, 0)), stepper.lastStep);
            steps++;
        }
        assertTrue(fit.passes > 1, "the drawn ring is off its groove, so the fit takes passes: " + fit.passes);
        assertEquals(fit.passes + 1, steps, "one step per pass, then the step that finds none left");
        assertEquals(fit.passes + 1, fit.loopsByPass.size(), "every pass is recorded after the drawn ring");

        boolean[] steppedLoop = ringMarks(stepper.runtime.getNodeOutput(FITTED, NodeGraphRuntime.GEOMETRY));
        assertArrayEquals(wholeLoop, steppedLoop, "the stepped fit ends on the edges the whole fit ends on");
        assertFalse(Arrays.equals(drawnLoop, steppedLoop), "and the ring has moved off where it was drawn");
    }

    /**
     * The edges a bundle marks as the bench's ring.
     *
     * @param geometry a graph output holding a bundle with that ring's marks
     * @return the marks, by edge id
     */
    private static boolean[] ringMarks(Object geometry) {
        Map<?, ?> marks = (Map<?, ?>) ((GeometryBundle) geometry).slots().get(EdgeMarks.SLOT);
        return (boolean[]) marks.get(RING);
    }
}
