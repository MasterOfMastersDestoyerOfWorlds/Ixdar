package ixdar.geometry.mesh.nodes.api;

/**
 * Per-element float values (packed {@code float[]}, one element per domain index).
 */
public record FloatField(float[] data) {

    /**
     * Validate that the backing array is non-null.
     */
    public FloatField {
        if (data == null) {
            throw new IllegalArgumentException("data");
        }
    }

    /**
     * Number of values stored (i.e. {@code data.length}).
     *
     * @return element count
     */
    public int length() {
        return data.length;
    }

    /**
     * Value at the i-th element.
     *
     * @param i element index, must satisfy {@code 0 <= i < length()}
     * @return stored value at {@code data[i]}
     */
    public float get(int i) {
        return data[i];
    }

    /**
     * Build a field of {@code len} elements all equal to {@code v}.
     *
     * @param v value to repeat at every element
     * @param len element count, must be non-negative
     * @return new field of length {@code len}
     */
    public static FloatField constant(float v, int len) {
        float[] d = new float[len];
        for (int i = 0; i < len; i++) {
            d[i] = v;
        }
        return new FloatField(d);
    }

    /**
     * Defensive copy from a float array.
     *
     * @param src source array
     * @return new field that does not share storage with {@code src}
     */
    public static FloatField copyOf(float[] src) {
        float[] d = new float[src.length];
        System.arraycopy(src, 0, d, 0, src.length);
        return new FloatField(d);
    }
}
