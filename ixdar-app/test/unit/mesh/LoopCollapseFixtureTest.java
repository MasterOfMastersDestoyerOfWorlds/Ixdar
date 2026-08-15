package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.fixtures.LoopCollapseFixture;

/**
 * The loop-collapse fixture carries botijo's operator-756 shape: a zero loop bounding an
 * empty one-sided cell contracts in place — no fan arc moves or absorbs the closed channel,
 * and the inside cell's stale cover resolves into the outside flank.
 */
class LoopCollapseFixtureTest {

    @Test
    void coversLabelEveryFaceWithoutOverlap() {
        LoopCollapseFixture fixture = new LoopCollapseFixture();

        fixture.tmesh.labelPatchCovers();

        assertTrue(fixture.tmesh.topology.patchByCopyFace.length > 0,
                "the patch walks must agree with the disk's winding, or the covers are dropped"
                        + " as overlapping and the drags run unrestricted");
        for (int face = 0; face < fixture.tmesh.topology.patchByCopyFace.length; face++) {
            assertTrue(fixture.tmesh.topology.patchByCopyFace[face] != EmbeddedTMesh.NONE,
                    "face " + face + " carries a label");
        }
    }

    /**
     * The operator-756 regression guard: the loop contracts in place, the fan keeps its
     * paths, and the emptied inside cell aliases into the outside flank.
     */
    @Test
    void loopContractsInPlaceAndItsInsideCellResolvesIntoTheOutsideFlank() {
        LoopCollapseFixture fixture = new LoopCollapseFixture();
        fixture.tmesh.labelPatchCovers();
        assertNull(fixture.tmesh.flankTearFailure("authored loop"),
                "the hand-set loop flanks agree with the covers before anything collapses");
        ZeroArcCollapseOperator collapseArc = fixture.tmesh.collapseArc;
        List<Integer> eastPathBefore = List.copyOf(
                fixture.tmesh.arcs.get(fixture.eastArcId).path.copyVertexPath);
        List<Integer> westPathBefore = List.copyOf(
                fixture.tmesh.arcs.get(fixture.westArcId).path.copyVertexPath);

        collapseArc.beginCollapse(fixture.loopArcId);
        int dragCount = 0;
        while (collapseArc.dragNextArc()) {
            dragCount++;
        }
        collapseArc.finishCollapse();

        assertEquals(collapseArc.movedNodeId, collapseArc.survivingNodeId,
                "the loop's node survives where it stands");
        assertEquals(0, dragCount, "a contracting loop moves nothing");
        assertEquals(eastPathBefore, fixture.tmesh.arcs.get(fixture.eastArcId).path.copyVertexPath,
                "the east fan arc keeps its path");
        assertEquals(westPathBefore, fixture.tmesh.arcs.get(fixture.westArcId).path.copyVertexPath,
                "the west fan arc keeps its path");
        assertFalse(fixture.tmesh.arcs.get(fixture.loopArcId).alive, "the loop is retired");
        assertFalse(fixture.tmesh.patches.get(fixture.insidePatchId).alive,
                "the one-sided inside cell is retired with its boundary");
        assertEquals(fixture.outsidePatchId,
                fixture.tmesh.topology.resolvePatch(fixture.insidePatchId),
                "the inside cell aliases into the outside flank, which absorbs its cover");
        assertNull(fixture.tmesh.flankTearFailure("loop collapse"),
                "every arc still lies between the patches it names once the covers are re-read");
    }
}
