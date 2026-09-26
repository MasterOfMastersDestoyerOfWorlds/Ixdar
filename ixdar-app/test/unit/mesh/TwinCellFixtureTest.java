package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;

/**
 * The twin-cell fixture carries botijo's operator-830 shape: the parallel wall's far node is
 * already the survivor, so the collapse closes the wall into a loop at that node.
 */
class TwinCellFixtureTest {

    /** The authored fixture graph. */
    private static final String DSL_PATH = "dsl/fixtures/twin_cell.dsl";

    /** Fixture output naming the authored arc network. */
    private static final String NETWORK_OUTPUT = "net";

    /** Fixture output naming the zero arc the collapse runs on. */
    private static final String CHANNEL_ARC = "channelArcId";

    /** Fixture output naming the wall running between the channel's own two nodes. */
    private static final String PARALLEL_ARC = "parallelArcId";

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
    void theFanHoldsThreeArcsAndTheOuterFanAbsorbs() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource(DSL_PATH, Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput(NETWORK_OUTPUT);
        fixtureNet.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixtureNet).collapseArc;

        collapseArc.beginCollapse(fixture.intOutput(CHANNEL_ARC));

        assertEquals(fixture.intOutput("movedNodeId"), collapseArc.movedNodeId,
                "the survivor is critical, so the moved node moves");
        assertEquals(3, collapseArc.fan.size(), "wall and both east arcs fill the fan");
        assertTrue(collapseArc.fan.contains(fixture.intOutput(PARALLEL_ARC)),
                "the parallel wall rides the fan");
        assertEquals(fixture.intOutput("outerFanArcId"), collapseArc.fan.get(1),
                "the outer fan arc sits mid-fan, so oscillating order drags it last and it"
                        + " absorbs the channel; the wall is searched instead");
    }

    /**
     * The operator-830 guard, as PATCH-111 restated it: both flanks of the wall keep a side,
     * so the collapse closes it into a loop rather than pointing it, which would merge two
     * live cells and take the ring slot the rest of the fan arrives in.
     */
    @Test
    void parallelWallClosesIntoALoopRatherThanPointing() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource(DSL_PATH, Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput(NETWORK_OUTPUT);
        fixtureNet.labelPatchCovers();
        ZeroArcCollapseOperator collapseArc = new NetworkContraction(fixtureNet).collapseArc;

        collapseArc.beginCollapse(fixture.intOutput(CHANNEL_ARC));
        while (collapseArc.dragNextArc()) {
            continue;
        }
        collapseArc.finishCollapse();

        assertEquals(0, collapseArc.blockedDragCount, "no drag blocks");
        EmbeddedArc wall = fixtureNet.arcs.get(fixture.intOutput(PARALLEL_ARC));
        assertTrue(wall.alive, "the wall survives: each of its flanks still holds a side");
        assertTrue(wall.path.copyVertexPath.size() > 1,
                "the wall carries a real path, not the surviving point");
        assertNotEquals(fixtureNet.topology.resolvePatch(fixture.intOutput("westCellPatchId")),
                fixtureNet.topology.resolvePatch(fixture.intOutput("southCellPatchId")),
                "the wall still separates its twin flanks, so they stay two patches");
        assertNull(fixtureNet.flankTearFailure("twin cell"),
                "every arc still lies between the patches it names once the covers are re-read");
    }
}
