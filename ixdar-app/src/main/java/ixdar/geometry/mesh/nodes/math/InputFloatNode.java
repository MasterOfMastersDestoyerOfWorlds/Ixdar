package ixdar.geometry.mesh.nodes.math;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

@MeshNodeAnnotation(id = "input_float")
public class InputFloatNode implements MeshNode {
    private static final InputPort NAME = new InputPort("name", PortType.STRING, "");
    private static final InputPort DEFAULT = new InputPort("default", PortType.FLOAT, 0.0f);
    private static final InputPort MIN = new InputPort("min", PortType.FLOAT, null);
    private static final InputPort MAX = new InputPort("max", PortType.FLOAT, null);
    private static final OutputPort RESULT = new OutputPort("result", PortType.FLOAT);

    @Override
    public String description() {
        return "Declares a named float parameter for the graph with a default value and optional min/max bounds.";
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(NAME, DEFAULT, MIN, MAX);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(RESULT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number valueNum = ctx.getInput("default", Number.class);
        float v = valueNum == null ? 0f : valueNum.floatValue();
        Number minNum = ctx.getInput("min", Number.class);
        Number maxNum = ctx.getInput("max", Number.class);
        float min = minNum == null ? Float.NEGATIVE_INFINITY : minNum.floatValue();
        float max = maxNum == null ? Float.POSITIVE_INFINITY : maxNum.floatValue();
        if (v < min) {
            v = min;
        }
        if (v > max) {
            v = max;
        }
        ctx.setOutput("result", v);
    }
}
