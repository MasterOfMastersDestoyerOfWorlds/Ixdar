package ixdar.geometry.mesh.graph;

/**
 * Parameter descriptor for the skeleton sensitivity optimizer, covering both
 * {@link InputParameterDescriptor} and {@link LiteralParameterDescriptor}.
 *
 * <p>{@link #overrideKey()} is the key used in the overrides map passed to
 * {@link NodeGraphRuntime#executeGraphResult}:
 * <ul>
 *   <li>For input params: the node ID (e.g. {@code "palm_x"})
 *   <li>For literal params: {@code "nodeId.argName"} (e.g. {@code "thumb_attach.theta"})
 *   <li>For vec3 components: {@code "nodeId.argName.x"} etc.
 * </ul>
 */
public record OptimizableParameter(
        String overrideKey,
        String displayName,
        float defaultValue,
        float minValue,
        float maxValue,
        boolean isLiteral) {

    /**
     * Adapter from a user-declared input node descriptor. Pulls default and
     * min/max from the descriptor (using INT vs FLOAT-typed fields as appropriate),
     * and uses the input node id as the override key.
     *
     * @param p input parameter descriptor harvested from the parsed graph
     * @return non-literal descriptor keyed by {@code p.nodeId()}
     */
    public static OptimizableParameter fromInput(InputParameterDescriptor p) {
        float def, min, max;
        if (p.kind() == InputParameterDescriptor.InputParameterKind.INT) {
            def = p.intDefault() != null ? p.intDefault() : 0;
            min = p.minInt() != null ? p.minInt() : Integer.MIN_VALUE;
            max = p.maxInt() != null ? p.maxInt() : Integer.MAX_VALUE;
        } else {
            def = p.floatDefault() != null ? p.floatDefault() : 0f;
            min = p.minFloat() != null ? p.minFloat() : Float.NEGATIVE_INFINITY;
            max = p.maxFloat() != null ? p.maxFloat() : Float.POSITIVE_INFINITY;
        }
        return new OptimizableParameter(p.nodeId(), p.name(), def, min, max, false);
    }

    /**
     * Adapter from a hardcoded-literal descriptor (e.g. {@code thumb_attach.theta}
     * or a vec3 component). Uses the descriptor's {@code overrideKey} for both
     * the override map key and the display name; missing min/max default to
     * unbounded.
     *
     * @param p literal parameter descriptor
     * @return literal-flagged descriptor
     */
    public static OptimizableParameter fromLiteral(LiteralParameterDescriptor p) {
        float min = p.minValue() != null ? p.minValue() : Float.NEGATIVE_INFINITY;
        float max = p.maxValue() != null ? p.maxValue() : Float.POSITIVE_INFINITY;
        return new OptimizableParameter(p.overrideKey(), p.overrideKey(), p.defaultValue(), min, max, true);
    }
}
