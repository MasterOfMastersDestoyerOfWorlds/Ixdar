package ixdar.procgen.dungeon.physics;

/**
 * Static collision queries between a vertical {@link CapsuleShape} and an {@link AabbBox}.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Find the closest point P on the capsule's central vertical segment to the AABB.</li>
 *   <li>Find the closest point Q on the AABB to P (just clamp P into the AABB).</li>
 *   <li>If {@code dist(P, Q) < radius} the capsule intersects the AABB.</li>
 *   <li>The minimum-translation vector (MTV) pushes the capsule center along {@code P - Q} by
 *       {@code radius - dist} so the new distance is exactly {@code radius} (flush, no overlap).</li>
 *   <li>Edge case: if P lies inside the AABB (the capsule's segment passes through the box),
 *       there's no well-defined direction from {@code P - Q}; pick the AABB face nearest to P
 *       and push out along its outward normal by {@code distToFace + radius}.</li>
 * </ol>
 */
public final class CapsuleAabbTest {

    private CapsuleAabbTest() {
    }

    /** True iff the capsule strictly overlaps the AABB (touching at exactly radius is NOT overlap). */
    public static boolean intersects(CapsuleShape c, AabbBox a) {
        float[] pq = closestPair(c, a);
        float dx = pq[0] - pq[3];
        float dy = pq[1] - pq[4];
        float dz = pq[2] - pq[5];
        float distSq = dx * dx + dy * dy + dz * dz;
        return distSq < c.radius() * c.radius();
    }

    /**
     * Minimum vector to add to the capsule's center so it no longer overlaps the AABB. Returns
     * {@link Vec3f#ZERO} when the two are already separate.
     */
    public static Vec3f penetration(CapsuleShape c, AabbBox a) {
        float[] pq = closestPair(c, a);
        float px = pq[0], py = pq[1], pz = pq[2];
        float qx = pq[3], qy = pq[4], qz = pq[5];
        float dx = px - qx, dy = py - qy, dz = pz - qz;
        float distSq = dx * dx + dy * dy + dz * dz;
        float r = c.radius();
        if (distSq >= r * r) return Vec3f.ZERO;

        // P outside AABB (Q on its surface): standard sphere-vs-AABB push along (P - Q).
        if (distSq > 1e-12f) {
            float dist = (float) Math.sqrt(distSq);
            float scale = (r - dist) / dist;
            return new Vec3f(dx * scale, dy * scale, dz * scale);
        }

        // P is inside the AABB. Pick the face nearest P and push outward through it.
        float dxMin = px - a.minX();
        float dxMax = a.maxX() - px;
        float dyMin = py - a.minY();
        float dyMax = a.maxY() - py;
        float dzMin = pz - a.minZ();
        float dzMax = a.maxZ() - pz;
        // Smallest = nearest face; ties broken in axis order X, Y, Z.
        float min = dxMin;
        int face = 0; // 0:-X, 1:+X, 2:-Y, 3:+Y, 4:-Z, 5:+Z
        if (dxMax < min) { min = dxMax; face = 1; }
        if (dyMin < min) { min = dyMin; face = 2; }
        if (dyMax < min) { min = dyMax; face = 3; }
        if (dzMin < min) { min = dzMin; face = 4; }
        if (dzMax < min) { min = dzMax; face = 5; }
        float push = min + r;
        return switch (face) {
            case 0 -> new Vec3f(-push, 0f, 0f);
            case 1 -> new Vec3f(push, 0f, 0f);
            case 2 -> new Vec3f(0f, -push, 0f);
            case 3 -> new Vec3f(0f, push, 0f);
            case 4 -> new Vec3f(0f, 0f, -push);
            default -> new Vec3f(0f, 0f, push);
        };
    }

    /**
     * Returns {@code {Px, Py, Pz, Qx, Qy, Qz}} — the closest pair (P on capsule axis, Q on AABB).
     */
    private static float[] closestPair(CapsuleShape c, AabbBox a) {
        // P on capsule's vertical axis: X and Z fixed, Y chosen to minimize distance to AABB Y range.
        float aabbMidY = (a.minY() + a.maxY()) * 0.5f;
        float py = clamp(aabbMidY, c.segmentMinY(), c.segmentMaxY());
        float px = c.centerX();
        float pz = c.centerZ();
        // Q on AABB closest to P: just clamp.
        float qx = clamp(px, a.minX(), a.maxX());
        float qy = clamp(py, a.minY(), a.maxY());
        float qz = clamp(pz, a.minZ(), a.maxZ());
        return new float[] { px, py, pz, qx, qy, qz };
    }

    private static float clamp(float v, float lo, float hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
