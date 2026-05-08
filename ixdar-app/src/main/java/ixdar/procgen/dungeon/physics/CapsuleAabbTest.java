package ixdar.procgen.dungeon.physics;

import org.joml.Vector3f;

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
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;
    public static final int NUM_5 = 5;
    public static final float NUM_1e_12 = 1e-12f;
    public static final float NUM_0 = 0f;
    public static final float NUM_0_5 = 0.5f;

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
        float[] pq = closestPair(c, a);
        float dx = pq[0] - pq[NUM_3];
        float dy = pq[1] - pq[NUM_4];
        float dz = pq[2] - pq[NUM_5];
        float distSq = dx * dx + dy * dy + dz * dz;
        return distSq < c.radius() * c.radius();
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
        float[] pq = closestPair(c, a);
        float px = pq[0], py = pq[1], pz = pq[2];
        float qx = pq[NUM_3], qy = pq[NUM_4], qz = pq[NUM_5];
        float dx = px - qx, dy = py - qy, dz = pz - qz;
        float distSq = dx * dx + dy * dy + dz * dz;
        float r = c.radius();
        if (distSq >= r * r) return new Vector3f(0f, 0f, 0f);

        // P outside AABB (Q on its surface): standard sphere-vs-AABB push along (P - Q).
        if (distSq > NUM_1e_12) {
            float dist = (float) Math.sqrt(distSq);
            float scale = (r - dist) / dist;
            return new Vector3f(dx * scale, dy * scale, dz * scale);
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
        if (dyMax < min) { min = dyMax; face = NUM_3; }
        if (dzMin < min) { min = dzMin; face = NUM_4; }
        if (dzMax < min) { min = dzMax; face = NUM_5; }
        float push = min + r;
        return switch (face) {
            case 0 -> new Vector3f(-push, NUM_0, NUM_0);
            case 1 -> new Vector3f(push, NUM_0, NUM_0);
            case 2 -> new Vector3f(NUM_0, -push, NUM_0);
            case NUM_3 -> new Vector3f(NUM_0, push, NUM_0);
            case NUM_4 -> new Vector3f(NUM_0, NUM_0, -push);
            default -> new Vector3f(NUM_0, NUM_0, push);
        };
    }

    /**
     * Returns {@code {Px, Py, Pz, Qx, Qy, Qz}} — the closest pair (P on capsule axis, Q on AABB).
     *
     * @param c capsule whose central vertical segment supplies P
     * @param a AABB whose surface (or interior) supplies Q
     * @return packed 6-element array {@code [Px, Py, Pz, Qx, Qy, Qz]}
     */
    private static float[] closestPair(CapsuleShape c, AabbBox a) {
        // P on capsule's vertical axis: X and Z fixed, Y chosen to minimize distance to AABB Y range.
        float aabbMidY = (a.minY() + a.maxY()) * NUM_0_5;
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
