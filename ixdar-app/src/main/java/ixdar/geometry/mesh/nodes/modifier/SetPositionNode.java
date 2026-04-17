package ixdar.geometry.mesh.nodes.modifier;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
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

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort OFFSET = new InputPort("offset", PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

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
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        Object off = FieldBroadcast.getInputOrDefault(ctx, "offset", OFFSET.defaultValue());
        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput("geometry", base);
            return;
        }
        var outMesh = MeshVertexOffset.apply(mesh, off);
        ctx.setOutput("geometry", base.withMesh(outMesh));
    }
}
