package ixdar.procgen.dungeon.physics;

import org.joml.Vector3f;

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
     * Validates capsule dimensions.
     *
     * @throws IllegalArgumentException if {@code halfHeight} is negative or {@code radius} is
     *     not strictly positive
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
    public CapsuleShape atCenter(Vector3f c) {
        return new CapsuleShape(c.x(), c.y(), c.z(), halfHeight, radius);
    }

    /**
     * Capsule center as a {@link Vector3f}.
     *
     * @return new vector with components {@code (centerX, centerY, centerZ)}
     */
    public Vector3f center() {
        return new Vector3f(centerX, centerY, centerZ);
    }

    /**
     * Bottom endpoint of the capsule's central vertical segment (center of the lower hemisphere).
     *
     * @return {@code centerY - halfHeight}
     */
    public float segmentMinY() { return centerY - halfHeight; }
    /**
     * Top endpoint of the capsule's central vertical segment (center of the upper hemisphere).
     *
     * @return {@code centerY + halfHeight}
     */
    public float segmentMaxY() { return centerY + halfHeight; }

    /** Total bounding-sphere radius around the capsule center: halfHeight + radius. */
    public float boundingSphereRadius() {
        return halfHeight + radius;
    }
}
