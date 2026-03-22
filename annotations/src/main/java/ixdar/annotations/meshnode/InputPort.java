package ixdar.annotations.meshnode;

public record InputPort(String name, PortType type, Object defaultValue, ModeConstraint modes) {
    public InputPort {
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
    }

    public InputPort(String name, PortType type, Object defaultValue) {
        this(name, type, defaultValue, null);
    }
}
