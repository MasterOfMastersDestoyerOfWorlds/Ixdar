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
 * The twin-cell fixture carries botijo's operator-830 shape: the parallel wall's far node is
 * already the survivor, so the collapse degenerates it to a point — a paper-legal outcome
 * for the all-zero web — and the bookkeeping must retire it and merge its twin flanks
 * rather than leave two live patches sharing one unlabeled cell.
 */
class TwinCellFixtureTest {

    @Test
    void coversLabelEveryFaceWithoutOverlap() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/twin_cell.dsl", Map.of());
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
    void theFanHoldsThreeArcsAndTheOuterFanAbsorbs() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/twin_cell.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        fixtureNet.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixtureNet).collapseArc;

        collapseArc.beginCollapse(fixture.intOutput("channelArcId"));

        assertEquals(fixture.intOutput("movedNodeId"), collapseArc.movedNodeId,
                "the survivor is critical, so the moved node moves");
        assertEquals(3, collapseArc.fan.size(), "wall and both east arcs fill the fan");
        assertTrue(collapseArc.fan.contains(fixture.intOutput("parallelArcId")),
                "the parallel wall rides the fan");
        assertEquals(fixture.intOutput("outerFanArcId"), collapseArc.fan.get(1),
                "the outer fan arc sits mid-fan, so oscillating order drags it last and it"
                        + " absorbs the channel; the wall is searched instead");
    }

    /**
     * The operator-830 regression guard: pointing the wall retires it and merges its twin
     * flanks, so the covers stay coherent with no arc claiming a vanished cell.
     */
    @Test
    void parallelWallIsRetiredAndItsTwinFlanksMergeOnPointing() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/twin_cell.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        fixtureNet.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixtureNet).collapseArc;

        collapseArc.beginCollapse(fixture.intOutput("channelArcId"));
        while (collapseArc.dragNextArc()) {
            continue;
        }
        collapseArc.finishCollapse();

        assertEquals(0, collapseArc.blockedDragCount, "no drag blocks");
        assertFalse(fixtureNet.arcs.get(fixture.intOutput("parallelArcId")).alive,
                "the wall degenerated to a point, so it is retired: a point separates nothing");
        assertEquals(fixtureNet.topology.resolvePatch(fixture.intOutput("westCellPatchId")),
                fixtureNet.topology.resolvePatch(fixture.intOutput("southCellPatchId")),
                "the wall's twin flanks resolve to one merged patch");
        assertNull(fixtureNet.flankTearFailure("twin cell"),
                "every arc still lies between the patches it names once the covers are re-read");
    }
}
