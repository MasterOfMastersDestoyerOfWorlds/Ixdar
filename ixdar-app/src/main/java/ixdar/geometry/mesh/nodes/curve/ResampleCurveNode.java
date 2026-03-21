package ixdar.geometry.mesh.nodes.curve;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.GeometryBundles;

@MeshNodeAnnotation(id = "resample_curve")
public class ResampleCurveNode implements MeshNode {

    private static final InputPort CURVE = new InputPort("curve", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort LENGTH = new InputPort("length", PortType.FLOAT, 0.1f);
    private static final OutputPort CURVE_OUT = new OutputPort("curve", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(CURVE, LENGTH);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(CURVE_OUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        ctx.setOutput("curve", GeometryBundles.requireBundle(ctx.getInput("curve", Object.class)));
    }
}
