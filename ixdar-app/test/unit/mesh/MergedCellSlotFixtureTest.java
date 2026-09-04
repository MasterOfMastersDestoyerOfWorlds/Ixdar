package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;

/**
 * The merged-cell-slot fixture carries botijo's operator-16 shape: releasing the bait arc's
 * claims merges its two flanks into one cell, and the free-pass route arrives through the far
 * flank's free-spoke wedge instead of the minted lane beside the channel — the wrong cyclic
 * slot, inside admitted labels, crossing no claims.
 */
class MergedCellSlotFixtureTest {

    @Test
    void coversLabelEveryFaceWithoutOverlap() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/merged_cell_slot.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");

        fixtureNet.labelPatchCovers();

        assertTrue(fixtureNet.topology.patchByCopyFace.length > 0,
                "the patch walks must agree with the disk's winding, or the covers are dropped"
                        + " as overlapping and the drags run unrestricted");
        for (int face = 0; face < fixtureNet.topology.patchByCopyFace.length; face++) {
            assertTrue(fixtureNet.topology.patchByCopyFace[face] != ArcNetwork.NONE,
                    "face " + face + " carries a label");
        }
    }

    @Test
    void baitArcRidesTheFanFirstAndTheTailAbsorbsTheChannel() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/merged_cell_slot.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        fixtureNet.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixtureNet).collapseArc;

        collapseArc.beginCollapse(fixture.intOutput("channelArcId"));

        assertEquals(fixture.intOutput("movedNodeId"), collapseArc.movedNodeId,
                "the survivor is critical, so the moved node moves");
        assertEquals(2, collapseArc.fan.size(), "the fan carries the bait and the tail");
        assertEquals(fixture.intOutput("baitArcId"), collapseArc.fan.get(0),
                "the bait arc is first in fan order, so it is searched and the tail splices");
    }

    /**
     * The merged-cell regression guard: the far wedge's flanks contradict the bait arc's, so
     * the arrival pre-ban forces the minted lane beside the channel — the correct slot — and
     * the collapse finishes with coherent covers.
     */
    @Test
    void baitDragIsBannedFromTheFarWedgeAndTheCollapseFinishesCleanly() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/merged_cell_slot.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        fixtureNet.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixtureNet).collapseArc;

        collapseArc.beginCollapse(fixture.intOutput("channelArcId"));
        assertTrue(collapseArc.dragNextArc(), "the bait arc drags first");
        assertTrue(collapseArc.bannedArrivalWedgeCount >= 1,
                "the far free-spoke wedge is banned before the search");
        while (collapseArc.dragNextArc()) {
            continue;
        }
        collapseArc.finishCollapse();

        assertEquals(0, collapseArc.blockedDragCount, "no drag blocks");
        assertTrue(fixtureNet.arcs.get(fixture.intOutput("baitArcId")).path.copyVertexPath
                .contains(fixtureNet.nodes.get(fixture.intOutput("survivorNodeId")).copyVertex),
                "the bait arc ends on the survivor");
        assertNull(fixtureNet.flankTearFailure("merged-cell slot"),
                "every arc still lies between the patches it names once the covers are re-read");
    }
}
