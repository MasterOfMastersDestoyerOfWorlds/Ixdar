package ixdar.geometry.mesh.nodes.curve;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

@MeshNodeAnnotation(id = "float_curve")
public class FloatCurveNode implements MeshNode {

    private static final InputPort FACTOR = new InputPort("factor", PortType.FLOAT, 1.0f);
    private static final InputPort VALUE = new InputPort("value", PortType.FLOAT, 0.0f);
    private static final OutputPort RESULT = new OutputPort("result", PortType.FLOAT);

    @Override
    public List<InputPort> inputs() {
        return List.of(FACTOR, VALUE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(RESULT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number v = ctx.getInput("value", Number.class);
        ctx.setOutput("result", v == null ? 0f : v.floatValue());
    }
}
