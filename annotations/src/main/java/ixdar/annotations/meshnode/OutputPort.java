package ixdar.annotations.meshnode;

public record OutputPort(String name, PortType type) {
    /**
     * Validate the port declaration: name is non-blank and type is non-null.
     */
    public OutputPort {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Output port name must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("Output port type must not be null");
        }
    }
}
