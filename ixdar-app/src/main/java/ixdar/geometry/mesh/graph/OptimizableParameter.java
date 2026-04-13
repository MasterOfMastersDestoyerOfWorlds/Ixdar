package ixdar.geometry.mesh.graph;

/**
 * Unified parameter descriptor for the skeleton sensitivity optimizer.
 * Wraps both {@link InputParameterDescriptor} (user-declared input nodes) and
 * {@link LiteralParameterDescriptor} (hardcoded literal arguments).
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

    public static OptimizableParameter fromLiteral(LiteralParameterDescriptor p) {
        float min = p.minValue() != null ? p.minValue() : Float.NEGATIVE_INFINITY;
        float max = p.maxValue() != null ? p.maxValue() : Float.POSITIVE_INFINITY;
        return new OptimizableParameter(p.overrideKey(), p.overrideKey(), p.defaultValue(), min, max, true);
    }
}
