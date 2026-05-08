package ixdar.geometry.mesh.nodes.math;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.BoolField;
import ixdar.annotations.meshnode.FloatField;
import ixdar.annotations.meshnode.IntField;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.Vector3Field;
import ixdar.annotations.meshnode.Vector3Value;

/**
 * Scalar vs {@link FloatField} / {@link Vector3Field} broadcasting for geometry-node math.
 */
public final class FieldBroadcast {
    public static final String VS = " vs ";

    private FieldBroadcast() {
    }

    /**
     * Returns the broadcast length of two operands when at least one is a
     * {@link FloatField}, or 0 if neither is.
     *
     * @param a first operand (any type)
     * @param b second operand (any type)
     * @throws IllegalArgumentException if both operands are {@link FloatField}s with mismatched lengths
     * @return common length, or 0 if neither operand is a field
     */
    public static int floatFieldLength(Object a, Object b) {
        int la = a instanceof FloatField fa ? fa.length() : 0;
        int lb = b instanceof FloatField fb ? fb.length() : 0;
        if (la > 0 && lb > 0 && la != lb) {
            throw new IllegalArgumentException("Float field length mismatch: " + la + VS + lb);
        }
        return Math.max(la, lb);
    }

    /**
     * Three-operand variant of {@link #floatFieldLength(Object, Object)}.
     *
     * @param a first operand (any type)
     * @param b second operand (any type)
     * @param c third operand (any type)
     * @throws IllegalArgumentException if any operand is a {@link FloatField} whose length differs from the broadcast length
     * @return common length, or 0 if no operand is a field
     */
    public static int floatFieldLength3(Object a, Object b, Object c) {
        int l = 0;
        l = Math.max(l, a instanceof FloatField fa ? fa.length() : 0);
        l = Math.max(l, b instanceof FloatField fb ? fb.length() : 0);
        l = Math.max(l, c instanceof FloatField fc ? fc.length() : 0);
        if (l == 0) {
            return 0;
        }
        if (a instanceof FloatField fa && fa.length() != l) {
            throw new IllegalArgumentException("Float field length mismatch on x");
        }
        if (b instanceof FloatField fb && fb.length() != l) {
            throw new IllegalArgumentException("Float field length mismatch on y");
        }
        if (c instanceof FloatField fc && fc.length() != l) {
            throw new IllegalArgumentException("Float field length mismatch on z");
        }
        return l;
    }

    /**
     * Reads the {@code i}-th element from {@code o}, broadcasting scalars.
     *
     * @param o {@link FloatField}, {@link Number} scalar, or {@code null}
     * @param i element index into the field; ignored for scalars
     * @param def fallback returned when {@code o} is {@code null} or not a recognized type
     * @return float value
     */
    public static float floatAt(Object o, int i, float def) {
        if (o == null) {
            return def;
        }
        if (o instanceof FloatField f) {
            return f.get(i);
        }
        if (o instanceof Number n) {
            return n.floatValue();
        }
        return def;
    }

    /**
     * Integer counterpart of {@link #floatAt(Object, int, float)}.
     *
     * @param o {@link IntField}, {@link Number} scalar, or {@code null}
     * @param i element index into the field; ignored for scalars
     * @param def fallback returned when {@code o} is {@code null} or not a recognized type
     * @return int value
     */
    public static int intAt(Object o, int i, int def) {
        if (o == null) {
            return def;
        }
        if (o instanceof IntField f) {
            return f.get(i);
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        return def;
    }

    /**
     * Boolean counterpart of {@link #floatAt(Object, int, float)}.
     *
     * @param o {@link BoolField}, {@link Boolean} scalar, or {@code null}
     * @param i element index into the field; ignored for scalars
     * @param def fallback returned when {@code o} is {@code null} or not a recognized type
     * @return boolean value
     */
    public static boolean boolAt(Object o, int i, boolean def) {
        if (o == null) {
            return def;
        }
        if (o instanceof BoolField f) {
            return f.get(i);
        }
        if (o instanceof Boolean b) {
            return b;
        }
        return def;
    }

    /**
     * Reads {@code o} as a scalar float, used by ports that don't broadcast.
     *
     * @param o {@link Number} scalar or {@code null}
     * @param def fallback returned when {@code o} is {@code null} or not a {@link Number}
     * @throws IllegalArgumentException if {@code o} is a {@link FloatField} (caller expected a scalar)
     * @return float value
     */
    public static float floatScalarOrDefault(Object o, float def) {
        if (o == null) {
            return def;
        }
        if (o instanceof FloatField) {
            throw new IllegalArgumentException("expected scalar float input");
        }
        if (o instanceof Number n) {
            return n.floatValue();
        }
        return def;
    }

    /**
     * Integer counterpart of {@link #floatScalarOrDefault(Object, float)}.
     *
     * @param o {@link Number} scalar or {@code null}
     * @param def fallback returned when {@code o} is {@code null} or not a {@link Number}
     * @throws IllegalArgumentException if {@code o} is an {@link IntField} (caller expected a scalar)
     * @return int value
     */
    public static int intScalarOrDefault(Object o, int def) {
        if (o == null) {
            return def;
        }
        if (o instanceof IntField) {
            throw new IllegalArgumentException("expected scalar int input");
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        return def;
    }

    /**
     * Vector3 counterpart of {@link #floatFieldLength(Object, Object)}.
     *
     * @param a first operand (any type)
     * @param b second operand (any type)
     * @throws IllegalArgumentException if both operands are {@link Vector3Field}s with mismatched lengths
     * @return common length, or 0 if neither operand is a field
     */
    public static int vec3Length(Object a, Object b) {
        int la = a instanceof Vector3Field va ? va.length() : 0;
        int lb = b instanceof Vector3Field vb ? vb.length() : 0;
        if (la > 0 && lb > 0 && la != lb) {
            throw new IllegalArgumentException("Vec3 field length mismatch: " + la + VS + lb);
        }
        return Math.max(la, lb);
    }

    /**
     * Length of a single operand if it is a {@link Vector3Field}, else 0.
     *
     * @param a operand to inspect
     * @return field length, or 0 if {@code a} isn't a {@link Vector3Field}
     */
    public static int vec3Length1(Object a) {
        return a instanceof Vector3Field va ? va.length() : 0;
    }

    /**
     * Reads the {@code i}-th element of {@code o} into {@code dest}, broadcasting
     * {@link Vector3Value} scalars and falling back to {@code defaultV}.
     *
     * @param o {@link Vector3Field}, {@link Vector3Value}, or {@code null}
     * @param i element index into the field; ignored for scalars
     * @param defaultV fallback used when {@code o} is {@code null} or not a recognized type
     * @param dest output vector overwritten with the result
     */
    public static void vec3At(Object o, int i, Vector3Value defaultV, Vector3f dest) {
        if (o == null) {
            dest.set(defaultV.x(), defaultV.y(), defaultV.z());
            return;
        }
        if (o instanceof Vector3Field v) {
            dest.set(v.getX(i), v.getY(i), v.getZ(i));
            return;
        }
        if (o instanceof Vector3Value vv) {
            dest.set(vv.x(), vv.y(), vv.z());
            return;
        }
        dest.set(defaultV.x(), defaultV.y(), defaultV.z());
    }

    /**
     * Coerces {@code o} to a single {@link Vector3Value}: returns the value
     * itself if scalar, the field's first element if a non-empty
     * {@link Vector3Field}, or {@code def}.
     *
     * @param o input operand
     * @param def fallback returned when {@code o} is {@code null}, an empty field, or not a recognized type
     * @return scalar Vector3Value
     */
    public static Vector3Value vector3ValueOrDefault(Object o, Vector3Value def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Vector3Value vv) {
            return vv;
        }
        if (o instanceof Vector3Field v && v.length() > 0) {
            return v.toVector3Value(0);
        }
        return def;
    }

    /**
     * Reads a node input, falling back to the port's schema default when no
     * value was provided by the caller. Used by math nodes to keep the
     * "treat missing input as scalar default" branch a one-liner.
     *
     * @param ctx node context being evaluated
     * @param name input port name
     * @param schemaDefault default declared on the {@link ixdar.annotations.meshnode.InputPort}
     * @return the bound value, or {@code schemaDefault} when no value is present
     */
    public static Object getInputOrDefault(NodeContext ctx, String name, Object schemaDefault) {
        Object v = ctx.getInputValue(name);
        return v != null ? v : schemaDefault;
    }
}
