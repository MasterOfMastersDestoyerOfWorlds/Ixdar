package ixdar.nodegraph.api;

import ixdar.nodegraph.field.FloatCurveKernel;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.paths.query.SurfaceCreases;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.quadlayout.ChartAtlas;
import ixdar.geometry.mesh.quadlayout.QuadLayout;
import ixdar.geometry.mesh.data.fields.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.numeric.sparse.system.DofSystem;

import ixdar.nodegraph.field.BoolField;
import ixdar.nodegraph.field.FloatField;
import ixdar.nodegraph.field.IntField;
import ixdar.nodegraph.field.RotationField;
import ixdar.nodegraph.field.RotationValue;
import ixdar.nodegraph.field.UvField;
import ixdar.nodegraph.field.Vector3Field;
import ixdar.nodegraph.field.Vector3Value;
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
    /** Intrinsic metric and connection of a surface, shared read-only by every consumer. */
    SURFACE_METRIC(SurfaceMetric.class),
    /** Groove strength and crease path cost of a surface, shared read-only by every ring. */
    SURFACE_CREASES(SurfaceCreases.class),
    /** Per-corner UV assignment over a mesh. */
    UV_FIELD(UvField.class),
    /** Node-arc-patch network on a surface. */
    ARC_NETWORK(ArcNetwork.class),
    /** A solve's degrees of freedom and their couplings. */
    DOF_SYSTEM(DofSystem.class),
    /** Atlas of charts covering a surface. */
    CHART_ATLAS(ChartAtlas.class),
    /** Every stage product of one quad layout. */
    QUAD_LAYOUT(QuadLayout.class);

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
