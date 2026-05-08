package ixdar.procgen.dungeon.physics;

/**
 * Immutable 3D float vector. Used as the interchange type for capsule positions, MTVs, and
 * deltas in this package. Kept as a plain record so the physics functions are obviously pure
 * (no mutation, no aliasing). Joml's {@code Vector3f} is mutable and risky to pass through a
 * collision pipeline.
 */
public record Vec3f(float x, float y, float z) {

    public static final Vec3f ZERO = new Vec3f(0f, 0f, 0f);

    /**
     * TODO: document {@code add}.
     *
     * @param o TODO: describe
     * @return TODO: describe
     */
    public Vec3f add(Vec3f o) {
        return new Vec3f(x + o.x, y + o.y, z + o.z);
    }

    /**
     * TODO: document {@code sub}.
     *
     * @param o TODO: describe
     * @return TODO: describe
     */
    public Vec3f sub(Vec3f o) {
        return new Vec3f(x - o.x, y - o.y, z - o.z);
    }

    /**
     * TODO: document {@code scale}.
     *
     * @param s TODO: describe
     * @return TODO: describe
     */
    public Vec3f scale(float s) {
        return new Vec3f(x * s, y * s, z * s);
    }

    /**
     * TODO: document {@code lengthSquared}.
     *
     * @return TODO: describe
     */
    public float lengthSquared() {
        return x * x + y * y + z * z;
    }

    /**
     * TODO: document {@code length}.
     *
     * @return TODO: describe
     */
    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }
}
