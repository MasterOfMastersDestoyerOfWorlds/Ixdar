package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.fixtures.MergedCellSlotFixture;

/**
 * The merged-cell-slot fixture carries botijo's operator-16 shape: releasing the bait arc's
 * claims merges its two flanks into one cell, and the free-pass route arrives through the far
 * flank's free-spoke wedge instead of the minted lane beside the channel — the wrong cyclic
 * slot, inside admitted labels, crossing no claims.
 */
class MergedCellSlotFixtureTest {

    @Test
    void coversLabelEveryFaceWithoutOverlap() {
        MergedCellSlotFixture fixture = new MergedCellSlotFixture();

        fixture.tmesh.labelPatchCovers();

        assertTrue(fixture.tmesh.topology.patchByCopyFace.length > 0,
                "the patch walks must agree with the disk's winding, or the covers are dropped"
                        + " as overlapping and the drags run unrestricted");
        for (int face = 0; face < fixture.tmesh.topology.patchByCopyFace.length; face++) {
            assertTrue(fixture.tmesh.topology.patchByCopyFace[face] != ArcNetwork.NONE,
                    "face " + face + " carries a label");
        }
    }

    @Test
    void baitArcRidesTheFanFirstAndTheTailAbsorbsTheChannel() {
        MergedCellSlotFixture fixture = new MergedCellSlotFixture();
        fixture.tmesh.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixture.tmesh).collapseArc;

        collapseArc.beginCollapse(fixture.channelArcId);

        assertEquals(fixture.movedNodeId, collapseArc.movedNodeId,
                "the survivor is critical, so the moved node moves");
        assertEquals(2, collapseArc.fan.size(), "the fan carries the bait and the tail");
        assertEquals(fixture.baitArcId, collapseArc.fan.get(0),
                "the bait arc is first in fan order, so it is searched and the tail splices");
    }

    /**
     * The merged-cell regression guard: the far wedge's flanks contradict the bait arc's, so
     * the arrival pre-ban forces the minted lane beside the channel — the correct slot — and
     * the collapse finishes with coherent covers.
     */
    @Test
    void baitDragIsBannedFromTheFarWedgeAndTheCollapseFinishesCleanly() {
        MergedCellSlotFixture fixture = new MergedCellSlotFixture();
        fixture.tmesh.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixture.tmesh).collapseArc;

        collapseArc.beginCollapse(fixture.channelArcId);
        assertTrue(collapseArc.dragNextArc(), "the bait arc drags first");
        assertTrue(collapseArc.bannedArrivalWedgeCount >= 1,
                "the far free-spoke wedge is banned before the search");
        while (collapseArc.dragNextArc()) {
            continue;
        }
        collapseArc.finishCollapse();

        assertEquals(0, collapseArc.blockedDragCount, "no drag blocks");
        assertTrue(fixture.tmesh.arcs.get(fixture.baitArcId).path.copyVertexPath
                .contains(fixture.tmesh.nodes.get(fixture.survivorNodeId).copyVertex),
                "the bait arc ends on the survivor");
        assertNull(fixture.tmesh.flankTearFailure("merged-cell slot"),
                "every arc still lies between the patches it names once the covers are re-read");
    }
}
