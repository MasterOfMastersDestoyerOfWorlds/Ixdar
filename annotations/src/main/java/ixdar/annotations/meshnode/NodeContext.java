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

    /**
     * The DSL assignment's left-hand-side identifier for this node, or {@code null}
     * when unknown (e.g. test contexts, synthetic intermediate nodes). For
     * {@code eye_inset = coons_inset_faces(...)} this returns {@code "eye_inset"}.
     * Used by the runtime's auto-tag hook to label newly-generated faces with the
     * author's variable name, so mesh_compare_regions can report per-feature error
     * using names that align with the written DSL.
     */
    default String nodeAssignmentId() {
        return null;
    }
}
