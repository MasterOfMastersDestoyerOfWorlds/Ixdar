package ixdar.geometry.mesh.nodes.modifier;

import java.util.List;

import ixdar.geometry.mesh.nodes.api.InputPort;

import java.util.Map;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.api.Vector3Value;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.MeshVertexOffset;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "set_position")
public class SetPositionNode implements MeshNode {
    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort OFFSET = new InputPort("offset", PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, OFFSET);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Translates all vertices of a geometry by a vector offset, useful for repositioning meshes in world space.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output. Every vertex position is shifted by `offset`.",
                OFFSET.name, "World-space translation added to every vertex. <0,0,0> = identity. For selective displacement, use a Vector3field."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput(GEOMETRY.name, Object.class));
        Object off = FieldBroadcast.getInputOrDefault(ctx, OFFSET.name, OFFSET.defaultValue);
        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput(GEOMETRY.name, base);
            return;
        }
        var outMesh = MeshVertexOffset.apply(mesh, off);
        ctx.setOutput(GEOMETRY.name, base.withMesh(outMesh));
    }
}
