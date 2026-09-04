package ixdar.geometry.mesh.nodes.api;

import ixdar.geometry.mesh.curve.FloatCurveKernel;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.quadlayout.ChartAtlas;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.solver.system.DofSystem;

public enum PortType {
    /** Mesh plus named attribute slots: the one geometry type. */
    GEOMETRY_BUNDLE(GeometryBundle.class),
    FLOAT(Number.class),
    INT(Integer.class),
    BOOLEAN(Boolean.class),
    VECTOR3(Vector3Value.class),
    STRING(String.class),
    ROTATION(RotationValue.class),
    /** Sampled float curve produced by float_curve / function_curve. */
    CLOSURE(FloatCurveKernel.class),
    /** Cross field over a mesh. */
    CROSS_FIELD(CrossField.class),
    /** Per-corner UV assignment over a mesh. */
    UV_FIELD(UvField.class),
    /** Node-arc-patch network on a surface. */
    ARC_NETWORK(ArcNetwork.class),
    /** A solve's degrees of freedom and their couplings. */
    DOF_SYSTEM(DofSystem.class),
    /** Atlas of charts covering a surface. */
    CHART_ATLAS(ChartAtlas.class);

    private final Class<?> valueType;

    PortType(Class<?> valueType) {
        this.valueType = valueType;
    }

    /**
     * True if {@code value} is assignable to this port type. Numeric, boolean, vector, and
     * rotation types also accept their corresponding packed-field forms (e.g. {@link FloatField}
     * for {@link #FLOAT}). Null is always accepted.
     *
     * @param value candidate value (may be null)
     * @return whether {@code value} is compatible with this type
     */
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
            return value instanceof Vector3Value || value instanceof Vector3Field;
        }
        if (this == ROTATION) {
            return value instanceof RotationValue || value instanceof RotationField;
        }
        return valueType.isInstance(value);
    }

    /**
     * Throw if {@code value} is not compatible with this port type, naming {@code portName}
     * in the error message.
     *
     * @param portName port name to include in the diagnostic
     * @param value candidate value (may be null, which is always accepted)
     * @throws IllegalArgumentException if {@code value} is not assignable to this type
     */
    public void validate(String portName, Object value) {
        if (!accepts(value)) {
            throw new IllegalArgumentException(
                    "Port '" + portName + "' expects " + name() + " but received "
                            + value.getClass().getSimpleName());
        }
    }
}
