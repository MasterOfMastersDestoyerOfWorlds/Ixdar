package ixdar.procgen.dungeon.algo;

import java.util.Random;

import ixdar.geometry.mesh.data.GeometryBundle;

/**
 * 3D analog of {@link RoomPlacer}: non-overlapping 1-cell-tall rooms at integer floor levels
 * [0, gridH) with a 1-unit buffer, flowing as points with half extents in the
 * {@link DungeonGrids#HALF_EXTENT} attribute. Exhausting {@code maxAttempts} may leave fewer
 * than {@code roomCount}.
 */
public final class RoomPlacer3D {
    public static final String X = "x";
    public static final int NUM_4 = 4;
    public static final float NUM_2 = 2f;
    public static final float NUM_0_5 = 0.5f;
    public static final int NUM_3 = 3;

    private RoomPlacer3D() {
    }

    /**
     * Insert a centered START room on the middle floor as room[0], then reject-sample rooms
     * across all floors until {@code roomCount} are placed or {@code maxAttempts} is exhausted.
     *
     * <p>The START room is even-sized so it straddles the world origin once
     * {@link GridToMesh3D} recenters the mesh, making (0, 0, 0) a valid spawn point.
     *
     * @param seed        PRNG seed
     * @param gridW       grid width (X) in cells
     * @param gridH       grid height (Y) in floors — typical 5 per vazgriz
     * @param gridD       grid depth (Z) in cells
     * @param roomCount   target number of rooms
     * @param minSize     minimum horizontal edge length (X and Z) inclusive
     * @param maxSize     maximum horizontal edge length (X and Z) inclusive
     * @param maxAttempts cap on placement attempts
     * @throws IllegalArgumentException if the size range is invalid, the grid
     *                                  horizontal extent is smaller than
     *                                  {@code maxSize}, or {@code gridH} is less
     *                                  than 1
     * @return rooms as a point bundle (vertex count may be less than {@code roomCount})
     */
    public static GeometryBundle place(long seed,
            int gridW, int gridH, int gridD,
            int roomCount,
            int minSize, int maxSize,
            int maxAttempts) {
        if (minSize <= 0 || maxSize < minSize) {
            throw new IllegalArgumentException("invalid size range: [" + minSize + "," + maxSize + "]");
        }
        if (gridW < maxSize || gridD < maxSize) {
            throw new IllegalArgumentException(
                    "grid " + gridW + X + gridH + X + gridD + " too small for maxSize " + maxSize);
        }
        if (gridH < 1) {
            throw new IllegalArgumentException("gridH must be at least 1");
        }

        float[] centers = new float[roomCount * NUM_3];
        float[] halfExtents = new float[roomCount * NUM_3];
        int placed = 0;

        int startSize = Math.max(minSize, Math.min(maxSize, NUM_4));
        if (startSize % 2 != 0) {
            startSize = Math.max(minSize, startSize - 1);
        }
        if (startSize > maxSize) {
            startSize = maxSize;
        }
        int startX = Math.max(0, gridW / 2 - startSize / 2);
        int startZ = Math.max(0, gridD / 2 - startSize / 2);
        int startFloor = gridH / 2;
        centers[0] = startX + startSize / NUM_2;
        centers[1] = startFloor + NUM_0_5;
        centers[2] = startZ + startSize / NUM_2;
        halfExtents[0] = startSize / NUM_2;
        halfExtents[1] = NUM_0_5;
        halfExtents[2] = startSize / NUM_2;
        placed = 1;

        Random rng = new Random(seed);
        int attempts = 0;
        while (placed < roomCount && attempts < maxAttempts) {
            attempts++;
            int w = minSize + rng.nextInt(maxSize - minSize + 1);
            int d = minSize + rng.nextInt(maxSize - minSize + 1);
            int x = rng.nextInt(gridW - w + 1);
            int floor = rng.nextInt(gridH);
            int z = rng.nextInt(gridD - d + 1);
            float cx = x + w / NUM_2;
            float cy = floor + NUM_0_5;
            float cz = z + d / NUM_2;
            float hx = w / NUM_2;
            float hy = NUM_0_5;
            float hz = d / NUM_2;
            if (!collidesAny(centers, halfExtents, placed, cx, cy, cz, hx, hy, hz)) {
                centers[placed * NUM_3] = cx;
                centers[placed * NUM_3 + 1] = cy;
                centers[placed * NUM_3 + 2] = cz;
                halfExtents[placed * NUM_3] = hx;
                halfExtents[placed * NUM_3 + 1] = hy;
                halfExtents[placed * NUM_3 + 2] = hz;
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
                                       float cx, float cy, float cz,
                                       float hx, float hy, float hz) {
        float buf = NUM_0_5;
        for (int i = 0; i < placed; i++) {
            float ox = centers[i * NUM_3];
            float oy = centers[i * NUM_3 + 1];
            float oz = centers[i * NUM_3 + 2];
            float ohx = halfExtents[i * NUM_3];
            float ohy = halfExtents[i * NUM_3 + 1];
            float ohz = halfExtents[i * NUM_3 + 2];
            boolean collides = ox - ohx - buf < cx + hx + buf
                    && ox + ohx + buf > cx - hx - buf
                    && oy - ohy - buf < cy + hy + buf
                    && oy + ohy + buf > cy - hy - buf
                    && oz - ohz - buf < cz + hz + buf
                    && oz + ohz + buf > cz - hz - buf;
            if (collides) {
                return true;
            }
        }
        return false;
    }
}
