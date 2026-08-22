package ixdar.annotations.meshnode;

import java.util.HashMap;
import java.util.Map;

public class MapNodeContext implements NodeContext {
    private final Map<String, InputPort> inputPorts;
    private final Map<String, OutputPort> outputPorts;
    private final Map<String, Object> inputs;
    private final Map<String, Object> outputs;

    /**
     * Build an empty context for the given node, indexing its declared input and output ports by name.
     * Inputs and outputs start unset; callers populate them via {@link #setInput} and {@link #setOutput}.
     *
     * @param node node whose port shape defines which names this context will accept
     */
    public MapNodeContext(MeshNode node) {
        this.inputPorts = new HashMap<>();
        this.outputPorts = new HashMap<>();
        this.inputs = new HashMap<>();
        this.outputs = new HashMap<>();

        for (InputPort inputPort : node.inputs()) {
            inputPorts.put(inputPort.name, inputPort);
        }
        for (OutputPort outputPort : node.outputs()) {
            outputPorts.put(outputPort.name, outputPort);
        }
    }

    /**
     * Stage a value on the named input port, validating it against the port's declared type. String values
     * are normalized through the port's mode set when one is declared.
     *
     * @param name input port name; must match a port declared by the node
     * @param value value to bind; must satisfy the port's type contract
     */
    public void setInput(String name, Object value) {
        InputPort inputPort = requireInputPort(name);
        inputPort.type.validate(name, value);
        Object stored = value;
        if (inputPort.modes != null && value instanceof String s) {
            stored = inputPort.modes.normalize(s);
        }
        inputs.put(name, stored);
    }

    /**
     * Read a staged input value by port name, falling back to the port's declared default when unset.
     *
     * @param name input port name; must match a port declared by the node
     * @param type expected runtime type of the stored value
     * @return the input value cast to {@code T}, or {@code null} if neither a value nor default is present
     */
    @Override
    public <T> T getInput(String name, Class<T> type) {
        InputPort inputPort = requireInputPort(name);
        Object value = inputs.containsKey(name) ? inputs.get(name) : inputPort.defaultValue;
        return castValue(name, value, type);
    }

    /**
     * Read a staged input value by port name without casting, falling back to the port's default when unset.
     *
     * @param name input port name; must match a port declared by the node
     * @return the raw input value (or default), or {@code null} if neither is present
     */
    @Override
    public Object getInputValue(String name) {
        InputPort inputPort = requireInputPort(name);
        return inputs.containsKey(name) ? inputs.get(name) : inputPort.defaultValue;
    }

    /**
     * Read back a value previously published to the named output port.
     *
     * @param name output port name; must match a port declared by the node
     * @param type expected runtime type of the stored value
     * @return the output value cast to {@code T}, or {@code null} if not yet set
     */
    @Override
    public <T> T getOutput(String name, Class<T> type) {
        requireOutputPort(name);
        return castValue(name, outputs.get(name), type);
    }

    /**
     * Publish a value on the named output port, validating it against the port's declared type.
     *
     * @param name output port name; must match a port declared by the node
     * @param value value to publish; must satisfy the port's type contract
     */
    @Override
    public void setOutput(String name, Object value) {
        OutputPort outputPort = requireOutputPort(name);
        outputPort.type.validate(name, value);
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
