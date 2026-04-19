package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.MapNodeContext;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.patch.AssignBezierHandlesNode;
import ixdar.geometry.mesh.nodes.patch.CoonsPatchNode;

/**
 * Smoke test for CoonsPatchNode's n-sided dispatch (MESH-47 Phase B). Quads
 * continue through the bilinearly-blended Coons path; 3-sided and 5+-sided
 * faces route to the Charrot-Gregory evaluator and emit triangle fans from
 * the patch centroid.
 */
public class CoonsPatchNGonTest {

    @Test
    public void triangleFaceSubdivides() {
        // Flat 2D triangle in z=0 plane, 3 vertices on the unit circle at
        // canonical angles. One face, all straight-line edges (zero bezier
        // handles). coons_patch with subdivisions=3 should emit 3·3 = 9 tris
        // and 1 + 3·3 = 10 verts.
        float[] positions = new float[9];
        float twoPi = (float) (2.0 * Math.PI);
        float pi = (float) Math.PI;
        for (int i = 0; i < 3; i++) {
            float theta = (i + 0.5f) * twoPi / 3f + pi;
            positions[i * 3] = (float) Math.cos(theta);
            positions[i * 3 + 1] = (float) Math.sin(theta);
            positions[i * 3 + 2] = 0f;
        }
        int[] faceIndices = {0, 1, 2};
        HalfEdgeMesh in = HalfEdgeMeshEngine.bulkAllocateMixed(
                positions, new int[]{3}, faceIndices);
        in.computeNormals();

        // Zero bezier handles → straight edges. Slot arrays keyed by edge id.
        int maxEid = 0;
        for (int i = 0; i < in.edgeCount(); i++) {
            maxEid = Math.max(maxEid, in.edgeIdAt(i));
        }
        float[] handles = new float[(maxEid + 1) * 3];

        Map<String, Object> slots = new HashMap<>();
        slots.put(AssignBezierHandlesNode.SLOT_HANDLES_START, handles);
        slots.put(AssignBezierHandlesNode.SLOT_HANDLES_END, handles);
        GeometryBundle bundle = new GeometryBundle(in, Map.copyOf(slots));

        MapNodeContext ctx = new MapNodeContext(new CoonsPatchNode());
        ctx.setInput("geometry", bundle);
        ctx.setInput("subdivisions", 3);
        new CoonsPatchNode().evaluate(ctx);

        GeometryBundle outBundle = ctx.getOutput("geometry", GeometryBundle.class);
        assertNotNull(outBundle, "coons_patch must produce a geometry bundle");
        MeshTopology out = outBundle.mesh();
        assertNotNull(out);

        // Expected: 1 centroid + 3·3 boundary samples = 10 verts.
        assertEquals(10, out.vertexCount(),
                "triangle subdivision expected 10 verts, got " + out.vertexCount());
        // Expected: 3·3 = 9 triangular sub-faces.
        assertEquals(9, out.faceCount());
        // All output faces are 3-sided (fan triangles).
        for (int fi = 0; fi < out.faceCount(); fi++) {
            int fid = out.faceIdAt(fi);
            assertEquals(3, out.faceVertexCount(fid),
                    "n-sided output face " + fi + " should be a triangle");
        }
        // All output vertex z values should be ~0 (flat triangle stays planar).
        Vector3f tmp = new Vector3f();
        for (int vi = 0; vi < out.vertexCount(); vi++) {
            out.vertexPosition(out.vertexIdAt(vi), tmp);
            assertTrue(Math.abs(tmp.z) < 1e-5f,
                    "flat triangle should stay planar; got z=" + tmp.z + " at vert " + vi);
        }
    }

    @Test
    public void quadFaceStillProducesQuads() {
        // Ensure the quad path isn't accidentally routed to Gregory. Build a
        // single quad face, run coons_patch, verify output is all quads.
        float[] positions = new float[]{
                -1f, 0f, -1f,
                 1f, 0f, -1f,
                 1f, 0f,  1f,
                -1f, 0f,  1f,
        };
        int[] faceIndices = {0, 1, 2, 3};
        HalfEdgeMesh in = HalfEdgeMeshEngine.bulkAllocateMixed(
                positions, new int[]{4}, faceIndices);
        in.computeNormals();

        int maxEid = 0;
        for (int i = 0; i < in.edgeCount(); i++) {
            maxEid = Math.max(maxEid, in.edgeIdAt(i));
        }
        float[] handles = new float[(maxEid + 1) * 3];

        Map<String, Object> slots = new HashMap<>();
        slots.put(AssignBezierHandlesNode.SLOT_HANDLES_START, handles);
        slots.put(AssignBezierHandlesNode.SLOT_HANDLES_END, handles);
        GeometryBundle bundle = new GeometryBundle(in, Map.copyOf(slots));

        MapNodeContext ctx = new MapNodeContext(new CoonsPatchNode());
        ctx.setInput("geometry", bundle);
        ctx.setInput("subdivisions", 3);
        new CoonsPatchNode().evaluate(ctx);

        GeometryBundle outBundle = ctx.getOutput("geometry", GeometryBundle.class);
        MeshTopology out = outBundle.mesh();
        for (int fi = 0; fi < out.faceCount(); fi++) {
            int fid = out.faceIdAt(fi);
            assertEquals(4, out.faceVertexCount(fid),
                    "quad face must subdivide into quads, got " + out.faceVertexCount(fid)
                            + " verts on sub-face " + fi);
        }
    }

    @Test
    public void pentagonFaceSubdivides() {
        // 5-sided face → 5·3 = 15 tris, 1 + 5·3 = 16 verts.
        int n = 5;
        float[] positions = new float[n * 3];
        float twoPi = (float) (2.0 * Math.PI);
        float pi = (float) Math.PI;
        for (int i = 0; i < n; i++) {
            float theta = (i + 0.5f) * twoPi / n + pi;
            positions[i * 3] = (float) Math.cos(theta);
            positions[i * 3 + 1] = (float) Math.sin(theta);
            positions[i * 3 + 2] = 0f;
        }
        int[] faceIndices = new int[n];
        for (int i = 0; i < n; i++) faceIndices[i] = i;
        HalfEdgeMesh in = HalfEdgeMeshEngine.bulkAllocateMixed(
                positions, new int[]{n}, faceIndices);
        in.computeNormals();

        int maxEid = 0;
        for (int i = 0; i < in.edgeCount(); i++) {
            maxEid = Math.max(maxEid, in.edgeIdAt(i));
        }
        float[] handles = new float[(maxEid + 1) * 3];

        Map<String, Object> slots = new HashMap<>();
        slots.put(AssignBezierHandlesNode.SLOT_HANDLES_START, handles);
        slots.put(AssignBezierHandlesNode.SLOT_HANDLES_END, handles);
        GeometryBundle bundle = new GeometryBundle(in, Map.copyOf(slots));

        MapNodeContext ctx = new MapNodeContext(new CoonsPatchNode());
        ctx.setInput("geometry", bundle);
        ctx.setInput("subdivisions", 3);
        new CoonsPatchNode().evaluate(ctx);

        GeometryBundle outBundle = ctx.getOutput("geometry", GeometryBundle.class);
        MeshTopology out = outBundle.mesh();
        assertEquals(16, out.vertexCount());
        assertEquals(15, out.faceCount());
    }
}
