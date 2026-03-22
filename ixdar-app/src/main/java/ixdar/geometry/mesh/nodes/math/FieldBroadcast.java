package ixdar.geometry.mesh.nodes.math;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.BoolField;
import ixdar.annotations.meshnode.FloatField;
import ixdar.annotations.meshnode.IntField;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.Vec3Field;
import ixdar.annotations.meshnode.Vector3Value;

/**
 * Scalar vs {@link FloatField} / {@link Vec3Field} broadcasting for geometry-node math.
 */
public final class FieldBroadcast {

    private FieldBroadcast() {
    }

    public static int floatFieldLength(Object a, Object b) {
        int la = a instanceof FloatField fa ? fa.length() : 0;
        int lb = b instanceof FloatField fb ? fb.length() : 0;
        if (la > 0 && lb > 0 && la != lb) {
            throw new IllegalArgumentException("Float field length mismatch: " + la + " vs " + lb);
        }
        return Math.max(la, lb);
    }

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

    public static int vec3Length(Object a, Object b) {
        int la = a instanceof Vec3Field va ? va.length() : 0;
        int lb = b instanceof Vec3Field vb ? vb.length() : 0;
        if (la > 0 && lb > 0 && la != lb) {
            throw new IllegalArgumentException("Vec3 field length mismatch: " + la + " vs " + lb);
        }
        return Math.max(la, lb);
    }

    public static int vec3Length1(Object a) {
        return a instanceof Vec3Field va ? va.length() : 0;
    }

    public static void vec3At(Object o, int i, Vector3Value defaultV, Vector3f dest) {
        if (o == null) {
            dest.set(defaultV.x(), defaultV.y(), defaultV.z());
            return;
        }
        if (o instanceof Vec3Field v) {
            dest.set(v.getX(i), v.getY(i), v.getZ(i));
            return;
        }
        if (o instanceof Vector3Value vv) {
            dest.set(vv.x(), vv.y(), vv.z());
            return;
        }
        dest.set(defaultV.x(), defaultV.y(), defaultV.z());
    }

    public static Vector3Value vector3ValueOrDefault(Object o, Vector3Value def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Vector3Value vv) {
            return vv;
        }
        if (o instanceof Vec3Field v && v.length() > 0) {
            return v.toVector3Value(0);
        }
        return def;
    }

    public static Object getInputOrDefault(NodeContext ctx, String name, Object schemaDefault) {
        Object v = ctx.getInputValue(name);
        return v != null ? v : schemaDefault;
    }
}
