package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.fixtures.PlaneLayoutFixture;
import ixdar.geometry.mesh.data.representation.IntIdList;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroPatchSplitOperator;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;

/**
 * A patch's face flood must land inside the patch whichever way its boundary was walked.
 *
 * <p>The flood is walled by the patch's own boundary arcs and seeded from one side of one of them,
 * chosen by {@code leftPatchId}. Which side that names depends on the direction {@code addPatch}
 * walked, which is the caller's side ordering rather than a fact about the surface, so
 * {@code resolveWalkOrientation} measures the true side once and restates every arc in those terms.
 *
 * <p>Reversing the walk models a layout whose sides are ordered the other way round. The faces a
 * patch covers cannot depend on it — on fertility that dependence had five of six operator-(2)
 * splits flooding 99.997% of the mesh and handing the router the whole surface as a corridor.
 */
class PatchInteriorSeedTest {

    /** Faces the fixture's three patches cover between them, out of the wider grid. */
    private static final int LAYOUT_FACES = 64;

    @Test
    void eachPatchFloodsItsOwnInterior() {
        List<Integer> asBuilt = floodSizes(new PlaneLayoutFixture(), false);

        assertTrue(asBuilt.size() > 1, "the fixture must carry several patches");
        assertEquals(LAYOUT_FACES, asBuilt.stream().mapToInt(Integer::intValue).sum(),
                "the fixture's patches tile a known region, so their floods must cover it exactly");
    }

    @Test
    void patchFloodStaysInsideWhicheverWayTheBoundaryWasWalked() {
        List<Integer> asBuilt = floodSizes(new PlaneLayoutFixture(), false);
        List<Integer> walkedTheOtherWay = floodSizes(new PlaneLayoutFixture(), true);

        assertEquals(asBuilt, walkedTheOtherWay,
                "a patch covers the same faces however its boundary was walked: seeding from the"
                        + " declared side alone floods the complement when the walk is reversed");
    }

    /**
     * The face count of every live patch's interior flood.
     *
     * @param fixture    layout to measure
     * @param swapSides  whether to swap every arc's left and right patch first, modelling sides
     *                   ordered the opposite way round
     * @return the flood sizes in patch order
     */
    private List<Integer> floodSizes(PlaneLayoutFixture fixture, boolean swapSides) {
        if (swapSides) {
            for (EmbeddedArc arc : fixture.tmesh.arcs) {
                int left = arc.leftPatchId;
                arc.leftPatchId = arc.rightPatchId;
                arc.rightPatchId = left;
            }
            fixture.tmesh.resolveWalkOrientation();
        }
        ZeroPatchSplitOperator operator = new ZeroPatchSplitOperator(fixture.tmesh);
        List<Integer> sizes = new ArrayList<>();
        for (EmbeddedPatch patch : fixture.tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            IntIdList faces = operator.patchFaces(patch.patchId);
            assertTrue(faces.size() < fixture.plane.faceCount(),
                    "patch " + patch.patchId + " flooded " + faces.size() + " of "
                            + fixture.plane.faceCount() + " faces: a whole-surface flood means the"
                            + " seed was outside the patch");
            sizes.add(faces.size());
        }
        return sizes;
    }
}
