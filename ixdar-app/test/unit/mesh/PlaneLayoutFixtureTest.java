package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;


/**
 * The planar Figure 9 fixture must be a valid cell decomposition of a disk before anything is
 * concluded from driving operators across it, and it must actually carry the configuration the
 * figure is about — a non-simple zero-patch, so more than two non-zero arcs on a patch whose other
 * two sides are quantized zero.
 */
class PlaneLayoutFixtureTest {

    /** A disk has Euler characteristic one, for any cell decomposition of it. */
    private static final int PLANE_EULER_CHARACTERISTIC = 1;

    @Test
    void fixtureIsADiskCarryingANonSimpleZeroPatch() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/plane_layout.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");

        fixtureNet.validate();

        long liveNodes = fixtureNet.nodes.stream().filter(node -> node.alive).count();
        long liveArcs = fixtureNet.arcs.stream().filter(arc -> arc.alive).count();
        long livePatches = fixtureNet.patches.stream().filter(patch -> patch.alive).count();
        assertEquals(PLANE_EULER_CHARACTERISTIC,
                liveNodes - liveArcs + livePatches, "the fixture decomposes a disk");

        long zeroArcs = fixtureNet.arcs.stream()
                .filter(arc -> arc.alive && arc.quantizedLength == 0).count();
        assertEquals(2, zeroArcs, "the zero-patch is flattened by its two zero sides");

        assertTrue(fixtureNet.patches.stream().anyMatch(patch -> patch.alive
                && fixtureNet.isZeroPatch(patch.patchId)
                && fixtureNet.nonZeroArcCount(patch.patchId) > 2),
                "Figure 9's blue patch is non-simple: more than two non-zero arcs are involved,"
                        + " which is the T-joint the paper extends through the patch");
    }
}
