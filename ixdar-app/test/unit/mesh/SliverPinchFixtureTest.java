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
 * The sliver-pinch fixture carries botijo's arc-collapse-3 shape: a drag whose direct edge to
 * the survivor is claimed and whose only free route runs through an unadmitted sliver sector.
 * The restriction must force the lane through the pinch instead, keeping the covers coherent.
 */
class SliverPinchFixtureTest {

    @Test
    void coversLabelEveryFaceWithoutOverlap() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/sliver_pinch.dsl", Map.of());
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
    void centerCollapseMovesTheMovedNodeOntoTheSurvivor() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/sliver_pinch.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        fixtureNet.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixtureNet).collapseArc;

        collapseArc.beginCollapse(fixture.intOutput("channelArcId"));

        assertEquals(fixture.intOutput("movedNodeId"), collapseArc.movedNodeId,
                "the survivor is critical, so the moved node moves");
        assertEquals(fixture.intOutput("survivorNodeId"), collapseArc.survivingNodeId,
                "the centre node survives");
        assertTrue(collapseArc.fan.contains(fixture.intOutput("pinchArcId")),
                "the pinch arc rides the fan");
    }

    /**
     * The wrong-slot regression guard: with no label-free ring escape, the sliver sector is
     * inadmissible, so the pinch arc's drag must mint its lane through the zero triangle's own
     * cell — the correct slot — and the covers survive the collapse.
     */
    @Test
    void pinchArcThreadsThePinchAndTheCoversHold() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/sliver_pinch.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        fixtureNet.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixtureNet).collapseArc;

        collapseArc.beginCollapse(fixture.intOutput("channelArcId"));
        while (collapseArc.dragNextArc()) {
            continue;
        }
        collapseArc.finishCollapse();

        assertEquals(0, collapseArc.blockedDragCount, "no drag blocks");
        assertTrue(fixtureNet.arcs.get(fixture.intOutput("pinchArcId")).path.copyVertexPath
                .contains(fixtureNet.nodes.get(fixture.intOutput("survivorNodeId")).copyVertex),
                "the pinch arc ends on the survivor");
        assertNull(fixtureNet.flankTearFailure("sliver pinch"),
                "every arc still lies between the patches it names once the covers are re-read");
    }
}
