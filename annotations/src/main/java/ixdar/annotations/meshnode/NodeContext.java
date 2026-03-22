package ixdar.annotations.meshnode;

public interface NodeContext {
    <T> T getInput(String name, Class<T> type);

    <T> T getOutput(String name, Class<T> type);

    void setOutput(String name, Object value);

    /**
     * Current implicit mesh domain for field nodes ({@code input_position}, etc.). Null when no geometry
     * has been established yet in the graph.
     */
    default FieldContext fieldContext() {
        return null;
    }

    /**
     * Raw input value (scalar or field) without type erasure; {@code null} if unset.
     */
    default Object getInputValue(String name) {
        return null;
    }
}
