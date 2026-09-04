package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;

/**
 * LCBK19 §6.1 operator (1) on the Figure-9 torus fixture: collapsing a zero arc must remove
 * exactly that arc and its moved node, drag every other incident arc onto the surviving
 * node, and leave the surface's cell decomposition intact.
 *
 * <p>The load-bearing check throughout is the Euler characteristic. Operator (1) removes one
 * node and one arc together, so {@code V - E + F} must not move — a re-route that lost an arc,
 * a merge that dropped a node without its arc, or a patch left with a dangling side would all
 * change it. The fixture is genus 1, so it must stay zero after every collapse.
 */
class ZeroArcCollapseTest {

    @Test
    void collapsingOneZeroArcRemovesItAndKeepsTheDecomposition() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/torus_layout.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        ZeroArcCollapseOperator operator = new ZeroArcCollapseOperator(fixtureNet);

        int liveArcsBefore = countLive(fixtureNet);
        int arcId = operator.mostContendedArc();
        assertNotEquals(ArcNetwork.NONE, arcId, "the zero row must offer a collapsible arc");
        assertEquals(0, fixtureNet.arcs.get(arcId).quantizedLength, "it must be a zero arc");

        operator.collapse(arcId);

        fixtureNet.validate();
        assertTrue(fixtureNet.arcs.get(arcId).alive == false,
                "the collapsed arc is retired");
        assertEquals(liveArcsBefore - 1, countLive(fixtureNet),
                "exactly one arc leaves the T-mesh");
    }

    /**
     * Operator (1) applied until no collapsible zero arc remains. It removes the zero arcs
     * but not the zero patches: once a zero-patch's zero-length sides collapse, its two
     * positive sides run between the same pair of nodes as a bigon, which only operator (3)
     * can merge. So the post-condition here is "no zero arcs", not "no zero patches".
     */
    @Test
    void collapsingUntilNoneLeavesNoZeroArcsAndHoldsEuler() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/torus_layout.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        ZeroArcCollapseOperator operator = new ZeroArcCollapseOperator(fixtureNet);

        int guard = 0;
        for (int arcId = operator.mostContendedArc(); arcId != ArcNetwork.NONE;
                arcId = operator.mostContendedArc()) {
            operator.collapse(arcId);
            fixtureNet.validate();
            if (++guard > fixtureNet.arcs.size()) {
                throw new AssertionError("collapse did not terminate");
            }
        }

        for (EmbeddedArc arc : fixtureNet.arcs) {
            if (arc.alive) {
                assertNotEquals(0, arc.quantizedLength,
                        "arc " + arc.arcId + " is a zero arc that should have collapsed");
            }
        }
        assertTrue(operator.collapsedCount >= 3,
                "the fixture's zero row has at least three collapsible arcs, got "
                        + operator.collapsedCount);
    }

    /**
     * The number of live arcs in a T-mesh.
     *
     * @param tmesh T-mesh to count
     * @return count of arcs still part of the layout
     */
    private static int countLive(ArcNetwork tmesh) {
        int count = 0;
        for (EmbeddedArc arc : tmesh.arcs) {
            if (arc.alive) {
                count++;
            }
        }
        return count;
    }
}
