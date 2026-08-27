package ixdar.geometry.mesh.nodes.math;

import java.util.List;

import ixdar.geometry.mesh.nodes.api.InputPort;

import java.util.Map;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;

@MeshNodeAnnotation(id = "input_boolean")
public class InputBooleanNode implements MeshNode {
    public static final InputPort NAME = new InputPort("name", PortType.STRING, "");
    public static final InputPort DEFAULT = new InputPort("default", PortType.BOOLEAN, false);
    public static final OutputPort VALUE = new OutputPort("value", PortType.BOOLEAN);

    @Override
    public String description() {
        return "Declares a named boolean parameter for the graph with a default value.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                NAME.name, "Parameter name (shown in the UI, referenced by param_sweep / overrides).",
                DEFAULT.name, "Initial value when not overridden.",
                VALUE.name, "The (possibly-overridden) boolean value."
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
        Object raw = ctx.getInputValue(DEFAULT.name);
        boolean b;
        if (raw instanceof Boolean) {
            b = (Boolean) raw;
        } else if (raw instanceof Number) {
            b = ((Number) raw).doubleValue() != 0.0;
        } else {
            b = false;
        }
        ctx.setOutput(VALUE.name,b);
    }
}
