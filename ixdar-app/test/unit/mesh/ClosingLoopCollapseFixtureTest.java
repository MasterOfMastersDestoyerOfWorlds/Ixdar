package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;

/**
 * A zero arc whose sibling runs between the same pair of nodes: the collapse closes that
 * sibling into a loop at the survivor, and it may not be embedded on the point, because the
 * cell it would pinch off is the slot the rest of the fan arrives in.
 */
class ClosingLoopCollapseFixtureTest {

    /** Fixture output naming the authored arc network. */
    private static final String NETWORK_OUTPUT = "net";

    /** Fixture output naming the zero arc the tests collapse. */
    private static final String ZERO_ARC = "zeroArcId";

    /** Fixture output naming the sibling that closes into a loop. */
    private static final String LOOP_ARC = "loopArcId";

    /** Fixture outputs naming the moving node's arcs whose far nodes are not the survivor. */
    private static final List<String> SPOKE_ARCS = List.of("innerSealArcId", "outerSealArcId");

    private static final String DSL_PATH = "dsl/fixtures/closing_loop_collapse.dsl";

    @Test
    void fixtureCarriesASiblingBetweenTheCollapsingArcsOwnNodes() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource(DSL_PATH, Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput(NETWORK_OUTPUT);

        EmbeddedArc zeroArc = fixtureNet.arcs.get(fixture.intOutput(ZERO_ARC));
        EmbeddedArc loopArc = fixtureNet.arcs.get(fixture.intOutput(LOOP_ARC));
        assertEquals(0, zeroArc.quantizedLength, "the authored zero arc is the collapse candidate");
        String betweenTheSameNodes = "the sibling runs between the zero arc's own two nodes";
        assertEquals(zeroArc.startNodeId, loopArc.startNodeId, betweenTheSameNodes);
        assertEquals(zeroArc.endNodeId, loopArc.endNodeId, betweenTheSameNodes);
        assertEquals(2, flanksKeepingExtent(fixtureNet, loopArc, zeroArc.arcId),
                "both flanks of the sibling keep a side of non-zero length once the zero arc"
                        + " goes, so embedding it on the point would pinch off a live cell");
    }

    /**
     * The regression: the sibling has to survive the collapse as a loop carrying a real path.
     * Embedded on the surviving point it takes the ring slot the last fan arc arrives in, and
     * the drag of that arc then finds no wedge bounding both of its patches.
     */
    @Test
    void theSiblingSurvivesTheCollapseAsALoopWithARealPath() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource(DSL_PATH, Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput(NETWORK_OUTPUT);
        fixtureNet.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixtureNet).collapseArc;
        int survivorVertex = survivorVertex(fixtureNet, fixture.intOutput(ZERO_ARC));

        collapseArc.collapse(fixture.intOutput(ZERO_ARC));

        assertEquals(0, collapseArc.blockedDragCount, "no drag blocks");
        EmbeddedArc loopArc = fixtureNet.arcs.get(fixture.intOutput(LOOP_ARC));
        List<Integer> path = loopArc.path.copyVertexPath;
        assertTrue(loopArc.alive, "the sibling survives the collapse");
        assertTrue(path.size() > 1,
                "the sibling keeps a path: embedded on the point it would pinch off the cell its"
                        + " flanks still bound");
        assertEquals(survivorVertex, path.get(0), "the sibling now leaves the survivor");
        assertEquals(survivorVertex, path.get(path.size() - 1),
                "the sibling now returns to the survivor");
        assertNull(fixtureNet.flankTearFailure("closing loop collapse"),
                "every arc still lies between the patches it names once the covers are re-read");
    }

    /**
     * The spokes whose far nodes are not the survivor have to follow the moving node through
     * the slot the closing loop leaves, which is the arrival a pinched-off cell destroys.
     */
    @Test
    void theSpokesFollowTheMovingNodeOntoTheSurvivor() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource(DSL_PATH, Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput(NETWORK_OUTPUT);
        fixtureNet.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixtureNet).collapseArc;
        int survivorVertex = survivorVertex(fixtureNet, fixture.intOutput(ZERO_ARC));

        collapseArc.collapse(fixture.intOutput(ZERO_ARC));

        for (String spoke : SPOKE_ARCS) {
            EmbeddedArc arc = fixtureNet.arcs.get(fixture.intOutput(spoke));
            List<Integer> path = arc.path.copyVertexPath;
            assertTrue(arc.alive, spoke + " survives the collapse");
            assertTrue(path.get(0) == survivorVertex || path.get(path.size() - 1) == survivorVertex,
                    spoke + " now ends on the survivor's vertex");
        }
        fixtureNet.validate();
    }

    /**
     * How many of an arc's flanks still carry a side of non-zero length once the collapsing
     * arc is gone, which is what says whether the merge may embed the arc on the point.
     *
     * @param tmesh           the authored network
     * @param arc             arc whose flanks are counted
     * @param collapsingArcId zero arc the collapse retires
     * @return the number of its flanks keeping extent of their own
     */
    private static int flanksKeepingExtent(ArcNetwork tmesh, EmbeddedArc arc,
            int collapsingArcId) {
        int keeping = 0;
        for (int patchId : new int[] { arc.leftPatchId, arc.rightPatchId }) {
            EmbeddedPatch patch = tmesh.patches.get(tmesh.topology.resolvePatch(patchId));
            boolean keepsExtent = false;
            for (List<Integer> sideArcIds : patch.sideArcIds) {
                for (int sideArcId : sideArcIds) {
                    keepsExtent |= sideArcId != arc.arcId && sideArcId != collapsingArcId
                            && tmesh.arcs.get(sideArcId).quantizedLength != 0;
                }
            }
            keeping += keepsExtent ? 1 : 0;
        }
        return keeping;
    }

    /**
     * The copy vertex the collapse keeps, which is the critical node the zero arc starts at.
     *
     * @param tmesh     the authored network
     * @param zeroArcId the zero arc being collapsed
     * @return the surviving node's copy vertex
     */
    private static int survivorVertex(ArcNetwork tmesh, int zeroArcId) {
        return tmesh.nodes.get(tmesh.arcs.get(zeroArcId).startNodeId).copyVertex;
    }
}
