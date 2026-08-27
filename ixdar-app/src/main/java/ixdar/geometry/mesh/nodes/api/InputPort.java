package ixdar.geometry.mesh.nodes.api;

public final class InputPort {
    public final String name;
    public final PortType type;
    public final Object defaultValue;
    public final ModeConstraint modes;
    public final Float minValue;
    public final Float maxValue;

    /**
     * Validates the port declaration: name is non-blank, type is non-null, mode constraints
     * apply only to {@link PortType#STRING} ports and require a valid default, and the
     * default value is type-compatible with {@code type}.
     *
     * @param name         port name (non-blank)
     * @param type         port type
     * @param defaultValue default value, must be type-compatible with {@code type} (may be null)
     * @param modes        allowed canonical modes / aliases, or null for an unconstrained port
     * @param minValue     advisory lower bound, or null for unbounded
     * @param maxValue     advisory upper bound, or null for unbounded
     * @throws IllegalArgumentException when the declaration violates any of the rules above
     */
    public InputPort(String name, PortType type, Object defaultValue, ModeConstraint modes,
            Float minValue, Float maxValue) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Input port name must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("Input port type must not be null");
        }
        if (modes != null && type != PortType.STRING) {
            throw new IllegalArgumentException("ModeConstraint only applies to STRING ports: " + name);
        }
        if (modes != null) {
            if (defaultValue == null) {
                throw new IllegalArgumentException("Mode port '" + name + "' requires a non-null default String");
            }
            modes.validateDefault(defaultValue);
        }
        type.validate(name, defaultValue);
        this.name = name;
        this.type = type;
        this.defaultValue = defaultValue;
        this.modes = modes;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    /**
     * Convenience constructor for a plain port with no mode constraint and no numeric bounds.
     *
     * @param name port name (non-blank)
     * @param type port type
     * @param defaultValue default value, must be type-compatible with {@code type} (may be null)
     */
    public InputPort(String name, PortType type, Object defaultValue) {
        this(name, type, defaultValue, null, null, null);
    }

    /**
     * Convenience constructor for a {@link PortType#STRING} port carrying a {@link ModeConstraint}.
     *
     * @param name port name (non-blank)
     * @param type port type (must be {@link PortType#STRING} when {@code modes} is non-null)
     * @param defaultValue default value, required to be a valid mode string when {@code modes} is non-null
     * @param modes allowed canonical modes / aliases, or null for an unconstrained port
     */
    public InputPort(String name, PortType type, Object defaultValue, ModeConstraint modes) {
        this(name, type, defaultValue, modes, null, null);
    }

    /**
     * Convenience constructor for a numeric port with advisory min/max bounds.
     *
     * @param name port name (non-blank)
     * @param type port type
     * @param defaultValue default value, must be type-compatible with {@code type} (may be null)
     * @param minValue advisory lower bound, or null for unbounded
     * @param maxValue advisory upper bound, or null for unbounded
     */
    public InputPort(String name, PortType type, Object defaultValue, Float minValue, Float maxValue) {
        this(name, type, defaultValue, null, minValue, maxValue);
    }
}
