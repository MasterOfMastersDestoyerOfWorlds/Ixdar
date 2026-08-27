package ixdar.geometry.mesh.nodes.math;

import java.util.List;

import ixdar.geometry.mesh.nodes.api.InputPort;

import java.util.Map;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;

@MeshNodeAnnotation(id = "input_float")
public class InputFloatNode implements MeshNode {
    public static final InputPort NAME = new InputPort("name", PortType.STRING, "");
    public static final InputPort DEFAULT = new InputPort("default", PortType.FLOAT, 0.0f);
    public static final InputPort MIN = new InputPort("min", PortType.FLOAT, null);
    public static final InputPort MAX = new InputPort("max", PortType.FLOAT, null);
    public static final OutputPort RESULT = new OutputPort("result", PortType.FLOAT);

    @Override
    public String description() {
        return "Declares a named float parameter for the graph with a default value and optional min/max bounds.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                NAME.name, "Parameter name (shown in UI, referenced by param_sweep / overrides).",
                DEFAULT.name, "Initial value when not overridden.",
                MIN.name, "Lower bound (optional). When null, unbounded below.",
                MAX.name, "Upper bound (optional). When null, unbounded above.",
                RESULT.name, "The (possibly-overridden) float value."
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
        Number valueNum = ctx.getInput(DEFAULT.name, Number.class);
        float v = valueNum == null ? 0f : valueNum.floatValue();
        Number minNum = ctx.getInput(MIN.name, Number.class);
        Number maxNum = ctx.getInput(MAX.name, Number.class);
        float min = minNum == null ? Float.NEGATIVE_INFINITY : minNum.floatValue();
        float max = maxNum == null ? Float.POSITIVE_INFINITY : maxNum.floatValue();
        if (v < min) {
            v = min;
        }
        if (v > max) {
            v = max;
        }
        ctx.setOutput(RESULT.name, v);
    }
}
