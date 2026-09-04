package ixdar.geometry.mesh.nodes.api;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Canonical mesh-node port names by type and semantic role. Single outputs use
 * these names; repeated outputs of one type remain descriptive.
 */
public final class CanonicalPortNames {

    /**
     * Canonical (required) name of an operation-selector input: a
     * mode-constrained string choosing the operation to perform (e.g.
     * {@code ADD}, {@code AND}).
     */
    public static final String OPERATION_SELECTOR = "operation";

    private static final Map<PortType, String> CANONICAL = new EnumMap<>(PortType.class);
    private static final Map<PortType, Set<String>> ROLE_NAMES = new EnumMap<>(PortType.class);

    private static final String VALUE = "value";
    private static final String RESULT = "result";

    static {
        CANONICAL.put(PortType.GEOMETRY_BUNDLE, "geometry");
        CANONICAL.put(PortType.BOOLEAN, VALUE);
        CANONICAL.put(PortType.FLOAT, RESULT);
        CANONICAL.put(PortType.INT, RESULT);
        CANONICAL.put(PortType.VECTOR3, "vector");
        CANONICAL.put(PortType.ROTATION, "rotation");
        CANONICAL.put(PortType.CLOSURE, "closure");
        CANONICAL.put(PortType.STRING, VALUE);
        ROLE_NAMES.put(PortType.GEOMETRY_BUNDLE, Set.of("mesh"));
        ROLE_NAMES.put(PortType.BOOLEAN,
                Set.of("selection", "generated", "injective", "separation_violated",
                        "feature_edges"));
        ROLE_NAMES.put(PortType.FLOAT, Set.of("float_out", "total_cost"));
        ROLE_NAMES.put(PortType.INT,
                Set.of("index", "int_out", "next_vertex", "singularity_count",
                        "flipped_triangles", "id", "singularities"));
    }

    private CanonicalPortNames() {
    }

    /**
     * Canonical (preferred) output port name for the given type.
     *
     * @param type port type to look up
     * @throws IllegalArgumentException if no canonical name is defined for
     *                                  {@code type}
     * @return canonical name registered for {@code type}
     */
    public static String canonicalFor(PortType type) {
        String n = CANONICAL.get(type);
        if (n == null) {
            throw new IllegalArgumentException("No canonical output name defined for " + type);
        }
        return n;
    }

    /**
     * Role-specific alternate names that are also permitted for this type.
     *
     * @param type port type to look up
     * @return set of allowed role-specific names (empty if none are registered)
     */
    public static Set<String> allowedRoleNames(PortType type) {
        return ROLE_NAMES.getOrDefault(type, Set.of());
    }

    /**
     * True if {@code name} is an allowed output port name for {@code type}: the
     * canonical name, or one of the role-specific alternates.
     *
     * @param type port type to validate against
     * @param name candidate output port name (null returns false)
     * @return true if {@code name} matches the canonical name or a registered
     *         role-specific exception
     */
    public static boolean isAllowed(PortType type, String name) {
        if (name == null) {
            return false;
        }
        return name.equals(canonicalFor(type)) || allowedRoleNames(type).contains(name);
    }

    /**
     * True when the input is an operation selector: a mode-constrained string
     * on a {@code *_math} node, which must be named {@link #OPERATION_SELECTOR}.
     * Unrelated modes retain their natural names.
     *
     * @param nodeId id of the owning mesh node (e.g. {@code "integer_math"})
     * @param port   input port to classify
     * @return true if {@code port} is the node's operation selector
     */
    public static boolean isOperationSelector(String nodeId, InputPort port) {
        return port != null && nodeId != null
                && nodeId.endsWith("_math")
                && port.type == PortType.STRING
                && port.modes != null;
    }
}
