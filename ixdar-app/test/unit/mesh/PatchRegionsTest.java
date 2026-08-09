package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.gridmap.PatchRegions;

/**
 * The patches must partition the working copy: every face assigned, one region per live
 * patch, each region sealed by exactly one patch's arcs. On the Figure-9 torus fixture that
 * is ten patches enclosing all of the torus's faces with none left over.
 */
class PatchRegionsTest {

    @Test
    void everyPatchEnclosesOneRegionAndEveryFaceIsAssigned() {
        TorusLayoutFixture fixture = new TorusLayoutFixture();
        PatchRegions regions = new PatchRegions(fixture.tmesh).build();

        assertEquals(fixture.tmesh.patches.size(), regions.copyFacesByPatch.size(),
                "one region per live patch");
        assertEquals(fixture.torus.faceCount(), regions.patchIdByCopyFace.size(),
                "every copy face is assigned to exactly one patch");

        int totalFaces = 0;
        for (var faces : regions.copyFacesByPatch.values()) {
            assertTrue(faces.size() > 0, "no patch region is empty");
            totalFaces += faces.size();
        }
        assertEquals(fixture.torus.faceCount(), totalFaces,
                "the regions cover the surface without overlap");
    }

    /**
     * The partition proof must actually fire when the layout is torn. Releasing an arc's
     * claims removes a stretch of the barrier between two patches, so their regions merge
     * and the region count no longer matches the patch count.
     */
    @Test
    void buildRejectsATornLayout() {
        TorusLayoutFixture fixture = new TorusLayoutFixture();
        int arcId = fixture.tmesh.arcs.get(0).arcId;
        for (int edgeId : fixture.tmesh.arcs.get(arcId).path.copyEdgePath) {
            fixture.tmesh.topology.ownerArcByCopyEdge[edgeId] =
                    ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology.UNCLAIMED;
        }

        assertThrows(IllegalStateException.class, () -> new PatchRegions(fixture.tmesh).build(),
                "a gap in a patch boundary must be caught");
    }
}
