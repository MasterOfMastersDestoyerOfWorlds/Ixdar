package ixdar.geometry.mesh.nodes.curve;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundles;

@MeshNodeAnnotation(id = "mesh_to_curve")
public class MeshToCurveNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final OutputPort CURVE = new OutputPort("curve", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(CURVE);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        ctx.setOutput("curve", GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class)));
    }
}
