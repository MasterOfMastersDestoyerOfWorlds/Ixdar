package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;

/**
 * The loop-collapse fixture carries botijo's operator-756 shape: a zero loop bounding an
 * empty one-sided cell contracts in place — no fan arc moves or absorbs the closed channel,
 * and the inside cell's stale cover resolves into the outside flank.
 */
class LoopCollapseFixtureTest {

    @Test
    void coversLabelEveryFaceWithoutOverlap() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/loop_collapse.dsl", Map.of());
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

    /**
     * The operator-756 regression guard: the loop contracts in place, the fan keeps its
     * paths, and the emptied inside cell aliases into the outside flank.
     */
    @Test
    void loopContractsInPlaceAndItsInsideCellResolvesIntoTheOutsideFlank() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/loop_collapse.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        fixtureNet.labelPatchCovers();
        assertNull(fixtureNet.flankTearFailure("authored loop"),
                "the hand-set loop flanks agree with the covers before anything collapses");
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixtureNet).collapseArc;
        List<Integer> eastPathBefore = List.copyOf(
                fixtureNet.arcs.get(fixture.intOutput("eastArcId")).path.copyVertexPath);
        List<Integer> westPathBefore = List.copyOf(
                fixtureNet.arcs.get(fixture.intOutput("westArcId")).path.copyVertexPath);

        collapseArc.beginCollapse(fixture.intOutput("loopArcId"));
        int dragCount = 0;
        while (collapseArc.dragNextArc()) {
            dragCount++;
        }
        collapseArc.finishCollapse();

        assertEquals(collapseArc.movedNodeId, collapseArc.survivingNodeId,
                "the loop's node survives where it stands");
        assertEquals(0, dragCount, "a contracting loop moves nothing");
        assertEquals(eastPathBefore, fixtureNet.arcs.get(fixture.intOutput("eastArcId")).path.copyVertexPath,
                "the east fan arc keeps its path");
        assertEquals(westPathBefore, fixtureNet.arcs.get(fixture.intOutput("westArcId")).path.copyVertexPath,
                "the west fan arc keeps its path");
        assertFalse(fixtureNet.arcs.get(fixture.intOutput("loopArcId")).alive, "the loop is retired");
        assertFalse(fixtureNet.patches.get(fixture.intOutput("insidePatchId")).alive,
                "the one-sided inside cell is retired with its boundary");
        assertEquals(fixture.intOutput("outsidePatchId"),
                fixtureNet.topology.resolvePatch(fixture.intOutput("insidePatchId")),
                "the inside cell aliases into the outside flank, which absorbs its cover");
        assertNull(fixtureNet.flankTearFailure("loop collapse"),
                "every arc still lies between the patches it names once the covers are re-read");
    }
}
