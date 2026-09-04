package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.IntIdList;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroPatchSplitOperator;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;

/**
 * A patch's face flood must land inside the patch, and a patch wound the other way is refused
 * by name rather than seeded outside.
 *
 * <p>The flood is walled by the patch's own boundary arcs and seeded from the side of one of them
 * that {@code leftPatchId} names. Boundary walks run counter-clockwise seen from outside with the
 * interior on the left, so the left flank is the interior by contract, and
 * {@code validateWalkOrientation} checks each fresh patch against the surface once. A seed on
 * the wrong side is not a small error: on fertility it had five of six operator-(2) splits
 * flooding 99.997% of the mesh and handing the router the whole surface as a corridor.
 */
class PatchInteriorSeedTest {

    /** Faces the fixture's three patches cover between them, out of the wider grid. */
    private static final int LAYOUT_FACES = 64;

    @Test
    void eachPatchFloodsItsOwnInterior() {
        List<Integer> asBuilt = floodSizes(NodeGraphRuntime.executeResource("dsl/fixtures/plane_layout.dsl", Map.of()));

        assertTrue(asBuilt.size() > 1, "the fixture must carry several patches");
        assertEquals(LAYOUT_FACES, asBuilt.stream().mapToInt(Integer::intValue).sum(),
                "the fixture's patches tile a known region, so their floods must cover it exactly");
    }

    @Test
    void aPatchWoundBackwardsIsNamedByTheValidator() {
        ArcNetwork net = (ArcNetwork) NodeGraphRuntime.executeResource("dsl/fixtures/plane_layout.dsl", Map.of()).lastOutput("net");
        net.validateWalkOrientation();
        EmbeddedPatch authored = net.patches.get(0);
        List<List<Integer>> backwards = new ArrayList<>(EmbeddedPatch.SIDES);
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            List<Integer> sideArcs = new ArrayList<>(
                    authored.sideArcIds.get((EmbeddedPatch.SIDES - side) % EmbeddedPatch.SIDES));
            Collections.reverse(sideArcs);
            backwards.add(sideArcs);
        }
        int backwardsPatch = net.addPatch(ArcNetwork.NONE, backwards, authored.cornerNodeId(1));

        IllegalStateException wound = assertThrows(IllegalStateException.class,
                net::validateWalkOrientation,
                "the same boundary walked the other way puts the interior on the right");
        assertEquals("patch " + backwardsPatch + " is wound backwards: boundary walks must run"
                + " counter-clockwise seen from outside, interior on the left", wound.getMessage(),
                "the validator names the backwards patch");
    }

    /**
     * The face count of every live patch's interior flood.
     *
     * @param fixture layout to measure
     * @return the flood sizes in patch order
     */
    private List<Integer> floodSizes(NodeGraphRuntime fixture) {
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        ZeroPatchSplitOperator operator = new ZeroPatchSplitOperator(fixtureNet);
        List<Integer> sizes = new ArrayList<>();
        for (EmbeddedPatch patch : fixtureNet.patches) {
            if (!patch.alive) {
                continue;
            }
            IntIdList faces = operator.patchFaces(patch.patchId);
            assertTrue(faces.size() < fixtureNet.topology.sourceMesh.faceCount(),
                    "patch " + patch.patchId + " flooded " + faces.size() + " of "
                            + fixtureNet.topology.sourceMesh.faceCount() + " faces: a whole-surface flood means the"
                            + " seed was outside the patch");
            sizes.add(faces.size());
        }
        return sizes;
    }
}
