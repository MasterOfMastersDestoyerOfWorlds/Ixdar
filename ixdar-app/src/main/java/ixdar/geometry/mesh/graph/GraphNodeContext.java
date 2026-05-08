package ixdar.geometry.mesh.graph;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import ixdar.annotations.meshnode.FieldContext;
import ixdar.annotations.meshnode.NodeContext;

/**
 * Per-node {@link NodeContext} the runtime hands to {@code MeshNode.evaluate}.
 * Keeps the resolved input bag, the output bag the node writes into, the active
 * field context, and the DSL left-hand-side id of the node (used by hooks like
 * {@link AutoTagHook}).
 */
public class GraphNodeContext implements NodeContext {
    private final Map<String, Object> inputs = new HashMap<>();
    private final Map<String, Object> outputs = new HashMap<>();
    private FieldContext fieldContext;
    private String nodeAssignmentId;

    /**
     * Sets the DSL left-hand-side variable name of the node about to evaluate.
     *
     * @param id LHS identifier (e.g. {@code "thumb_attach"}); may be null
     */
    public void setNodeAssignmentId(String id) {
        this.nodeAssignmentId = id;
    }

    /**
     * @return DSL left-hand-side variable name, or null
     */
    @Override
    public String nodeAssignmentId() {
        return nodeAssignmentId;
    }

    /**
     * Stores a resolved input value for the named input port. Called by the
     * runtime before {@code evaluate}.
     *
     * @param portName input port name
     * @param value    resolved value (literal, upstream output, or override)
     */
    public void setInputValue(String portName, Object value) {
        inputs.put(portName, value);
    }

    /**
     * Untyped read of the input bag.
     *
     * @param name input port name
     * @return raw value, or null if not present
     */
    @Override
    public Object getInputValue(String name) {
        return inputs.get(name);
    }

    /**
     * Sets the field context (vertex domain) that nodes can read from
     * {@link #fieldContext()}.
     *
     * @param ctx active field context, or null
     */
    public void setFieldContext(FieldContext ctx) {
        this.fieldContext = ctx;
    }

    /**
     * @return field context most recently set on this node, or null
     */
    @Override
    public FieldContext fieldContext() {
        return fieldContext;
    }

    /**
     * Type-safe read of the input bag.
     *
     * @param <T>      requested type
     * @param portName input port name
     * @param type     class object for the requested type
     * @return value cast to {@code T}, or null when absent or incompatible
     */
    @Override
    public <T> T getInput(String portName, Class<T> type) {
        Object val = inputs.get(portName);
        if (val != null && type.isAssignableFrom(val.getClass())) {
            return type.cast(val);
        }
        return null;
    }

    /**
     * Writes an output value for the named output port.
     *
     * @param portName output port name
     * @param value    value to publish (may be null)
     */
    @Override
    public void setOutput(String portName, Object value) {
        outputs.put(portName, value);
    }

    /**
     * Untyped read of the output bag (used by the runtime when wiring downstream
     * nodes).
     *
     * @param portName output port name
     * @return raw value, or null if not present
     */
    public Object getOutput(String portName) {
        return outputs.get(portName);
    }

    /**
     * For runtime introspection (e.g. implicit field context after evaluate).
     *
     * @return read-only view of the output bag
     */
    public Map<String, Object> getOutputsSnapshot() {
        return Collections.unmodifiableMap(outputs);
    }

    /**
     * Type-safe read of the output bag.
     *
     * @param <T>  requested type
     * @param name output port name
     * @param type class object for the requested type
     * @return value cast to {@code T}, or null when absent or incompatible
     */
    @Override
    public <T> T getOutput(String name, Class<T> type) {
        Object val = outputs.get(name);
        if (val != null && type.isAssignableFrom(val.getClass())) {
            return type.cast(val);
        }
        return null;
    }
}