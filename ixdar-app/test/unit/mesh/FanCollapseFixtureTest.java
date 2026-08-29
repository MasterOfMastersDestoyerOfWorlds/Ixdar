package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.fixtures.FanCollapseFixture;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;

/**
 * The fan-collapse fixture must carry exactly the LCKB19 Figure 9 e-to-f configuration —
 * one zero spoke into a twelve-arc fan — and stepping operator (1) across it one drag at a
 * time must land the whole fan on the surviving node, matching the one-shot collapse.
 */
class FanCollapseFixtureTest {

    @Test
    void fixtureCarriesTheFanAroundOneZeroSpoke() {
        FanCollapseFixture fixture = new FanCollapseFixture();

        assertEquals(1 + FanCollapseFixture.CLOCK_POSITIONS, fixture.tmesh.nodes.size(),
                "one center node plus one node per clock position");
        assertEquals(2 * FanCollapseFixture.CLOCK_POSITIONS, fixture.tmesh.arcs.size(),
                "one spoke and one box arc per clock position");
        assertEquals(FanCollapseFixture.CLOCK_POSITIONS + 1, fixture.tmesh.patches.size(),
                "one sector per clock position plus the outer annulus");
        int zeroArcs = 0;
        for (EmbeddedArc arc : fixture.tmesh.arcs) {
            if (arc.quantizedLength == 0) {
                zeroArcs++;
            }
        }
        assertEquals(1, zeroArcs, "the 12-o'clock spoke is the only zero arc");
        EmbeddedArc zeroSpoke = fixture.tmesh.arcs.get(fixture.zeroSpokeArcId);
        assertEquals(fixture.centerNodeId, zeroSpoke.startNodeId,
                "the zero spoke leaves the center");
        assertEquals(fixture.boxNodeIdByHour[0], zeroSpoke.endNodeId,
                "the zero spoke ends at 12 o'clock");
        assertEquals(FanCollapseFixture.CLOCK_POSITIONS, fixture.tmesh.degree(fixture.centerNodeId),
                "every spoke ends on the center");
    }

    @Test
    void coversLabelEveryFaceWithoutOverlap() {
        FanCollapseFixture fixture = new FanCollapseFixture();

        fixture.tmesh.labelPatchCovers();

        assertTrue(fixture.tmesh.topology.patchByCopyFace.length > 0,
                "the sector walks must agree with the disk's winding, or the covers are dropped"
                        + " as overlapping and the drags run unrestricted");
    }

    @Test
    void centerMovesOntoTheCriticalBoxNode() {
        FanCollapseFixture fixture = new FanCollapseFixture();
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixture.tmesh).collapseArc;

        assertEquals(fixture.zeroSpokeArcId, collapseArc.mostContendedArc(),
                "the zero spoke is the only collapse candidate");
        collapseArc.beginCollapse(fixture.zeroSpokeArcId);
        assertEquals(fixture.centerNodeId, collapseArc.movedNodeId,
                "the critical 12-o'clock node pins that end, so the center moves");
        assertEquals(fixture.boxNodeIdByHour[0], collapseArc.survivingNodeId,
                "the 12-o'clock node survives");
    }

    /**
     * The Figure 9 e-to-f payoff: the whole fan drags onto the survivor one reroute at a time.
     * The mid-collapse cells fit no single label, so this passing is what proves the
     * touched-union restriction carries every drag home.
     */
    @Test
    void steppedCollapseDragsElevenSpokesOntoTheSurvivor() {
        FanCollapseFixture fixture = new FanCollapseFixture();
        fixture.tmesh.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixture.tmesh).collapseArc;

        collapseArc.beginCollapse(fixture.zeroSpokeArcId);
        int drags = 0;
        while (collapseArc.dragNextArc()) {
            drags++;
        }
        collapseArc.finishCollapse();

        assertEquals(FanCollapseFixture.CLOCK_POSITIONS - 1, drags,
                "every spoke but the collapsing one is dragged exactly once");
        assertEquals(0, collapseArc.blockedDragCount, "no drag blocks");
        assertFalse(fixture.tmesh.nodes.get(fixture.centerNodeId).alive,
                "the center node is retired into the survivor");
        assertFalse(fixture.tmesh.arcs.get(fixture.zeroSpokeArcId).alive,
                "the zero spoke is retired");
        int survivorVertex = fixture.tmesh.nodes.get(fixture.boxNodeIdByHour[0]).copyVertex;
        for (int hour = 1; hour < FanCollapseFixture.CLOCK_POSITIONS; hour++) {
            EmbeddedArc spoke = fixture.tmesh.arcs.get(fixture.spokeArcIdByHour[hour]);
            assertTrue(spoke.alive, "spoke " + hour + " survives the collapse");
            List<Integer> path = spoke.path.copyVertexPath;
            assertTrue(path.get(0) == survivorVertex
                    || path.get(path.size() - 1) == survivorVertex,
                    "spoke " + hour + " now ends on the survivor's vertex");
        }
        assertNull(fixture.tmesh.flankTearFailure("fan collapse"),
                "every arc still lies between the patches it names once the covers are re-read");
    }

    @Test
    void oneShotCollapseMatchesTheSteppedCollapse() {
        FanCollapseFixture stepped = new FanCollapseFixture();
        stepped.tmesh.labelPatchCovers();
        ZeroArcCollapseOperator steppedCollapse = new NetworkContraction(stepped.tmesh).collapseArc;
        steppedCollapse.beginCollapse(stepped.zeroSpokeArcId);
        while (steppedCollapse.dragNextArc()) {
            continue;
        }
        steppedCollapse.finishCollapse();

        FanCollapseFixture oneShot = new FanCollapseFixture();
        oneShot.tmesh.labelPatchCovers();
        new NetworkContraction(oneShot.tmesh).collapseArc.collapse(oneShot.zeroSpokeArcId);

        assertEquals(liveCounts(oneShot.tmesh), liveCounts(stepped.tmesh),
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
