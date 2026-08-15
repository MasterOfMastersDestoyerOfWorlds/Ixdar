package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.fixtures.SliverPinchFixture;

/**
 * The sliver-pinch fixture carries botijo's arc-collapse-3 shape: a drag whose direct edge to
 * the survivor is claimed and whose only free route runs through an unadmitted sliver sector.
 * The restriction must force the lane through the pinch instead, keeping the covers coherent.
 */
class SliverPinchFixtureTest {

    @Test
    void coversLabelEveryFaceWithoutOverlap() {
        SliverPinchFixture fixture = new SliverPinchFixture();

        fixture.tmesh.labelPatchCovers();

        assertTrue(fixture.tmesh.topology.patchByCopyFace.length > 0,
                "the patch walks must agree with the disk's winding, or the covers are dropped"
                        + " as overlapping and the drags run unrestricted");
        for (int face = 0; face < fixture.tmesh.topology.patchByCopyFace.length; face++) {
            assertTrue(fixture.tmesh.topology.patchByCopyFace[face] != EmbeddedTMesh.NONE,
                    "face " + face + " carries a label");
        }
    }

    @Test
    void centerCollapseMovesTheMovedNodeOntoTheSurvivor() {
        SliverPinchFixture fixture = new SliverPinchFixture();
        fixture.tmesh.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = fixture.tmesh.collapseArc;

        collapseArc.beginCollapse(fixture.channelArcId);

        assertEquals(fixture.movedNodeId, collapseArc.movedNodeId,
                "the survivor is critical, so the moved node moves");
        assertEquals(fixture.survivorNodeId, collapseArc.survivingNodeId,
                "the centre node survives");
        assertTrue(collapseArc.fan.contains(fixture.pinchArcId),
                "the pinch arc rides the fan");
    }

    /**
     * The wrong-slot regression guard: with no label-free ring escape, the sliver sector is
     * inadmissible, so the pinch arc's drag must mint its lane through the zero triangle's own
     * cell — the correct slot — and the covers survive the collapse.
     */
    @Test
    void pinchArcThreadsThePinchAndTheCoversHold() {
        SliverPinchFixture fixture = new SliverPinchFixture();
        fixture.tmesh.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = fixture.tmesh.collapseArc;

        collapseArc.beginCollapse(fixture.channelArcId);
        while (collapseArc.dragNextArc()) {
            continue;
        }
        collapseArc.finishCollapse();

        assertEquals(0, collapseArc.blockedDragCount, "no drag blocks");
        assertTrue(fixture.tmesh.arcs.get(fixture.pinchArcId).path.copyVertexPath
                .contains(fixture.tmesh.nodes.get(fixture.survivorNodeId).copyVertex),
                "the pinch arc ends on the survivor");
        assertNull(fixture.tmesh.flankTearFailure("sliver pinch"),
                "every arc still lies between the patches it names once the covers are re-read");
    }
}
