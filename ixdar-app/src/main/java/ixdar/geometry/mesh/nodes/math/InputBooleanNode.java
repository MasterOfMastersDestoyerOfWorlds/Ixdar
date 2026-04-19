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
    private static final InputPort NAME = new InputPort("name", PortType.STRING, "");
    private static final InputPort DEFAULT = new InputPort("default", PortType.BOOLEAN, false);
    private static final OutputPort VALUE = new OutputPort("value", PortType.BOOLEAN);

    @Override
    public String description() {
        return "Declares a named boolean parameter for the graph with a default value.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "name", "Parameter name (shown in the UI, referenced by param_sweep / overrides).",
                "default", "Initial value when not overridden.",
                "value", "The (possibly-overridden) boolean value."
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
        Object raw = ctx.getInputValue("default");
        boolean b;
        if (raw instanceof Boolean) {
            b = (Boolean) raw;
        } else if (raw instanceof Number) {
            b = ((Number) raw).doubleValue() != 0.0;
        } else {
            b = false;
        }
        ctx.setOutput("value",b);
    }
}
