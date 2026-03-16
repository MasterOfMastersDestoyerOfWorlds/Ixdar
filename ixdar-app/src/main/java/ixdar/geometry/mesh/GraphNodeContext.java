package ixdar.geometry.mesh;

import java.util.HashMap;
import java.util.Map;
import ixdar.annotations.meshnode.NodeContext;

public class GraphNodeContext implements NodeContext {
    private final Map<String, Object> inputs = new HashMap<>();
    private final Map<String, Object> outputs = new HashMap<>();

    public void setInputValue(String portName, Object value) {
        inputs.put(portName, value);
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

    @Override
    public <T> T getOutput(String name, Class<T> type) {
        Object val = outputs.get(name);
        if (val != null && type.isAssignableFrom(val.getClass())) {
            return type.cast(val);
        }
        return null;
    }
}