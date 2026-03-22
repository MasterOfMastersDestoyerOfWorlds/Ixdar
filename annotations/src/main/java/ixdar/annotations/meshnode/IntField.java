package ixdar.annotations.meshnode;

/**
 * Per-element int values (one index per domain element).
 */
public record IntField(int[] data) {

    public IntField {
        if (data == null) {
            throw new IllegalArgumentException("data");
        }
    }

    public int length() {
        return data.length;
    }

    public int get(int i) {
        return data[i];
    }

    public static IntField constant(int v, int len) {
        int[] d = new int[len];
        for (int i = 0; i < len; i++) {
            d[i] = v;
        }
        return new IntField(d);
    }

    public static IntField copyOf(int[] src) {
        int[] d = new int[src.length];
        System.arraycopy(src, 0, d, 0, src.length);
        return new IntField(d);
    }
}
