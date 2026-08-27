package ixdar.geometry.mesh.nodes.api;

/**
 * Per-element boolean selection (one flag per domain element).
 */
public record BoolField(boolean[] data) {

    /**
     * Validate that the backing array is non-null.
     */
    public BoolField {
        if (data == null) {
            throw new IllegalArgumentException("data");
        }
    }

    /**
     * Number of flags stored (i.e. {@code data.length}).
     *
     * @return element count
     */
    public int length() {
        return data.length;
    }

    /**
     * Flag at the i-th element, with out-of-range indices treated as {@code false}.
     *
     * @param i element index; values outside {@code [0, length())} yield {@code false}
     * @return the stored flag, or {@code false} when {@code i} is out of range
     */
    public boolean get(int i) {
        return i >= 0 && i < data.length && data[i];
    }

    /**
     * Build a field of {@code len} elements all equal to {@code v}.
     *
     * @param v value to repeat at every element
     * @param len element count, must be non-negative
     * @return new field of length {@code len}
     */
    public static BoolField constant(boolean v, int len) {
        boolean[] d = new boolean[len];
        for (int i = 0; i < len; i++) {
            d[i] = v;
        }
        return new BoolField(d);
    }

    /**
     * Defensive copy from a boolean array.
     *
     * @param src source array
     * @return new field that does not share storage with {@code src}
     */
    public static BoolField copyOf(boolean[] src) {
        boolean[] d = new boolean[src.length];
        System.arraycopy(src, 0, d, 0, src.length);
        return new BoolField(d);
    }
}
