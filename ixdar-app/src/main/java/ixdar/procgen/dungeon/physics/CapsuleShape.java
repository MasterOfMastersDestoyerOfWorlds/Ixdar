package ixdar.procgen.dungeon.physics;

/**
 * Vertical capsule (Y-axis) defined by its center, the half-height of the cylindrical body,
 * and the radius of the hemispherical endcaps and cylinder.
 *
 * <p>Total height of the capsule = {@code 2 * halfHeight + 2 * radius} (cylinder body plus two
 * hemispheres). Setting {@code halfHeight = 0} collapses the capsule to a sphere of {@code radius}.
 *
 * <p>The capsule's central segment runs from
 * {@code (centerX, centerY - halfHeight, centerZ)} (bottom hemisphere center) to
 * {@code (centerX, centerY + halfHeight, centerZ)} (top hemisphere center).
 */
public record CapsuleShape(float centerX, float centerY, float centerZ,
                           float halfHeight, float radius) {

    /**
     * TODO: document {@code CapsuleShape}.
     *
     * @throws IllegalArgumentException TODO: describe
     */
    public CapsuleShape {
        if (halfHeight < 0f) {
            throw new IllegalArgumentException("halfHeight must be >= 0, got " + halfHeight);
        }
        if (radius <= 0f) {
            throw new IllegalArgumentException("radius must be > 0, got " + radius);
        }
    }

    /** Returns a copy of this capsule with a new center, preserving halfHeight and radius. */
    public CapsuleShape atCenter(Vec3f c) {
        return new CapsuleShape(c.x(), c.y(), c.z(), halfHeight, radius);
    }

    /**
     * TODO: document {@code center}.
     *
     * @return TODO: describe
     */
    public Vec3f center() {
        return new Vec3f(centerX, centerY, centerZ);
    }

    /**
     * TODO: document {@code segmentMinY}.
     *
     * @return TODO: describe
     */
    public float segmentMinY() { return centerY - halfHeight; }
    /**
     * TODO: document {@code segmentMaxY}.
     *
     * @return TODO: describe
     */
    public float segmentMaxY() { return centerY + halfHeight; }

    /** Total bounding-sphere radius around the capsule center: halfHeight + radius. */
    public float boundingSphereRadius() {
        return halfHeight + radius;
    }
}
