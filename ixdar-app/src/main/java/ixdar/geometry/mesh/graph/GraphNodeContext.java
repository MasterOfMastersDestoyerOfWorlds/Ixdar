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

    public void setNodeAssignmentId(String id) {
        this.nodeAssignmentId = id;
    }

    @Override
    public String nodeAssignmentId() {
        return nodeAssignmentId;
    }

    public void setInputValue(String portName, Object value) {
        inputs.put(portName, value);
    }

    @Override
    public Object getInputValue(String name) {
        return inputs.get(name);
    }

    public void setFieldContext(FieldContext ctx) {
        this.fieldContext = ctx;
    }

    @Override
    public FieldContext fieldContext() {
        return fieldContext;
    }

    @Override
    public <T> T getInput(String portName, Class<T> type) {
        Object val = inputs.get(portName);
        if (val != null && type.isAssignableFrom(val.getClass())) {
            return type.cast(val);
        }
        return null;
    }

    @Override
    public void setOutput(String portName, Object value) {
        outputs.put(portName, value);
    }

    public Object getOutput(String portName) {
        return outputs.get(portName);
    }

    /** For runtime introspection (e.g. implicit field context after evaluate). */
    public Map<String, Object> getOutputsSnapshot() {
        return Collections.unmodifiableMap(outputs);
    }

    @Override
    public <T> T getOutput(String name, Class<T> type) {
        Object val = outputs.get(name);
        if (val != null && type.isAssignableFrom(val.getClass())) {
            return type.cast(val);
        }
        return null;
    }
}