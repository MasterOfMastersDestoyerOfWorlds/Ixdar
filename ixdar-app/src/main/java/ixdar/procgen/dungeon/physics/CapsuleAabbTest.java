package ixdar.procgen.dungeon.physics;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.PackedPoints;

/**
 * Static collision queries between a vertical {@link CapsuleShape} and an {@link AabbBox},
 * reporting a minimum-translation vector that separates the two flush.
 *
 * <p>When the capsule's central segment passes through the box the separation direction is
 * degenerate, and the MTV instead points out along the nearest face normal.
 */
public final class CapsuleAabbTest {
    public static final float DEGENERATE_THRESHOLD = 1e-12f;

    private CapsuleAabbTest() {
    }

    /**
     * True iff the capsule strictly overlaps the AABB (touching at exactly radius is NOT overlap).
     *
     * @param c capsule under test
     * @param a static axis-aligned bounding box
     * @return {@code true} if the squared distance from the capsule's central segment to {@code a}
     *     is strictly less than {@code c.radius() * c.radius()}
     */
    public static boolean intersects(CapsuleShape c, AabbBox a) {
        PackedPoints pq = closestPair(c, a);
        return pq.at(0).distanceSquared(pq.at(1)) < c.radius() * c.radius();
    }

    /**
     * Minimum vector to add to the capsule's center so it no longer overlaps the AABB. Returns
     * {@link Vector3f#ZERO} when the two are already separate.
     *
     * @param c capsule under test
     * @param a static axis-aligned bounding box
     * @return minimum-translation vector that resolves penetration, or {@link Vector3f#ZERO} when
     *     {@code c} and {@code a} are not overlapping
     */
    public static Vector3f penetration(CapsuleShape c, AabbBox a) {
        PackedPoints pq = closestPair(c, a);
        Vector3f p = pq.at(0);
        float px = p.x, py = p.y, pz = p.z;
        Vector3f apart = pq.at(0).sub(pq.at(1));
        float distSq = apart.lengthSquared();
        float r = c.radius();
        if (distSq >= r * r) return new Vector3f(0f, 0f, 0f);

        if (distSq > DEGENERATE_THRESHOLD) {
            float dist = (float) Math.sqrt(distSq);
            return apart.mul((r - dist) / dist);
        }

        float dxMin = px - a.minX();
        float dxMax = a.maxX() - px;
        float dyMin = py - a.minY();
        float dyMax = a.maxY() - py;
        float dzMin = pz - a.minZ();
        float dzMax = a.maxZ() - pz;
        float min = dxMin;
        int face = 0; // 0:-X, 1:+X, 2:-Y, 3:+Y, 4:-Z, 5:+Z
        if (dxMax < min) { min = dxMax; face = 1; }
        if (dyMin < min) { min = dyMin; face = 2; }
        if (dyMax < min) { min = dyMax; face = 3; }
        if (dzMin < min) { min = dzMin; face = 4; }
        if (dzMax < min) { min = dzMax; face = 5; }
        float push = min + r;
        return switch (face) {
            case 0 -> new Vector3f(-push, 0f, 0f);
            case 1 -> new Vector3f(push, 0f, 0f);
            case 2 -> new Vector3f(0f, -push, 0f);
            case 3 -> new Vector3f(0f, push, 0f);
            case 4 -> new Vector3f(0f, 0f, -push);
            default -> new Vector3f(0f, 0f, push);
        };
    }

    /**
     * The closest pair: P on the capsule axis, Q on the AABB.
     *
     * @param c capsule whose central vertical segment supplies P
     * @param a AABB whose surface (or interior) supplies Q
     * @return P then Q
     */
    private static PackedPoints closestPair(CapsuleShape c, AabbBox a) {
        float aabbMidY = (a.minY() + a.maxY()) * 0.5f;
        float py = clamp(aabbMidY, c.segmentMinY(), c.segmentMaxY());
        float px = c.centerX();
        float pz = c.centerZ();
        PackedPoints pair = new PackedPoints(2);
        pair.set(0, px, py, pz);
        pair.set(1, clamp(px, a.minX(), a.maxX()), clamp(py, a.minY(), a.maxY()), clamp(pz, a.minZ(), a.maxZ()));
        return pair;
    }

    private static float clamp(float v, float lo, float hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
