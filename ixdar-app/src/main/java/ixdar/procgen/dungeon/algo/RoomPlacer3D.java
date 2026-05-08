package ixdar.procgen.dungeon.algo;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import ixdar.procgen.dungeon.values.RoomListValue3D;
import ixdar.procgen.dungeon.values.RoomListValue3D.Room;

/**
 * 3D analog of {@link RoomPlacer}. Places non-overlapping axis-aligned 3D rooms on an integer
 * grid with a 1-unit buffer between rooms. Rooms are 1 cell tall by default (single floor) and
 * placed at integer floor levels [0, gridH).
 *
 * <p>Per-attempt RNG draws: width, depth, x, y (floor), z. Six draws per attempt. The result
 * may contain fewer than {@code roomCount} rooms if {@code maxAttempts} is exhausted.
 */
public final class RoomPlacer3D {
    public static final String X = "x";
    public static final int NUM_4 = 4;
    public static final float NUM_2 = 2f;
    public static final float NUM_0_5 = 0.5f;

    private RoomPlacer3D() {
    }

    /**
     * TODO: document.
     *
     * @param seed         PRNG seed
     * @param gridW        grid width (X) in cells
     * @param gridH        grid height (Y) in floors — typical 5 per vazgriz
     * @param gridD        grid depth (Z) in cells
     * @param roomCount    target number of rooms
     * @param minSize      minimum horizontal edge length (X and Z) inclusive
     * @param maxSize      maximum horizontal edge length (X and Z) inclusive
     * @param maxAttempts  cap on placement attempts
     * @throws IllegalArgumentException TODO: describe
     * @return TODO: describe
     */
    public static RoomListValue3D place(long seed,
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

        List<Room> placed = new ArrayList<>(roomCount);
        // Always insert a START room as room[0] at the grid center on the middle floor. Size
        // is clamped to the requested [minSize, maxSize] but defaults to 4. After GridToMesh3D
        // centers the mesh at world origin, an even-sized centered start room straddles the
        // origin precisely — so the player can spawn at (0, 0, 0) and find walkable space.
        int startSize = Math.max(minSize, Math.min(maxSize, NUM_4));
        if (startSize % 2 != 0) startSize = Math.max(minSize, startSize - 1); // prefer even
        if (startSize > maxSize) startSize = maxSize;
        int startX = Math.max(0, gridW / 2 - startSize / 2);
        int startZ = Math.max(0, gridD / 2 - startSize / 2);
        int startFloor = gridH / 2;
        placed.add(new Room(
                0,
                startX + startSize / NUM_2, startFloor + NUM_0_5, startZ + startSize / NUM_2,
                startSize / NUM_2, NUM_0_5, startSize / NUM_2));

        Random rng = new Random(seed);
        int attempts = 0;
        while (placed.size() < roomCount && attempts < maxAttempts) {
            attempts++;
            int w = minSize + rng.nextInt(maxSize - minSize + 1);
            int d = minSize + rng.nextInt(maxSize - minSize + 1);
            int x = rng.nextInt(gridW - w + 1);
            int floor = rng.nextInt(gridH);
            int z = rng.nextInt(gridD - d + 1);
            Room candidate = new Room(
                    placed.size(),
                    x + w / NUM_2, floor + NUM_0_5, z + d / NUM_2,
                    w / NUM_2, NUM_0_5, d / NUM_2);
            if (!collidesAny(candidate, placed)) {
                placed.add(candidate);
            }
        }
        return new RoomListValue3D(placed);
    }

    /**
     * Two rooms collide if their AABBs (inflated by 0.5 on each axis) overlap.
     *
     * @param a TODO: describe
     * @param b TODO: describe
     * @return TODO: describe
     */
    static boolean collidesWithBuffer(Room a, Room b) {
        float buf = NUM_0_5;
        return a.minX() - buf < b.maxX() + buf
                && a.maxX() + buf > b.minX() - buf
                && a.minY() - buf < b.maxY() + buf
                && a.maxY() + buf > b.minY() - buf
                && a.minZ() - buf < b.maxZ() + buf
                && a.maxZ() + buf > b.minZ() - buf;
    }

    private static boolean collidesAny(Room candidate, List<Room> placed) {
        for (Room r : placed) {
            if (collidesWithBuffer(r, candidate)) return true;
        }
        return false;
    }
}
