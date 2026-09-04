package ixdar.procgen.dungeon.algo;

import java.util.Random;

import ixdar.geometry.mesh.data.GeometryBundle;

/**
 * Deterministic seeded random placement of non-overlapping rooms on an integer grid with a
 * 1-unit buffer. Rooms flow as points at {@code (centerX, centerY, 0)} with half extents in
 * the {@link DungeonGrids#HALF_EXTENT} attribute; exhausting {@code maxAttempts} may leave
 * fewer than {@code roomCount}.
 */
public final class RoomPlacer {
    public static final float NUM_2 = 2f;
    public static final float NUM_0_5 = 0.5f;
    public static final int NUM_3 = 3;

    private RoomPlacer() {
    }

    /**
     * Reject-sample axis-aligned rooms within the grid until {@code roomCount} have been placed
     * without colliding (with a 1-unit buffer) or {@code maxAttempts} is exhausted.
     *
     * @param seed          PRNG seed for reproducibility
     * @param gridW         grid width in units (rooms are placed so their AABBs fit in [0, gridW])
     * @param gridH         grid height in units
     * @param roomCount     target number of rooms
     * @param minSize       minimum room edge length in units (inclusive)
     * @param maxSize       maximum room edge length in units (inclusive)
     * @param maxAttempts   cap on total placement attempts; each attempt consumes RNG draws
     * @throws IllegalArgumentException if the size range is invalid or the grid is smaller than {@code maxSize}
     * @return rooms as a point bundle (vertex count may be less than {@code roomCount})
     */
    public static GeometryBundle place(long seed, int gridW, int gridH,
                                       int roomCount, int minSize, int maxSize,
                                       int maxAttempts) {
        if (minSize <= 0 || maxSize < minSize) {
            throw new IllegalArgumentException("invalid size range: [" + minSize + "," + maxSize + "]");
        }
        if (gridW < maxSize || gridH < maxSize) {
            throw new IllegalArgumentException(
                    "grid " + gridW + "x" + gridH + " too small for maxSize " + maxSize);
        }

        Random rng = new Random(seed);
        float[] centers = new float[roomCount * NUM_3];
        float[] halfExtents = new float[roomCount * NUM_3];
        int placed = 0;
        int attempts = 0;
        while (placed < roomCount && attempts < maxAttempts) {
            attempts++;
            int w = minSize + rng.nextInt(maxSize - minSize + 1);
            int h = minSize + rng.nextInt(maxSize - minSize + 1);
            int x = rng.nextInt(gridW - w + 1);
            int y = rng.nextInt(gridH - h + 1);
            float cx = x + w / NUM_2;
            float cy = y + h / NUM_2;
            float hx = w / NUM_2;
            float hy = h / NUM_2;
            if (!collidesAny(centers, halfExtents, placed, cx, cy, hx, hy)) {
                centers[placed * NUM_3] = cx;
                centers[placed * NUM_3 + 1] = cy;
                halfExtents[placed * NUM_3] = hx;
                halfExtents[placed * NUM_3 + 1] = hy;
                placed++;
            }
        }
        int len = placed * NUM_3;
        float[] c = new float[len];
        float[] he = new float[len];
        System.arraycopy(centers, 0, c, 0, len);
        System.arraycopy(halfExtents, 0, he, 0, len);
        return DungeonGrids.pointBundle(c, he);
    }

    private static boolean collidesAny(float[] centers, float[] halfExtents, int placed,
                                       float cx, float cy, float hx, float hy) {
        float buf = NUM_0_5;
        for (int i = 0; i < placed; i++) {
            float ox = centers[i * NUM_3];
            float oy = centers[i * NUM_3 + 1];
            float ohx = halfExtents[i * NUM_3];
            float ohy = halfExtents[i * NUM_3 + 1];
            boolean collides = ox - ohx - buf < cx + hx + buf
                    && ox + ohx + buf > cx - hx - buf
                    && oy - ohy - buf < cy + hy + buf
                    && oy + ohy + buf > cy - hy - buf;
            if (collides) {
                return true;
            }
        }
        return false;
    }
}
