package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The planar Figure 9 fixture must be a valid cell decomposition of a disk before anything is
 * concluded from driving operators across it, and it must actually carry the configuration the
 * figure is about — a non-simple zero-patch, so more than two non-zero arcs on a patch whose other
 * two sides are quantized zero.
 */
class PlaneLayoutFixtureTest {

    @Test
    void fixtureIsADiskCarryingANonSimpleZeroPatch() {
        PlaneLayoutFixture fixture = new PlaneLayoutFixture();

        fixture.tmesh.validate(PlaneLayoutFixture.PLANE_EULER_CHARACTERISTIC);

        long liveNodes = fixture.tmesh.nodes.stream().filter(node -> node.alive).count();
        long liveArcs = fixture.tmesh.arcs.stream().filter(arc -> arc.alive).count();
        long livePatches = fixture.tmesh.patches.stream().filter(patch -> patch.alive).count();
        assertEquals(PlaneLayoutFixture.PLANE_EULER_CHARACTERISTIC,
                liveNodes - liveArcs + livePatches, "the fixture decomposes a disk");

        long zeroArcs = fixture.tmesh.arcs.stream()
                .filter(arc -> arc.alive && arc.quantizedLength == 0).count();
        assertEquals(2, zeroArcs, "the zero-patch is flattened by its two zero sides");

        assertTrue(fixture.tmesh.patches.stream().anyMatch(patch -> patch.alive
                && fixture.tmesh.isZeroPatch(patch.patchId)
                && fixture.tmesh.nonZeroArcCount(patch.patchId) > 2),
                "Figure 9's blue patch is non-simple: more than two non-zero arcs are involved,"
                        + " which is the T-joint the paper extends through the patch");
    }
}
