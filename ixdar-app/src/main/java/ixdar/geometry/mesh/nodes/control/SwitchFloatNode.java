package ixdar.geometry.mesh.nodes.control;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

@MeshNodeAnnotation(id = "switch_float")
public class SwitchFloatNode implements MeshNode {

    private static final InputPort SWITCH = new InputPort("switch", PortType.BOOLEAN, false);
    private static final InputPort FALSE_VAL = new InputPort("false", PortType.FLOAT, 0.0f);
    private static final InputPort TRUE_VAL = new InputPort("true", PortType.FLOAT, 0.0f);
    private static final OutputPort RESULT = new OutputPort("result", PortType.FLOAT);

    @Override
    public List<InputPort> inputs() {
        return List.of(SWITCH, FALSE_VAL, TRUE_VAL);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(RESULT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Boolean sw = ctx.getInput("switch", Boolean.class);
        Number fa = ctx.getInput("false", Number.class);
        Number tr = ctx.getInput("true", Number.class);
        float f = fa == null ? 0f : fa.floatValue();
        float t = tr == null ? 0f : tr.floatValue();
        boolean on = sw != null && sw;
        ctx.setOutput("result", on ? t : f);
    }
}
