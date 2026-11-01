package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.nodes.data.SceneModelNode;
import ixdar.nodegraph.nodes.primitives.IcosphereMeshNode;
import ixdar.nodegraph.nodes.quadlayout.QuadLayoutNode;
import ixdar.nodegraph.runtime.GraphNodeContext;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * PATCH-127: quad_layout refuses a pleated surface, sliver faces folded back over it, at its entry
 * and before any stage runs, instead of running on to a folded map or out of heap.
 */
class FoldedInputRefusalTest {

    private static final int SUBDIVISIONS = 3;

    private static final float PLEAT_OVERSHOOT = 0.3f;

    /**
     * The fixture is an icosphere whose first face is split at a point pushed across its first
     * edge, so the split's triangle on that edge, still face 0, winds against the surface.
     */
    @Test
    void quadLayoutRefusesAPleatBeforeAnyStage() {
        HalfEdgeMesh sphere = icosphere();
        int vertexCount = sphere.vertexCount();
        float[] positions = Arrays.copyOf(sphere.vertexPositions, (vertexCount + 1) * 3);
        int[] corners = new int[(sphere.faceCount() + 2) * 3];
        for (int activeFace = 0; activeFace < sphere.faceCount(); activeFace++) {
            for (int corner = 0; corner < 3; corner++) {
                corners[activeFace * 3 + corner] =
                        sphere.faceVertexAt(sphere.faceIdAt(activeFace), corner);
            }
        }
        int first = corners[0];
        int second = corners[1];
        int apex = corners[2];
        Vector3f edgeMidpoint = sphere.vertexPosition(first).add(sphere.vertexPosition(second))
                .mul(0.5f);
        Vector3f pleat = new Vector3f(edgeMidpoint).sub(sphere.vertexPosition(apex))
                .mul(PLEAT_OVERSHOOT).add(edgeMidpoint);
        positions[vertexCount * 3] = pleat.x;
        positions[vertexCount * 3 + 1] = pleat.y;
        positions[vertexCount * 3 + 2] = pleat.z;
        int[] split = { first, second, vertexCount, second, apex, vertexCount, apex, first,
            vertexCount };
        System.arraycopy(split, 0, corners, 0, 3);
        System.arraycopy(split, 3, corners, sphere.faceCount() * 3, 2 * 3);
        HalfEdgeMesh pleated = HalfEdgeMesh.buildFromIndexedMesh(positions, corners);
        int pleatFaceId = pleated.faceIdAt(0);

        NodeGraphRuntime layout = layoutOver(pleated);
        Exception refusal = assertThrows(Exception.class,
                () -> layout.pull(QuadLayoutNode.sceneOutput(QuadLayoutNode.FIELD)),
                "a pleated surface is refused");
        Throwable cause = refusal;
        while (!(cause instanceof IllegalArgumentException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        assertTrue(message.startsWith(QuadLayoutNode.TYPE + ": 1 faces fold back"),
                "quad_layout counts the one folded face: " + message);
        assertTrue(message.contains("[" + pleatFaceId + "]"),
                "the refusal names the folded face: " + message);
        GraphNodeContext field = layout.statementAt(QuadLayoutNode.LAYOUT_CALL
                + GraphNodeContext.PATH_SEPARATOR + QuadLayoutNode.FIELD.name);
        assertFalse(field != null && field.hasOutput(QuadLayoutNode.FIELD.name),
                "no stage ran on the refused surface");
    }

    @Test
    void quadLayoutAcceptsTheFoldFreeSphere() throws Exception {
        assertNotNull(layoutOver(icosphere()).pull(QuadLayoutNode.sceneOutput(QuadLayoutNode.FIELD)),
                "the fold-free sphere gets its cross field");
    }

    /**
     * The scene graph of quad_layout over a mesh.
     *
     * @param mesh triangle mesh
     * @return the runtime, nothing pulled yet
     */
    private static NodeGraphRuntime layoutOver(HalfEdgeMesh mesh) {
        NodeGraphRuntime layout = new NodeGraphRuntime(QuadLayoutNode.overSceneModel());
        layout.graphInputs.put(SceneModelNode.BINDING, GeometryBundle.ofMesh(mesh));
        return layout;
    }

    /**
     * The fold-free base of the fixture.
     *
     * @return an icosphere of {@link #SUBDIVISIONS} levels
     */
    private static HalfEdgeMesh icosphere() {
        IcosphereMeshNode icosphere = new IcosphereMeshNode();
        MapNodeContext ctx = new MapNodeContext(icosphere);
        ctx.setInput(IcosphereMeshNode.SUBDIVISIONS.name, SUBDIVISIONS);
        icosphere.evaluate(ctx);
        return (HalfEdgeMesh) ctx.getOutput(IcosphereMeshNode.MESH.name, GeometryBundle.class)
                .mesh();
    }
}
