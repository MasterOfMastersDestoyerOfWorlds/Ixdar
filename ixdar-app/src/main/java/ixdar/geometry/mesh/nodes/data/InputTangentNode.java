package ixdar.geometry.mesh.nodes.data;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vec3Field;
import ixdar.annotations.meshnode.Vector3Value;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.MeshFieldContext;

import org.joml.Vector3f;

@MeshNodeAnnotation(id = "input_tangent")
public class InputTangentNode implements MeshNode {

    private static final OutputPort VECTOR = new OutputPort("vector", PortType.VECTOR3);

    @Override
    public String description() {
        return "Outputs a per-vertex tangent vector derived from the first outgoing half-edge of each vertex.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "vector", "Per-vertex Vec3Field of unit tangents (direction along the first outgoing half-edge)."
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
            ctx.setOutput("vector",new Vector3Value(1f, 0f, 0f));
            return;
        }
        MeshTopology mesh = mfc.mesh();
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput("vector",new Vector3Value(1f, 0f, 0f));
            return;
        }
        int n = mesh.vertexCount();
        float[] d = new float[n * 3];
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
            if (tan.lengthSquared() < 1e-20f) {
                tan.set(1f, 0f, 0f);
            } else {
                tan.normalize();
            }
            d[3 * vi] = tan.x;
            d[3 * vi + 1] = tan.y;
            d[3 * vi + 2] = tan.z;
        }
        ctx.setOutput("vector",new Vec3Field(d));
    }
}
