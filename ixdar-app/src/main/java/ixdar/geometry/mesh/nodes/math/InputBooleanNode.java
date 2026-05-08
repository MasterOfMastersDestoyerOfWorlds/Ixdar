package ixdar.geometry.mesh.nodes.math;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

@MeshNodeAnnotation(id = "input_boolean")
public class InputBooleanNode implements MeshNode {
    public static final String NAME_2 = "name";
    public static final String DEFAULT_2 = "default";
    public static final String VALUE_2 = "value";
    private static final InputPort NAME = new InputPort(NAME_2, PortType.STRING, "");
    private static final InputPort DEFAULT = new InputPort(DEFAULT_2, PortType.BOOLEAN, false);
    private static final OutputPort VALUE = new OutputPort(VALUE_2, PortType.BOOLEAN);

    @Override
    public String description() {
        return "Declares a named boolean parameter for the graph with a default value.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                NAME_2, "Parameter name (shown in the UI, referenced by param_sweep / overrides).",
                DEFAULT_2, "Initial value when not overridden.",
                VALUE_2, "The (possibly-overridden) boolean value."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(NAME, DEFAULT);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(VALUE);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Object raw = ctx.getInputValue(DEFAULT_2);
        boolean b;
        if (raw instanceof Boolean) {
            b = (Boolean) raw;
        } else if (raw instanceof Number) {
            b = ((Number) raw).doubleValue() != 0.0;
        } else {
            b = false;
        }
        ctx.setOutput(VALUE_2,b);
    }
}
