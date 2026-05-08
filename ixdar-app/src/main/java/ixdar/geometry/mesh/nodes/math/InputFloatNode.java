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
    public static final String NAME_2 = "name";
    public static final String DEFAULT_2 = "default";
    public static final String MIN_2 = "min";
    public static final String MAX_2 = "max";
    public static final String RESULT_2 = "result";
    private static final InputPort NAME = new InputPort(NAME_2, PortType.STRING, "");
    private static final InputPort DEFAULT = new InputPort(DEFAULT_2, PortType.FLOAT, 0.0f);
    private static final InputPort MIN = new InputPort(MIN_2, PortType.FLOAT, null);
    private static final InputPort MAX = new InputPort(MAX_2, PortType.FLOAT, null);
    private static final OutputPort RESULT = new OutputPort(RESULT_2, PortType.FLOAT);

    @Override
    public String description() {
        return "Declares a named float parameter for the graph with a default value and optional min/max bounds.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                NAME_2, "Parameter name (shown in UI, referenced by param_sweep / overrides).",
                DEFAULT_2, "Initial value when not overridden.",
                MIN_2, "Lower bound (optional). When null, unbounded below.",
                MAX_2, "Upper bound (optional). When null, unbounded above.",
                RESULT_2, "The (possibly-overridden) float value."
        );
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
        Number valueNum = ctx.getInput(DEFAULT_2, Number.class);
        float v = valueNum == null ? 0f : valueNum.floatValue();
        Number minNum = ctx.getInput(MIN_2, Number.class);
        Number maxNum = ctx.getInput(MAX_2, Number.class);
        float min = minNum == null ? Float.NEGATIVE_INFINITY : minNum.floatValue();
        float max = maxNum == null ? Float.POSITIVE_INFINITY : maxNum.floatValue();
        if (v < min) {
            v = min;
        }
        if (v > max) {
            v = max;
        }
        ctx.setOutput(RESULT_2, v);
    }
}
