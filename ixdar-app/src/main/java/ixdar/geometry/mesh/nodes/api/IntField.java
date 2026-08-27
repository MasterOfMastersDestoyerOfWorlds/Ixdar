package ixdar.geometry.mesh.nodes.api;

/**
 * Per-element int values (one index per domain element).
 */
public record IntField(int[] data) {

    /**
     * Validate that the backing array is non-null.
     */
    public IntField {
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
    public int get(int i) {
        return data[i];
    }

    /**
     * Build a field of {@code len} elements all equal to {@code v}.
     *
     * @param v value to repeat at every element
     * @param len element count, must be non-negative
     * @return new field of length {@code len}
     */
    public static IntField constant(int v, int len) {
        int[] d = new int[len];
        for (int i = 0; i < len; i++) {
            d[i] = v;
        }
        return new IntField(d);
    }

    /**
     * Defensive copy from an int array.
     *
     * @param src source array
     * @return new field that does not share storage with {@code src}
     */
    public static IntField copyOf(int[] src) {
        int[] d = new int[src.length];
        System.arraycopy(src, 0, d, 0, src.length);
        return new IntField(d);
    }
}
