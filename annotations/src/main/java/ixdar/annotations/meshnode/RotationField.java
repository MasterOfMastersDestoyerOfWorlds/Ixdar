package ixdar.annotations.meshnode;

/**
 * Per-element quaternions (packed xyzw per element).
 */
public record RotationField(float[] data) {

    public RotationField {
        if (data == null || data.length % 4 != 0) {
            throw new IllegalArgumentException("data length must be divisible by 4");
        }
    }

    public int length() {
        return data.length / 4;
    }

    public RotationValue rotationAt(int i) {
        int o = 4 * i;
        return new RotationValue(data[o], data[o + 1], data[o + 2], data[o + 3]);
    }
}
