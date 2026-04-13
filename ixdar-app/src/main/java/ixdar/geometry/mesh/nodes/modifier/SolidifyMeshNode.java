package ixdar.geometry.mesh.nodes.modifier;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.ArrayMeshEngine;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "solidify_mesh")
public class SolidifyMeshNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort THICKNESS = new InputPort("thickness", PortType.FLOAT, 0.01f, 0.001f, 10f);
    private static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);
    private static final OutputPort MESH_OUT = new OutputPort("mesh", PortType.MESH);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, THICKNESS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(MESH_OUT, GEOMETRY_OUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        MeshTopology in = base.mesh();
        if (in == null || in.vertexCount() == 0) {
            ctx.setOutput("mesh", null);
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }
        Object to = FieldBroadcast.getInputOrDefault(ctx, "thickness", THICKNESS.defaultValue());
        float t = FieldBroadcast.floatScalarOrDefault(to, 0.01f);
        ArrayMesh am = in instanceof ArrayMesh m ? m : ArrayMeshEngine.fromUniformMeshTopology(in);
        if (!ArrayMeshEngine.isUniformQuads(am)) {
            throw new IllegalStateException("solidify_mesh requires uniform quad meshes");
        }
        ArrayMesh out = ArrayMeshEngine.solidifyUniformQuads(am, t);
        ctx.setOutput("mesh", out);
        ctx.setOutput("geometry", base.withMesh(out));
    }
}
