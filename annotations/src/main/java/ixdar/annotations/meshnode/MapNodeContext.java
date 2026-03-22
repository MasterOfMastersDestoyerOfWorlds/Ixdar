package ixdar.annotations.meshnode;

import java.util.HashMap;
import java.util.Map;

public class MapNodeContext implements NodeContext {
    private final Map<String, InputPort> inputPorts;
    private final Map<String, OutputPort> outputPorts;
    private final Map<String, Object> inputs;
    private final Map<String, Object> outputs;

    public MapNodeContext(MeshNode node) {
        this.inputPorts = new HashMap<>();
        this.outputPorts = new HashMap<>();
        this.inputs = new HashMap<>();
        this.outputs = new HashMap<>();

        for (InputPort inputPort : node.inputs()) {
            inputPorts.put(inputPort.name(), inputPort);
        }
        for (OutputPort outputPort : node.outputs()) {
            outputPorts.put(outputPort.name(), outputPort);
        }
    }

    public void setInput(String name, Object value) {
        InputPort inputPort = requireInputPort(name);
        inputPort.type().validate(name, value);
        Object stored = value;
        if (inputPort.modes() != null && value instanceof String s) {
            stored = inputPort.modes().normalize(s);
        }
        inputs.put(name, stored);
    }

    @Override
    public <T> T getInput(String name, Class<T> type) {
        InputPort inputPort = requireInputPort(name);
        Object value = inputs.containsKey(name) ? inputs.get(name) : inputPort.defaultValue();
        return castValue(name, value, type);
    }

    @Override
    public Object getInputValue(String name) {
        InputPort inputPort = requireInputPort(name);
        return inputs.containsKey(name) ? inputs.get(name) : inputPort.defaultValue();
    }

    @Override
    public <T> T getOutput(String name, Class<T> type) {
        requireOutputPort(name);
        return castValue(name, outputs.get(name), type);
    }

    @Override
    public void setOutput(String name, Object value) {
        OutputPort outputPort = requireOutputPort(name);
        outputPort.type().validate(name, value);
        outputs.put(name, value);
    }

    private InputPort requireInputPort(String name) {
        InputPort inputPort = inputPorts.get(name);
        if (inputPort == null) {
            throw new IllegalArgumentException("Unknown input port: " + name);
        }
        return inputPort;
    }

    private OutputPort requireOutputPort(String name) {
        OutputPort outputPort = outputPorts.get(name);
        if (outputPort == null) {
            throw new IllegalArgumentException("Unknown output port: " + name);
        }
        return outputPort;
    }

    private <T> T castValue(String name, Object value, Class<T> type) {
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(
                    "Port '" + name + "' expected read type " + type.getSimpleName() + " but held "
                            + value.getClass().getSimpleName());
        }
        return type.cast(value);
    }
}
