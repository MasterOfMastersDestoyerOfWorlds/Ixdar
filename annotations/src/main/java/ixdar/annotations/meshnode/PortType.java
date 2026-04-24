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
    CLOSURE(Object.class),
    /** Dungeon-generation room list (see ixdar.procgen.dungeon.values.RoomListValue). */
    ROOM_LIST(Object.class),
    /** Dungeon-generation edge graph (see ixdar.procgen.dungeon.values.EdgeGraphValue). */
    EDGE_GRAPH(Object.class),
    /** Dungeon-generation tile grid (see ixdar.procgen.dungeon.values.TileGridValue). */
    TILE_GRID(Object.class);

    private final Class<?> valueType;

    PortType(Class<?> valueType) {
        this.valueType = valueType;
    }

    public boolean accepts(Object value) {
        if (value == null) {
            return true;
        }
        if (this == INT) {
            return value instanceof Integer || value instanceof IntField;
        }
        if (this == FLOAT) {
            return value instanceof Number || value instanceof FloatField;
        }
        if (this == BOOLEAN) {
            return value instanceof Boolean || value instanceof BoolField;
        }
        if (this == VECTOR3) {
            return value instanceof Vector3Value || value instanceof Vec3Field;
        }
        if (this == ROTATION) {
            return value instanceof RotationValue || value instanceof RotationField;
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
