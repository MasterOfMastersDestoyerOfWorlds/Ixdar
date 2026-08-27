package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import ixdar.geometry.mesh.nodes.api.InputPort;

import java.util.Map;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.api.Vector3Field;
import ixdar.geometry.mesh.nodes.api.Vector3Value;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.MeshFieldContext;

import org.joml.Vector3f;

@MeshNodeAnnotation(id = "input_tangent")
public class InputTangentNode implements MeshNode {
    public static final float NUM_1 = 1f;
    public static final float NUM_0 = 0f;
    public static final int NUM_3 = 3;
    public static final float NUM_1e_20 = 1e-20f;

    public static final OutputPort VECTOR = new OutputPort("vector", PortType.VECTOR3);

    @Override
    public String description() {
        return "Outputs a per-vertex tangent vector derived from the first outgoing half-edge of each vertex.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                VECTOR.name, "Per-vertex Vector3field of unit tangents (direction along the first outgoing half-edge)."
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
            ctx.setOutput(VECTOR.name,new Vector3Value(NUM_1, NUM_0, NUM_0));
            return;
        }
        MeshTopology mesh = mfc.mesh();
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput(VECTOR.name,new Vector3Value(NUM_1, NUM_0, NUM_0));
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
        ctx.setOutput(VECTOR.name,new Vector3Field(d));
    }
}
