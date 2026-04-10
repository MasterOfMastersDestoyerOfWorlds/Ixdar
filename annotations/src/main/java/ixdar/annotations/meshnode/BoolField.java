package ixdar.annotations.meshnode;

/**
 * Per-element boolean selection (one flag per domain element).
 */
public record BoolField(boolean[] data) {

    public BoolField {
        if (data == null) {
            throw new IllegalArgumentException("data");
        }
    }

    public int length() {
        return data.length;
    }

    public boolean get(int i) {
        return i >= 0 && i < data.length && data[i];
    }

    public static BoolField constant(boolean v, int len) {
        boolean[] d = new boolean[len];
        for (int i = 0; i < len; i++) {
            d[i] = v;
        }
        return new BoolField(d);
    }

    public static BoolField copyOf(boolean[] src) {
        boolean[] d = new boolean[src.length];
        System.arraycopy(src, 0, d, 0, src.length);
        return new BoolField(d);
    }
}
