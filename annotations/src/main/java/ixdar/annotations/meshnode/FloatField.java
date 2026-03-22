package ixdar.annotations.meshnode;

/**
 * Per-element float values (packed {@code float[]}, one element per domain index).
 */
public record FloatField(float[] data) {

    public FloatField {
        if (data == null) {
            throw new IllegalArgumentException("data");
        }
    }

    public int length() {
        return data.length;
    }

    public float get(int i) {
        return data[i];
    }

    public static FloatField constant(float v, int len) {
        float[] d = new float[len];
        for (int i = 0; i < len; i++) {
            d[i] = v;
        }
        return new FloatField(d);
    }

    public static FloatField copyOf(float[] src) {
        float[] d = new float[src.length];
        System.arraycopy(src, 0, d, 0, src.length);
        return new FloatField(d);
    }
}
