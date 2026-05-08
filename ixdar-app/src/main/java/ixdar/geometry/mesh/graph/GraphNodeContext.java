package ixdar.geometry.mesh.graph;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import ixdar.annotations.meshnode.FieldContext;
import ixdar.annotations.meshnode.NodeContext;

public class GraphNodeContext implements NodeContext {
    private final Map<String, Object> inputs = new HashMap<>();
    private final Map<String, Object> outputs = new HashMap<>();
    private FieldContext fieldContext;
    private String nodeAssignmentId;

    /**
     * TODO: document {@code setNodeAssignmentId}.
     *
     * @param id TODO: describe
     */
    public void setNodeAssignmentId(String id) {
        this.nodeAssignmentId = id;
    }

    /**
     * TODO: document {@code nodeAssignmentId}.
     *
     * @return TODO: describe
     */
    @Override
    public String nodeAssignmentId() {
        return nodeAssignmentId;
    }

    /**
     * TODO: document {@code setInputValue}.
     *
     * @param portName TODO: describe
     * @param value TODO: describe
     */
    public void setInputValue(String portName, Object value) {
        inputs.put(portName, value);
    }

    /**
     * TODO: document {@code getInputValue}.
     *
     * @param name TODO: describe
     * @return TODO: describe
     */
    @Override
    public Object getInputValue(String name) {
        return inputs.get(name);
    }

    /**
     * TODO: document {@code setFieldContext}.
     *
     * @param ctx TODO: describe
     */
    public void setFieldContext(FieldContext ctx) {
        this.fieldContext = ctx;
    }

    /**
     * TODO: document {@code fieldContext}.
     *
     * @return TODO: describe
     */
    @Override
    public FieldContext fieldContext() {
        return fieldContext;
    }

    /**
     * TODO: document {@code getInput}.
     *
     * @param <T> TODO: describe
     * @param portName TODO: describe
     * @param type TODO: describe
     * @return TODO: describe
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
     * TODO: document {@code setOutput}.
     *
     * @param portName TODO: describe
     * @param value TODO: describe
     */
    @Override
    public void setOutput(String portName, Object value) {
        outputs.put(portName, value);
    }

    /**
     * TODO: document {@code getOutput}.
     *
     * @param portName TODO: describe
     * @return TODO: describe
     */
    public Object getOutput(String portName) {
        return outputs.get(portName);
    }

    /**
     * For runtime introspection (e.g. implicit field context after evaluate).
     *
     * @return TODO: describe
     */
    public Map<String, Object> getOutputsSnapshot() {
        return Collections.unmodifiableMap(outputs);
    }

    /**
     * TODO: document {@code getOutput}.
     *
     * @param <T> TODO: describe
     * @param name TODO: describe
     * @param type TODO: describe
     * @return TODO: describe
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