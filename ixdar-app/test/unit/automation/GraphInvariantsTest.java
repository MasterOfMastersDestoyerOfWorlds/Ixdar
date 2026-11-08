package unit.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import ixdar.automation.endpoints.mesh.Fingerprint;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * Invariants of graphs that once had golden fingerprints: what any correct change keeps, in place of
 * the bit-exact hash a correct change breaks.
 */
class GraphInvariantsTest {
    private static final String GEOMETRY = "geometry";
    private static final String FACES = "faceCount";
    private static final String ALL_QUADS = "every face a quad";
    private static final int QUADRISECTIONS = 2;

    @Test
    void subdividingAQuadCylinderKeepsItsTopologyAndQuadruplesItsFaces() {
        // Catmull-Clark: each level splits a quad into four and a boundary edge into two, and keeps
        // the Euler characteristic.
        NodeGraphRuntime graph = NodeGraphRuntime.executeResource("dsl/quad_cylinder_test.dsl", Map.of());
        MeshTopology cylinder = ((GeometryBundle) graph.getNodeOutput("cyl", GEOMETRY)).mesh();
        MeshTopology smooth = ((GeometryBundle) graph.lastOutput(GEOMETRY)).mesh();
        int growth = 1 << QUADRISECTIONS;
        assertEquals(cylinder.faceCount() * growth * growth, smooth.faceCount(), "faces quadruple per level");
        assertEquals(2 * smooth.faceCount(), smooth.triangleCount(), ALL_QUADS);
        assertEquals(boundaryEdges(cylinder) * growth, boundaryEdges(smooth), "boundary edges double per level");
        assertEquals(euler(cylinder), euler(smooth), "the Euler characteristic is kept");
    }

    @Test
    @Tag("heavy")
    void theSpheresQuadLayoutIsAClosedQuadMeshOverASphereShapedTMesh() {
        // sphere_base is genus zero: the quad mesh and the T-mesh's patch network both have Euler
        // characteristic 2, the quad mesh is closed and every face is a quad.
        JsonObject outputs = Fingerprint.graphFingerprint(NodeGraphRuntime.executeResource(
                "test/resources/dsl/quad_layout_sphere.dsl", Map.of())).getAsJsonObject("outputs");
        JsonObject quads = outputs.getAsJsonObject(GEOMETRY);
        JsonObject tmesh = outputs.getAsJsonObject("tmesh");
        assertEquals(0, quads.get("boundaryEdgeCount").getAsInt(), "the quad mesh is closed");
        assertEquals(2 * quads.get(FACES).getAsInt(), quads.get("triangleCount").getAsInt(), ALL_QUADS);
        assertEquals(2, quads.get("vertexCount").getAsInt() - quads.get("edgeCount").getAsInt()
                + quads.get(FACES).getAsInt(), "the quad mesh is a sphere");
        assertEquals(2, tmesh.get("nodeCount").getAsInt() - tmesh.get("arcCount").getAsInt()
                + tmesh.get("patchCount").getAsInt(), "the T-mesh's patches tile a sphere");
    }

    /**
     * A mesh's Euler characteristic.
     *
     * @param mesh the mesh
     * @return V - E + F
     */
    private static int euler(MeshTopology mesh) {
        return mesh.vertexCount() - mesh.edgeCount() + mesh.faceCount();
    }

    /**
     * Edges with a face on one side only.
     *
     * @param mesh the mesh
     * @return how many
     */
    private static int boundaryEdges(MeshTopology mesh) {
        int boundary = 0;
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            int halfEdge = mesh.edgeHalfEdge(mesh.edgeIdAt(activeEdge));
            boundary += mesh.halfEdgeFace(halfEdge) < 0 || mesh.halfEdgeFace(mesh.halfEdgeTwin(halfEdge)) < 0 ? 1 : 0;
        }
        return boundary;
    }
}
