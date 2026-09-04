package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;

/**
 * The fan-collapse fixture must carry exactly the LCKB19 Figure 9 e-to-f configuration —
 * one zero spoke into a twelve-arc fan — and stepping operator (1) across it one drag at a
 * time must land the whole fan on the surviving node, matching the one-shot collapse.
 */
class FanCollapseFixtureTest {

    /** Clock positions on the box, one node each; hour zero is 12 o'clock. */
    private static final int CLOCK_POSITIONS = 12;

    private static final String DSL_PATH = "dsl/fixtures/fan_collapse.dsl";

    @Test
    void fixtureCarriesTheFanAroundOneZeroSpoke() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource(DSL_PATH, Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");

        assertEquals(1 + CLOCK_POSITIONS, fixtureNet.nodes.size(),
                "one center node plus one node per clock position");
        assertEquals(2 * CLOCK_POSITIONS, fixtureNet.arcs.size(),
                "one spoke and one box arc per clock position");
        assertEquals(CLOCK_POSITIONS + 1, fixtureNet.patches.size(),
                "one sector per clock position plus the outer annulus");
        int zeroArcs = 0;
        for (EmbeddedArc arc : fixtureNet.arcs) {
            if (arc.quantizedLength == 0) {
                zeroArcs++;
            }
        }
        assertEquals(1, zeroArcs, "the 12-o'clock spoke is the only zero arc");
        EmbeddedArc zeroSpoke = fixtureNet.arcs.get(fixture.intOutput("zeroSpokeArcId"));
        assertEquals(fixture.intOutput("centerNodeId"), zeroSpoke.startNodeId,
                "the zero spoke leaves the center");
        assertEquals(fixture.intOutput("boxNodeIdByHour0"), zeroSpoke.endNodeId,
                "the zero spoke ends at 12 o'clock");
        assertEquals(CLOCK_POSITIONS, fixtureNet.degree(fixture.intOutput("centerNodeId")),
                "every spoke ends on the center");
    }

    @Test
    void coversLabelEveryFaceWithoutOverlap() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource(DSL_PATH, Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");

        fixtureNet.labelPatchCovers();

        assertTrue(fixtureNet.topology.patchByCopyFace.length > 0,
                "the sector walks must agree with the disk's winding, or the covers are dropped"
                        + " as overlapping and the drags run unrestricted");
    }

    @Test
    void centerMovesOntoTheCriticalBoxNode() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource(DSL_PATH, Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixtureNet).collapseArc;

        assertEquals(fixture.intOutput("zeroSpokeArcId"), collapseArc.mostContendedArc(),
                "the zero spoke is the only collapse candidate");
        collapseArc.beginCollapse(fixture.intOutput("zeroSpokeArcId"));
        assertEquals(fixture.intOutput("centerNodeId"), collapseArc.movedNodeId,
                "the critical 12-o'clock node pins that end, so the center moves");
        assertEquals(fixture.intOutput("boxNodeIdByHour0"), collapseArc.survivingNodeId,
                "the 12-o'clock node survives");
    }

    /**
     * The Figure 9 e-to-f payoff: the whole fan drags onto the survivor one reroute at a time.
     * The mid-collapse cells fit no single label, so this passing is what proves the
     * touched-union restriction carries every drag home.
     */
    @Test
    void steppedCollapseDragsElevenSpokesOntoTheSurvivor() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource(DSL_PATH, Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        fixtureNet.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixtureNet).collapseArc;

        collapseArc.beginCollapse(fixture.intOutput("zeroSpokeArcId"));
        int drags = 0;
        while (collapseArc.dragNextArc()) {
            drags++;
        }
        collapseArc.finishCollapse();

        assertEquals(CLOCK_POSITIONS - 1, drags,
                "every spoke but the collapsing one is dragged exactly once");
        assertEquals(0, collapseArc.blockedDragCount, "no drag blocks");
        assertFalse(fixtureNet.nodes.get(fixture.intOutput("centerNodeId")).alive,
                "the center node is retired into the survivor");
        assertFalse(fixtureNet.arcs.get(fixture.intOutput("zeroSpokeArcId")).alive,
                "the zero spoke is retired");
        int survivorVertex = fixtureNet.nodes.get(fixture.intOutput("boxNodeIdByHour0")).copyVertex;
        for (int hour = 1; hour < CLOCK_POSITIONS; hour++) {
            EmbeddedArc spoke = fixtureNet.arcs.get(fixture.intOutput("spokeArcIdByHour" + hour));
            assertTrue(spoke.alive, "spoke " + hour + " survives the collapse");
            List<Integer> path = spoke.path.copyVertexPath;
            assertTrue(path.get(0) == survivorVertex
                    || path.get(path.size() - 1) == survivorVertex,
                    "spoke " + hour + " now ends on the survivor's vertex");
        }
        assertNull(fixtureNet.flankTearFailure("fan collapse"),
                "every arc still lies between the patches it names once the covers are re-read");
    }

    @Test
    void oneShotCollapseMatchesTheSteppedCollapse() {
        NodeGraphRuntime stepped = NodeGraphRuntime.executeResource(DSL_PATH, Map.of());
        ArcNetwork steppedNet = (ArcNetwork) stepped.lastOutput("net");
        steppedNet.labelPatchCovers();
        ZeroArcCollapseOperator steppedCollapse = new NetworkContraction(steppedNet).collapseArc;
        steppedCollapse.beginCollapse(stepped.intOutput("zeroSpokeArcId"));
        while (steppedCollapse.dragNextArc()) {
            continue;
        }
        steppedCollapse.finishCollapse();

        NodeGraphRuntime oneShot = NodeGraphRuntime.executeResource(DSL_PATH, Map.of());
        ArcNetwork oneShotNet = (ArcNetwork) oneShot.lastOutput("net");
        oneShotNet.labelPatchCovers();
        new NetworkContraction(oneShotNet).collapseArc.collapse(oneShot.intOutput("zeroSpokeArcId"));

        assertEquals(liveCounts(oneShotNet), liveCounts(steppedNet),
                "stepping the collapse must leave the same live element counts as one shot");
    }

    /**
     * The live node, arc and patch counts of a T-mesh, as comparable text.
     *
     * @param tmesh T-mesh to count
     * @return {@code nodes/arcs/patches} counts
     */
    private static String liveCounts(ArcNetwork tmesh) {
        int nodes = 0;
        for (int nodeId = 0; nodeId < tmesh.nodes.size(); nodeId++) {
            nodes += tmesh.nodes.get(nodeId).alive ? 1 : 0;
        }
        int arcs = 0;
        for (EmbeddedArc arc : tmesh.arcs) {
            arcs += arc.alive ? 1 : 0;
        }
        int patches = 0;
        for (EmbeddedPatch patch : tmesh.patches) {
            patches += patch.alive ? 1 : 0;
        }
        return nodes + "/" + arcs + "/" + patches;
    }
}
