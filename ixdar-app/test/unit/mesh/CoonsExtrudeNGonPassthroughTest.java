package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.BoolField;
import ixdar.annotations.meshnode.MapNodeContext;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.patch.AssignBezierHandlesNode;
import ixdar.geometry.mesh.nodes.patch.CoonsExtrudeMeshNode;

/**
 * Regression for MESH-47 Phase B extrude pass-through: non-quad faces that
 * flow through {@code coons_extrude_mesh} as unselected must keep their
 * original vpf rather than being padded into degenerate all-zero quads. This
 * lets upstream n-gon fills (from MESH-47 inset rewrite, or manually
 * constructed triangle/pentagon faces) survive through the extrude and out
 * to {@code coons_patch} for Gregory-based subdivision.
 */
public class CoonsExtrudeNGonPassthroughTest {

    private static GeometryBundle buildBundle(HalfEdgeMesh mesh) {
        int maxEid = 0;
        for (int i = 0; i < mesh.edgeCount(); i++) {
            maxEid = Math.max(maxEid, mesh.edgeIdAt(i));
        }
        float[] handles = new float[(maxEid + 1) * 3];
        Map<String, Object> slots = new HashMap<>();
        slots.put(AssignBezierHandlesNode.SLOT_HANDLES_START, handles);
        slots.put(AssignBezierHandlesNode.SLOT_HANDLES_END, handles);
        slots.put(AssignBezierHandlesNode.SLOT_WEIGHT, 0.33f);
        return new GeometryBundle(mesh, Map.copyOf(slots));
    }

    @Test
    public void triangleFlowsThroughExtrudeUnchanged() {
        // Input: a small mesh with one triangle and one quad side-by-side
        // sharing an edge. Select NOTHING. The extrude pass-through must
        // preserve both face vertex counts in the output.
        float[] positions = new float[]{
                0f, 0f, 0f,   // 0
                1f, 0f, 0f,   // 1
                1f, 1f, 0f,   // 2  (shared with triangle)
                0f, 1f, 0f,   // 3
                2f, 0.5f, 0f, // 4  (triangle apex)
        };
        int[] faceVpf = {4, 3};
        int[] faceIdx = {0, 1, 2, 3,   // quad
                         1, 4, 2};     // triangle sharing edge 1-2
        HalfEdgeMesh in = HalfEdgeMeshEngine.bulkAllocateMixed(positions, faceVpf, faceIdx);
        in.computeNormals();

        GeometryBundle bundle = buildBundle(in);
        MapNodeContext ctx = new MapNodeContext(new CoonsExtrudeMeshNode());
        ctx.setInput("geometry", bundle);
        ctx.setInput("offset", 0.5f);
        // Select nothing; everything should pass through.
        ctx.setInput("selection", new BoolField(new boolean[]{false, false}));
        ctx.setInput("region", false);
        new CoonsExtrudeMeshNode().evaluate(ctx);

        GeometryBundle out = ctx.getOutput("geometry", GeometryBundle.class);
        assertNotNull(out);
        MeshTopology outMesh = out.mesh();
        assertNotNull(outMesh);
        // No extrusion happened; vertex count must be preserved and both face
        // vpfs must be the original (4 and 3).
        assertEquals(5, outMesh.vertexCount(), "vert count must be preserved");
        assertEquals(2, outMesh.faceCount(), "face count must be preserved");
        int fid0 = outMesh.faceIdAt(0);
        int fid1 = outMesh.faceIdAt(1);
        int vpf0 = outMesh.faceVertexCount(fid0);
        int vpf1 = outMesh.faceVertexCount(fid1);
        // Either order is fine (depends on iteration), just verify {3, 4}.
        assertTrue((vpf0 == 4 && vpf1 == 3) || (vpf0 == 3 && vpf1 == 4),
                "expected one quad and one triangle, got vpfs " + vpf0 + " and " + vpf1);
    }
}
