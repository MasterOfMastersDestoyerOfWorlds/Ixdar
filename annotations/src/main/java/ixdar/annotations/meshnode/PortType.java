package ixdar.annotations.meshnode;

public enum PortType {
    MESH(MeshValue.class),
    GEOMETRY_BUNDLE(GeometryBundleValue.class),
    FLOAT(Number.class),
    INT(Integer.class),
    BOOLEAN(Boolean.class),
    VECTOR3(Vector3Value.class),
    STRING(String.class),
    ROTATION(RotationValue.class),
    /** Float curve / closure payload (runtime object, e.g. FloatCurveKernel). */
    CLOSURE(Object.class);

    private final Class<?> valueType;

    PortType(Class<?> valueType) {
        this.valueType = valueType;
    }

    public boolean accepts(Object value) {
        if (value == null) {
            return true;
        }
        if (this == INT) {
            return value instanceof Integer;
        }
        return valueType.isInstance(value);
    }

    public void validate(String portName, Object value) {
        if (!accepts(value)) {
            throw new IllegalArgumentException(
                    "Port '" + portName + "' expects " + name() + " but received "
                            + value.getClass().getSimpleName());
        }
    }
}
