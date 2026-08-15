package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.fixtures.TorusLayoutFixture;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.gridmap.PatchRegions;

/**
 * Re-carving a contracted T-mesh onto a clean copy of the original surface must
 * preserve live layout combinatorics while reducing working-copy density.
 */
class EmbeddedTMeshRecarveTest {

    @Test
    void recarvePreservesLiveLayoutAndReducesMeshDensity() {
        TorusLayoutFixture fixture = new TorusLayoutFixture();
        fixture.tmesh.contract();

        int liveNodesBefore = countLiveNodes(fixture.tmesh);
        int liveArcsBefore = countLiveArcs(fixture.tmesh);
        int livePatchesBefore = countLivePatches(fixture.tmesh);
        int verticesBefore = fixture.topology.copy.vertexCount();
        int facesBefore = fixture.topology.copy.faceCount();

        EmbeddedTMesh rebuilt = fixture.tmesh.recarve(fixture.torus);
        rebuilt.validate();
        new PatchRegions(rebuilt).build();

        assertEquals(liveNodesBefore, countLiveNodes(rebuilt),
                "re-carve should keep every live node");
        assertEquals(liveArcsBefore, countLiveArcs(rebuilt),
                "re-carve should keep every live arc");
        assertEquals(livePatchesBefore, countLivePatches(rebuilt),
                "re-carve should keep every live patch");

        for (EmbeddedArc arc : rebuilt.arcs) {
            if (arc.alive) {
                assertNotEquals(0, arc.quantizedLength,
                        "re-carved arc " + arc.arcId + " should stay non-zero");
            }
        }
        for (EmbeddedPatch patch : rebuilt.patches) {
            if (patch.alive) {
                assertTrue(!rebuilt.isZeroPatch(patch.patchId),
                        "re-carved patch " + patch.patchId + " should stay non-zero");
            }
        }

        assertTrue(rebuilt.topology.copy.vertexCount() <= verticesBefore,
                "re-carve should not increase vertex count");
        assertTrue(rebuilt.topology.copy.faceCount() <= facesBefore,
                "re-carve should not increase face count");
    }

    /**
     * The number of live nodes in a T-mesh.
     *
     * @param tmesh T-mesh to scan
     * @return count of live nodes
     */
    private static int countLiveNodes(EmbeddedTMesh tmesh) {
        int live = 0;
        for (int nodeId = 0; nodeId < tmesh.nodes.size(); nodeId++) {
            if (tmesh.nodes.get(nodeId).alive) {
                live++;
            }
        }
        return live;
    }

    /**
     * The number of live arcs in a T-mesh.
     *
     * @param tmesh T-mesh to scan
     * @return count of live arcs
     */
    private static int countLiveArcs(EmbeddedTMesh tmesh) {
        int live = 0;
        for (int arcId = 0; arcId < tmesh.arcs.size(); arcId++) {
            if (tmesh.arcs.get(arcId).alive) {
                live++;
            }
        }
        return live;
    }

    /**
     * The number of live patches in a T-mesh.
     *
     * @param tmesh T-mesh to scan
     * @return count of live patches
     */
    private static int countLivePatches(EmbeddedTMesh tmesh) {
        int live = 0;
        for (int patchId = 0; patchId < tmesh.patches.size(); patchId++) {
            if (tmesh.patches.get(patchId).alive) {
                live++;
            }
        }
        return live;
    }
}
