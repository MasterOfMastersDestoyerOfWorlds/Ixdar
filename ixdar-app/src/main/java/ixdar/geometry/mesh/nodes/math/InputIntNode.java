package ixdar.geometry.mesh.nodes.math;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;

import java.util.Map;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

@MeshNodeAnnotation(id = "input_int")
public class InputIntNode implements MeshNode {
    public static final InputPort NAME = new InputPort("name", PortType.STRING, "");
    public static final InputPort DEFAULT = new InputPort("default", PortType.INT, 0);
    public static final InputPort MIN = new InputPort("min", PortType.INT, null);
    public static final InputPort MAX = new InputPort("max", PortType.INT, null);
    public static final OutputPort RESULT = new OutputPort("result", PortType.INT);

    @Override
    public String description() {
        return "Declares a named integer parameter for the graph with a default value and optional min/max bounds.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                NAME.name, "Parameter name (shown in UI, referenced by param_sweep / overrides).",
                DEFAULT.name, "Initial value when not overridden.",
                MIN.name, "Lower bound (optional). When null, unbounded below.",
                MAX.name, "Upper bound (optional). When null, unbounded above.",
                RESULT.name, "The (possibly-overridden) integer value."
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
        int v = valueNum == null ? 0 : valueNum.intValue();
        Number minNum = ctx.getInput(MIN.name, Number.class);
        Number maxNum = ctx.getInput(MAX.name, Number.class);
        int min = minNum == null ? Integer.MIN_VALUE : minNum.intValue();
        int max = maxNum == null ? Integer.MAX_VALUE : maxNum.intValue();
        if (v < min) {
            v = min;
        }
        if (v > max) {
            v = max;
        }
        ctx.setOutput(RESULT.name, v);
    }
}
