package ixdar.annotations.meshnode;

/**
 * Per-element quaternions (packed xyzw per element).
 */
public record RotationField(float[] data) {
    private static final int NUM_4 = 4;
    private static final int NUM_3 = 3;

    /**
     * Validate that the backing array packs whole xyzw quadruples.
     */
    public RotationField {
        if (data == null || data.length % NUM_4 != 0) {
            throw new IllegalArgumentException("data length must be divisible by 4");
        }
    }

    /**
     * Number of quaternions stored (i.e. {@code data.length / 4}).
     *
     * @return element count
     */
    public int length() {
        return data.length / NUM_4;
    }

    /**
     * Materialize the i-th element as a {@link RotationValue}.
     *
     * @param i element index, must satisfy {@code 0 <= i < length()}
     * @return new value with x, y, z, w copied from the i-th packed quadruple
     */
    public RotationValue rotationAt(int i) {
        int o = NUM_4 * i;
        return new RotationValue(data[o], data[o + 1], data[o + 2], data[o + NUM_3]);
    }
}
