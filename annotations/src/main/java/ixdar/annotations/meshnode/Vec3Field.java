package ixdar.annotations.meshnode;

/**
 * Per-element 3D vectors (packed xyz per element: {@code data.length == 3 * elementCount}).
 */
public record Vec3Field(float[] data) {

    public Vec3Field {
        if (data == null || data.length % 3 != 0) {
            throw new IllegalArgumentException("data length must be non-null and divisible by 3");
        }
    }

    public int length() {
        return data.length / 3;
    }

    public float getX(int i) {
        return data[3 * i];
    }

    public float getY(int i) {
        return data[3 * i + 1];
    }

    public float getZ(int i) {
        return data[3 * i + 2];
    }

    public Vector3Value toVector3Value(int i) {
        return new Vector3Value(getX(i), getY(i), getZ(i));
    }

    public static Vec3Field constant(Vector3Value v, int len) {
        float[] d = new float[len * 3];
        for (int i = 0; i < len; i++) {
            d[3 * i] = v.x();
            d[3 * i + 1] = v.y();
            d[3 * i + 2] = v.z();
        }
        return new Vec3Field(d);
    }

    public static Vec3Field copyOf(float[] src) {
        float[] d = new float[src.length];
        System.arraycopy(src, 0, d, 0, src.length);
        return new Vec3Field(d);
    }
}
