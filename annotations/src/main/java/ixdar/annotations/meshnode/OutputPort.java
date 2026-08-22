package ixdar.annotations.meshnode;

public final class OutputPort {
    public final String name;
    public final PortType type;

    /**
     * Validates the port declaration: name is non-blank and type is non-null.
     *
     * @param name port name (non-blank)
     * @param type port type
     * @throws IllegalArgumentException when the name is blank or the type is null
     */
    public OutputPort(String name, PortType type) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Output port name must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("Output port type must not be null");
        }
        this.name = name;
        this.type = type;
    }
}
