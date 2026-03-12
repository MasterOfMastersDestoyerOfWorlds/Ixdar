package ixdar.annotations.meshnode;

public interface NodeContext {
    <T> T getInput(String name, Class<T> type);

    <T> T getOutput(String name, Class<T> type);

    void setOutput(String name, Object value);
}
