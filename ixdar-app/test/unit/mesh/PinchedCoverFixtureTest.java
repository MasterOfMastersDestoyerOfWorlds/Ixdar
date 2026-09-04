package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;

/**
 * The pinched-cover fixture carries botijo's operator-755 shape: a bait drag that once rode
 * its released hop through the moved vertex, pinching the transferred sliver off its patch
 * when the channel was re-claimed through the same vertex. Only the fan's last drag may
 * transit the moved vertex, so the bait must peel off before it and the single-seed cover
 * relabel stays sufficient.
 */
class PinchedCoverFixtureTest {

    @Test
    void coversLabelEveryFaceWithoutOverlap() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/pinched_cover.dsl", Map.of());
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
    void theFanHoldsThreeArcsAndTheAbsorberDragsLast() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/pinched_cover.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        fixtureNet.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixtureNet).collapseArc;

        collapseArc.beginCollapse(fixture.intOutput("channelArcId"));

        assertEquals(fixture.intOutput("movedNodeId"), collapseArc.movedNodeId,
                "the survivor is critical, so the moved node moves");
        assertEquals(3, collapseArc.fan.size(), "bait, absorber and filler fill the fan");
        assertTrue(collapseArc.fan.contains(fixture.intOutput("baitArcId")), "the bait rides the fan");
        assertEquals(fixture.intOutput("absorberArcId"),
                collapseArc.fan.get(1), "the absorber sits mid-fan, so oscillating order"
                        + " drags it last, the only drag allowed to transit the moved vertex");
    }

    /**
     * The operator-755 regression guard: an early drag may not transit the moved vertex, so
     * the bait peels off before it, the sliver stays flood-connected to its patch round the
     * moved vertex, and the covers hold without any relabel surgery.
     */
    @Test
    void baitPeelsOffBeforeTheMovedVertexAndTheCoversHold() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/pinched_cover.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        fixtureNet.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixtureNet).collapseArc;

        collapseArc.beginCollapse(fixture.intOutput("channelArcId"));
        while (collapseArc.dragNextArc()) {
            continue;
        }
        collapseArc.finishCollapse();

        assertEquals(0, collapseArc.blockedDragCount, "no drag blocks");
        assertFalse(fixtureNet.arcs.get(fixture.intOutput("baitArcId")).path.copyVertexPath
                .contains(collapseArc.movedVertex),
                "the bait peels off before the moved vertex; transit is the last drag's alone");
        assertNull(fixtureNet.flankTearFailure("pinched cover"),
                "every arc still lies between the patches it names once the covers are re-read");
    }
}
