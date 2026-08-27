package ixdar.geometry.mesh.nodes.api;

/**
 * Per-element 3D vectors (packed xyz per element: {@code data.length == 3 * elementCount}).
 */
public record Vector3Field(float[] data) {
    private static final int NUM_3 = 3;

    /**
     * Validate that the backing array packs whole xyz triples.
     */
    public Vector3Field {
        if (data == null || data.length % NUM_3 != 0) {
            throw new IllegalArgumentException("data length must be non-null and divisible by 3");
        }
    }

    /**
     * Number of vectors stored (i.e. {@code data.length / 3}).
     *
     * @return element count
     */
    public int length() {
        return data.length / NUM_3;
    }

    /**
     * X component of the i-th vector.
     *
     * @param i element index, must satisfy {@code 0 <= i < length()}
     * @return packed value at offset {@code 3*i}
     */
    public float getX(int i) {
        return data[NUM_3 * i];
    }

    /**
     * Y component of the i-th vector.
     *
     * @param i element index, must satisfy {@code 0 <= i < length()}
     * @return packed value at offset {@code 3*i + 1}
     */
    public float getY(int i) {
        return data[NUM_3 * i + 1];
    }

    /**
     * Z component of the i-th vector.
     *
     * @param i element index, must satisfy {@code 0 <= i < length()}
     * @return packed value at offset {@code 3*i + 2}
     */
    public float getZ(int i) {
        return data[NUM_3 * i + 2];
    }

    /**
     * Materialize the i-th element as a {@link Vector3Value}.
     *
     * @param i element index, must satisfy {@code 0 <= i < length()}
     * @return new value with x, y, z copied from the i-th packed triple
     */
    public Vector3Value toVector3Value(int i) {
        return new Vector3Value(getX(i), getY(i), getZ(i));
    }

    /**
     * Build a field of {@code len} elements all equal to {@code v}.
     *
     * @param v value to repeat at every element
     * @param len element count, must be non-negative
     * @return new field of length {@code len}
     */
    public static Vector3Field constant(Vector3Value v, int len) {
        float[] d = new float[len * NUM_3];
        for (int i = 0; i < len; i++) {
            d[NUM_3 * i] = v.x();
            d[NUM_3 * i + 1] = v.y();
            d[NUM_3 * i + 2] = v.z();
        }
        return new Vector3Field(d);
    }

    /**
     * Defensive copy from a packed xyz array.
     *
     * @param src source array; length must be divisible by 3
     * @return new field that does not share storage with {@code src}
     */
    public static Vector3Field copyOf(float[] src) {
        float[] d = new float[src.length];
        System.arraycopy(src, 0, d, 0, src.length);
        return new Vector3Field(d);
    }
}
