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

    /** The authored fixture graph. */
    private static final String DSL_PATH = "dsl/fixtures/merged_cell_slot.dsl";

    /** Fixture output naming the authored arc network. */
    private static final String NETWORK_OUTPUT = "net";

    /** Fixture output naming the zero arc the collapse runs on. */
    private static final String CHANNEL_ARC = "channelArcId";

    /** Fixture output naming the arc that is dragged first. */
    private static final String BAIT_ARC = "baitArcId";

    @Test
    void coversLabelEveryFaceWithoutOverlap() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource(DSL_PATH, Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput(NETWORK_OUTPUT);

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
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource(DSL_PATH, Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput(NETWORK_OUTPUT);
        fixtureNet.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixtureNet).collapseArc;

        collapseArc.beginCollapse(fixture.intOutput(CHANNEL_ARC));

        assertEquals(fixture.intOutput("movedNodeId"), collapseArc.movedNodeId,
                "the survivor is critical, so the moved node moves");
        assertEquals(2, collapseArc.fan.size(), "the fan carries the bait and the tail");
        assertEquals(fixture.intOutput(BAIT_ARC), collapseArc.fan.get(0),
                "the bait arc is first in fan order, so it is searched and the tail splices");
    }

    /**
     * The merged-cell regression guard: the bait arc has to arrive beside the channel rather
     * than through the far side of the cell releasing it merged, which is what leaves every
     * arc between the patches it names once the covers are re-read.
     */
    @Test
    void baitDragTakesTheSlotBesideTheChannelAndTheCollapseFinishesCleanly() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource(DSL_PATH, Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput(NETWORK_OUTPUT);
        fixtureNet.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixtureNet).collapseArc;

        collapseArc.beginCollapse(fixture.intOutput(CHANNEL_ARC));
        assertTrue(collapseArc.dragNextArc(), "the bait arc drags first");
        while (collapseArc.dragNextArc()) {
            continue;
        }
        collapseArc.finishCollapse();

        assertEquals(0, collapseArc.blockedDragCount, "no drag blocks");
        assertTrue(fixtureNet.arcs.get(fixture.intOutput(BAIT_ARC)).path.copyVertexPath
                .contains(fixtureNet.nodes.get(fixture.intOutput("survivorNodeId")).copyVertex),
                "the bait arc ends on the survivor");
        assertNull(fixtureNet.flankTearFailure("merged-cell slot"),
                "every arc still lies between the patches it names once the covers are re-read");
    }
}
