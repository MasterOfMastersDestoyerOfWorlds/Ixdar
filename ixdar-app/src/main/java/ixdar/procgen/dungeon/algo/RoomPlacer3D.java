package ixdar.procgen.dungeon.algo;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.netlib.util.booleanW;

import ixdar.procgen.dungeon.values.RoomListValue3D;
import ixdar.procgen.dungeon.values.RoomListValue3D.Room;

/**
 * 3D analog of {@link RoomPlacer}. Places non-overlapping axis-aligned 3D rooms on an integer
 * grid with a 1-unit buffer between rooms. Rooms are 1 cell tall by default and sit at integer
 * floor levels [0, gridH).
 *
 * <p>The result may contain fewer than {@code roomCount} rooms when {@code maxAttempts} is
 * exhausted.
 */
public final class RoomPlacer3D {
    public static final String X = "x";
    public static final int NUM_4 = 4;
    public static final float NUM_2 = 2f;
    public static final float NUM_0_5 = 0.5f;

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
     * @return the placed rooms (size may be less than {@code roomCount} on attempt
     *         exhaustion)
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
            boolean collidesAny = false;
            for (Room r : placed) {
                float buf = NUM_0_5;
                boolean collides = r.minX() - buf < candidate.maxX() + buf
                        && r.maxX() + buf > candidate.minX() - buf
                        && r.minY() - buf < candidate.maxY() + buf
                        && r.maxY() + buf > candidate.minY() - buf
                        && r.minZ() - buf < candidate.maxZ() + buf
                        && r.maxZ() + buf > candidate.minZ() - buf;
                if (collides) {
                    collidesAny = true;
                    break;
                }
            }
            if (!collidesAny) {
                placed.add(candidate);
            }
        }
        return new RoomListValue3D(placed);
    }
}
