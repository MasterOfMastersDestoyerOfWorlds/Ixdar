package ixdar.geometry.mesh.nodes.modifier;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;

import java.util.Map;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Value;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.MeshVertexOffset;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "set_position")
public class SetPositionNode implements MeshNode {
    public static final String GEOMETRY_2 = "geometry";
    public static final String OFFSET_2 = "offset";

    private static final InputPort GEOMETRY = new InputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort OFFSET = new InputPort(OFFSET_2, PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    private static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE);

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
                GEOMETRY_2, "Input/output. Every vertex position is shifted by `offset`.",
                OFFSET_2, "World-space translation added to every vertex. <0,0,0> = identity. For selective displacement, use a Vector3field."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput(GEOMETRY_2, Object.class));
        Object off = FieldBroadcast.getInputOrDefault(ctx, OFFSET_2, OFFSET.defaultValue());
        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput(GEOMETRY_2, base);
            return;
        }
        var outMesh = MeshVertexOffset.apply(mesh, off);
        ctx.setOutput(GEOMETRY_2, base.withMesh(outMesh));
    }
}
