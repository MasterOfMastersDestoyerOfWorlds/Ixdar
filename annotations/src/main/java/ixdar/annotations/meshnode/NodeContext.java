package ixdar.annotations.meshnode;

public interface NodeContext {
    /**
     * Read a typed input value by port name, cast to the requested type.
     *
     * @param <T>  requested input type; the held value must be assignable to it
     * @param name input port name as declared by the node's
     *             {@link MeshNode#inputs()}
     * @param type expected runtime type of the stored value
     * @return the input value (or its declared default), cast to {@code T};
     *         {@code null} if unset and no default
     */
    <T> T getInput(String name, Class<T> type);

    /**
     * Read back a previously published output by port name, cast to the requested
     * type.
     *
     * @param <T>  requested output type; the held value must be assignable to it
     * @param name output port name as declared by the node's
     *             {@link MeshNode#outputs()}
     * @param type expected runtime type of the stored value
     * @return the output value, cast to {@code T}; {@code null} if not yet set
     */
    <T> T getOutput(String name, Class<T> type);

    /**
     * Publish a value on the named output port. The value's type is validated
     * against the port's declared type.
     *
     * @param name  output port name as declared by the node's
     *              {@link MeshNode#outputs()}
     * @param value value to publish; must satisfy the port's type contract
     */
    void setOutput(String name, Object value);

    /**
     * Current implicit mesh domain for field nodes ({@code input_position}, etc.).
     * Null when no geometry has been established yet in the graph.
     *
     * @return the active field context, or {@code null} if no geometry domain is
     *         currently in scope
     */
    default FieldContext fieldContext() {
        return null;
    }

    /**
     * Raw input value (scalar or field) without type erasure; {@code null} if
     * unset.
     *
     * @param name input port name as declared by the node's
     *             {@link MeshNode#inputs()}
     * @return the stored input value (or default), without casting; {@code null} if
     *         unset and no default
     */
    default Object getInputValue(String name) {
        return null;
    }

    /**
     * The DSL assignment identifier used to auto-tag generated faces, or
     * {@code null} for contexts without an author-written assignment.
     *
     * @return the author-written variable name bound to this node's output, or
     *         {@code null} if unavailable
     */
    default String nodeAssignmentId() {
        return null;
    }
}
