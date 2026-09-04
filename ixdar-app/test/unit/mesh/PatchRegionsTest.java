package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.gridmap.PatchRegions;

/**
 * The patches must partition the working copy: every face assigned, one region per live
 * patch, each region sealed by exactly one patch's arcs. On the Figure-9 torus fixture that
 * is ten patches enclosing all of the torus's faces with none left over.
 */
class PatchRegionsTest {

    @Test
    void everyPatchEnclosesOneRegionAndEveryFaceIsAssigned() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/torus_layout.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        PatchRegions regions = new PatchRegions(fixtureNet).build();

        assertEquals(fixtureNet.patches.size(), regions.copyFacesByPatch.size(),
                "one region per live patch");
        assertEquals(fixtureNet.topology.sourceMesh.faceCount(), regions.patchIdByCopyFace.size(),
                "every copy face is assigned to exactly one patch");

        int totalFaces = 0;
        for (var faces : regions.copyFacesByPatch.values()) {
            assertTrue(faces.size() > 0, "no patch region is empty");
            totalFaces += faces.size();
        }
        assertEquals(fixtureNet.topology.sourceMesh.faceCount(), totalFaces,
                "the regions cover the surface without overlap");
    }

    /**
     * The partition proof must actually fire when the layout is torn. Releasing an arc's
     * claims removes a stretch of the barrier between two patches, so their regions merge
     * and the region count no longer matches the patch count.
     */
    @Test
    void buildRejectsATornLayout() {
        NodeGraphRuntime fixture = NodeGraphRuntime.executeResource("dsl/fixtures/torus_layout.dsl", Map.of());
        ArcNetwork fixtureNet = (ArcNetwork) fixture.lastOutput("net");
        int arcId = fixtureNet.arcs.get(0).arcId;
        for (int edgeId : fixtureNet.arcs.get(arcId).path.copyEdgePath) {
            fixtureNet.topology.ownerArcByCopyEdge[edgeId] =
                    ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology.UNCLAIMED;
        }

        assertThrows(IllegalStateException.class, () -> new PatchRegions(fixtureNet).build(),
                "a gap in a patch boundary must be caught");
    }
}
