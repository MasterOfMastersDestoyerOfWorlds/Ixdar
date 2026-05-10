package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;

import java.util.Map;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Field;
import ixdar.annotations.meshnode.Vector3Value;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.MeshFieldContext;

import org.joml.Vector3f;

@MeshNodeAnnotation(id = "input_tangent")
public class InputTangentNode implements MeshNode {
    public static final String VECTOR_2 = "vector";
    public static final float NUM_1 = 1f;
    public static final float NUM_0 = 0f;
    public static final int NUM_3 = 3;
    public static final float NUM_1e_20 = 1e-20f;

    private static final OutputPort VECTOR = new OutputPort(VECTOR_2, PortType.VECTOR3);

    @Override
    public String description() {
        return "Outputs a per-vertex tangent vector derived from the first outgoing half-edge of each vertex.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                VECTOR_2, "Per-vertex Vector3field of unit tangents (direction along the first outgoing half-edge)."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of();
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(VECTOR);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        var fc = ctx.fieldContext();
        if (fc == null || !(fc instanceof MeshFieldContext mfc)) {
            ctx.setOutput(VECTOR_2,new Vector3Value(NUM_1, NUM_0, NUM_0));
            return;
        }
        MeshTopology mesh = mfc.mesh();
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput(VECTOR_2,new Vector3Value(NUM_1, NUM_0, NUM_0));
            return;
        }
        int n = mesh.vertexCount();
        float[] d = new float[n * NUM_3];
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f tan = new Vector3f();
        for (int vi = 0; vi < n; vi++) {
            int vid = mesh.vertexIdAt(vi);
            mesh.vertexPosition(vid, p0);
            int he = mesh.vertexOutgoingHalfEdgeAt(vid, 0);
            int twin = mesh.halfEdgeTwin(he);
            int ov = mesh.halfEdgeEndVertex(twin);
            mesh.vertexPosition(ov, p1);
            tan.set(p1).sub(p0);
            if (tan.lengthSquared() < NUM_1e_20) {
                tan.set(NUM_1, NUM_0, NUM_0);
            } else {
                tan.normalize();
            }
            d[NUM_3 * vi] = tan.x;
            d[NUM_3 * vi + 1] = tan.y;
            d[NUM_3 * vi + 2] = tan.z;
        }
        ctx.setOutput(VECTOR_2,new Vector3Field(d));
    }
}
