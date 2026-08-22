package ixdar.annotations.meshnode;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Canonical mesh-node port names by type and semantic role. Single outputs use
 * these names; repeated outputs of one type remain descriptive.
 */
public final class CanonicalPortNames {

    private static final Map<PortType, String> CANONICAL = new EnumMap<>(PortType.class);
    private static final Map<PortType, Set<String>> ROLE_NAMES = new EnumMap<>(PortType.class);

    private static final Map<InputRole, String> INPUT_CANONICAL = new EnumMap<>(InputRole.class);
    private static final String VALUE = "value";
    private static final String RESULT = "result";

    static {
        CANONICAL.put(PortType.MESH, "mesh");
        CANONICAL.put(PortType.GEOMETRY_BUNDLE, "geometry");
        CANONICAL.put(PortType.BOOLEAN, VALUE);
        CANONICAL.put(PortType.FLOAT, RESULT);
        CANONICAL.put(PortType.INT, RESULT);
        CANONICAL.put(PortType.VECTOR3, "vector");
        CANONICAL.put(PortType.ROTATION, "rotation");
        CANONICAL.put(PortType.CLOSURE, "closure");
        CANONICAL.put(PortType.STRING, VALUE);

        // Role-specific exceptions — legitimate semantic alternates to the
        // canonical name. Added here instead of a generic "allow anything"
        // escape so the allowlist stays visible in one place.
        //
        // float_out/int_out: random_value's mode-switched outputs coexist, so
        // neither can take the shared canonical "result".
        // total_cost/next_vertex: input_shortest_edge_paths' per-vertex fields,
        // consumed by name downstream (edge_paths_to_selection reads next_vertex).
        ROLE_NAMES.put(PortType.BOOLEAN, Set.of("selection", "generated"));
        ROLE_NAMES.put(PortType.FLOAT, Set.of("float_out", "total_cost"));
        ROLE_NAMES.put(PortType.INT, Set.of("index", "int_out", "next_vertex"));
    }

    static {
        INPUT_CANONICAL.put(InputRole.OPERATION_SELECTOR, "operation");
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
     * Canonical (required) input port name for the given role.
     *
     * @param role semantic input role to look up
     * @throws IllegalArgumentException if no canonical name is defined for
     *                                  {@code role}
     * @return canonical name registered for {@code role}
     */
    public static String canonicalForRole(InputRole role) {
        String n = INPUT_CANONICAL.get(role);
        if (n == null) {
            throw new IllegalArgumentException("No canonical input name defined for role " + role);
        }
        return n;
    }

    /**
     * Classifies known input-role signatures. A mode-constrained string on a
     * {@code *_math} node is an operation selector; unrelated modes retain their
     * natural names.
     *
     * @param nodeId id of the owning mesh node (e.g. {@code "integer_math"})
     * @param port   input port to classify
     * @return matched role, or empty if {@code port} does not match any known role
     *         signature
     */
    public static Optional<InputRole> roleOf(String nodeId, InputPort port) {
        if (port == null || nodeId == null) {
            return Optional.empty();
        }
        if (nodeId.endsWith("_math")
                && port.type() == PortType.STRING
                && port.modes() != null) {
            return Optional.of(InputRole.OPERATION_SELECTOR);
        }
        return Optional.empty();
    }

    /**
     * Semantic input roles whose canonical names can be derived from declared port
     * shape.
     */
    public enum InputRole {
        /**
         * A {@link PortType#STRING} input with a {@link ModeConstraint} selecting the
         * operation to perform (e.g. {@code ADD}, {@code AND}). Canonical name:
         * {@code "operation"}.
         */
        OPERATION_SELECTOR
    }
}
