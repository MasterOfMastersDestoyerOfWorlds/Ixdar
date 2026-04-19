package ixdar.annotations.meshnode;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Canonical naming rules for mesh-node output ports.
 *
 * <p>A single node's output should be predictable from its {@link PortType}:
 * if {@code cube} produces a {@link PortType#MESH}, its output is named
 * {@code "mesh"} — not {@code "geometry"}, {@code "result"}, or any variant.
 * Agents (and humans) can then chain nodes without reaching for docs.
 *
 * <p>This class is consumed by
 * {@code unit.mesh.MeshNodeCanonicalOutputNamesTest} which walks the
 * generated {@code MeshNodeRegistry_MeshNodes.MAP} and fails the build if
 * any node violates the rule below.
 *
 * <h2>Rule (v1)</h2>
 * <ol>
 *   <li>A node's single output of a given {@link PortType} must be named
 *       using {@link #canonicalFor(PortType)} — or, if the type has listed
 *       role-specific exceptions via {@link #allowedRoleNames(PortType)},
 *       one of those role names.</li>
 *   <li>Nodes with <em>multiple</em> outputs of the same {@link PortType}
 *       are exempt (their outputs must be descriptive, e.g.
 *       {@code separate_geometry → selected, inverted}). A future revision
 *       may require one primary-canonical output per type; for now,
 *       multi-output nodes are on the honor system.</li>
 * </ol>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>BOOLEAN has three legitimate roles: {@code selection} (selection
 *       primitives), {@code generated} (region-produced boolean per inset
 *       / extrude), and {@code value} (scalar boolean results from math).
 *       Only {@code value} is canonical; the others are listed as role
 *       exceptions.</li>
 *   <li>INT's canonical is {@code result} (covers integer_math,
 *       float_to_int, input_int). {@code index} is allowed as a role name
 *       for nodes that expose a vertex/face index as their value.</li>
 *   <li>FLOAT's canonical is {@code result} (same as INT). Accepting the
 *       same-name-different-type overlap here because distinguishing them
 *       would require renames nobody would read naturally
 *       ({@code float_result} vs {@code int_result}).</li>
 * </ul>
 */
public final class CanonicalPortNames {

    private CanonicalPortNames() {}

    private static final Map<PortType, String> CANONICAL = new EnumMap<>(PortType.class);
    private static final Map<PortType, Set<String>> ROLE_NAMES = new EnumMap<>(PortType.class);

    static {
        CANONICAL.put(PortType.MESH, "mesh");
        CANONICAL.put(PortType.GEOMETRY_BUNDLE, "geometry");
        CANONICAL.put(PortType.BOOLEAN, "value");
        CANONICAL.put(PortType.FLOAT, "result");
        CANONICAL.put(PortType.INT, "result");
        CANONICAL.put(PortType.VECTOR3, "vector");
        CANONICAL.put(PortType.ROTATION, "rotation");
        CANONICAL.put(PortType.CLOSURE, "closure");
        CANONICAL.put(PortType.STRING, "value");

        // Role-specific exceptions — legitimate semantic alternates to the
        // canonical name. Added here instead of a generic "allow anything"
        // escape so the allowlist stays visible in one place.
        ROLE_NAMES.put(PortType.BOOLEAN, Set.of("selection", "generated"));
        ROLE_NAMES.put(PortType.INT, Set.of("index"));
    }

    /** Canonical (preferred) output port name for the given type. */
    public static String canonicalFor(PortType type) {
        String n = CANONICAL.get(type);
        if (n == null) {
            throw new IllegalArgumentException("No canonical output name defined for " + type);
        }
        return n;
    }

    /** Role-specific alternate names that are also permitted for this type. */
    public static Set<String> allowedRoleNames(PortType type) {
        return ROLE_NAMES.getOrDefault(type, Set.of());
    }

    /**
     * True if {@code name} is an allowed output port name for {@code type}:
     * the canonical name, or one of the role-specific alternates.
     */
    public static boolean isAllowed(PortType type, String name) {
        if (name == null) {
            return false;
        }
        return name.equals(canonicalFor(type)) || allowedRoleNames(type).contains(name);
    }

    /**
     * Semantic role of an input port. Used to enforce canonical input naming
     * for input ports that play a well-known role across many nodes (e.g. the
     * mode-selector string on {@code *_math} nodes).
     *
     * <p>Keep this enum conservative — every value is an explicit extension,
     * not a generic allowlist. If a rule can't be derived from the port's
     * declared shape ({@link PortType} + {@link ModeConstraint}), it probably
     * doesn't belong here.
     */
    public enum InputRole {
        /**
         * A {@link PortType#STRING} input with a {@link ModeConstraint}
         * selecting the operation to perform (e.g. {@code ADD}, {@code AND}).
         * Canonical name: {@code "operation"}.
         */
        OPERATION_SELECTOR
    }

    private static final Map<InputRole, String> INPUT_CANONICAL = new EnumMap<>(InputRole.class);

    static {
        INPUT_CANONICAL.put(InputRole.OPERATION_SELECTOR, "operation");
    }

    /** Canonical (required) input port name for the given role. */
    public static String canonicalForRole(InputRole role) {
        String n = INPUT_CANONICAL.get(role);
        if (n == null) {
            throw new IllegalArgumentException("No canonical input name defined for role " + role);
        }
        return n;
    }

    /**
     * Classify an input port's role, if it matches a known role signature.
     *
     * <p>Currently recognizes {@link InputRole#OPERATION_SELECTOR}: a
     * {@link PortType#STRING} input with a {@link ModeConstraint} on a node
     * whose id ends in {@code "_math"} (i.e. it selects which arithmetic or
     * logical op to run). Other mode-bearing inputs across the catalog
     * (axis selectors on {@code curve_deform}, mapping modes on
     * {@code map_range}, output-type selectors on {@code random_value},
     * etc.) are deliberately <em>not</em> covered here — they're
     * semantically distinct and have their own natural names.
     *
     * <p>Extend cautiously — each role added here adds a build-time
     * enforcement across every node in the registry.
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
}
