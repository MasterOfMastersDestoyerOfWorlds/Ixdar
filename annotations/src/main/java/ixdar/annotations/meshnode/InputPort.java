package ixdar.annotations.meshnode;

public record InputPort(String name, PortType type, Object defaultValue) {
    public InputPort {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Input port name must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("Input port type must not be null");
        }
        type.validate(name, defaultValue);
    }
}
