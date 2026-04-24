package ixdar.procgen.dungeon.algo;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import ixdar.procgen.dungeon.values.RoomListValue;
import ixdar.procgen.dungeon.values.RoomListValue.Room;

/**
 * Deterministic seeded random placement of non-overlapping rooms on an integer grid, with a
 * 1-unit buffer between rooms to prevent adjacency. Stage 1 of the vazgriz dungeon pipeline.
 *
 * <p>Placements are attempted up to {@code maxAttempts} times; each attempt consumes three
 * draws from the RNG (width, height, candidate position). If a candidate collides with any
 * already-placed room (including buffer), it is discarded and the loop continues. The result
 * may contain fewer rooms than {@code roomCount} if {@code maxAttempts} is exceeded — callers
 * choosing low attempt caps should check the returned list size.
 */
public final class RoomPlacer {

    private RoomPlacer() {
    }

    /**
     * @param seed          PRNG seed for reproducibility
     * @param gridW         grid width in units (rooms are placed so their AABBs fit in [0, gridW])
     * @param gridH         grid height in units
     * @param roomCount     target number of rooms
     * @param minSize       minimum room edge length in units (inclusive)
     * @param maxSize       maximum room edge length in units (inclusive)
     * @param maxAttempts   cap on total placement attempts; each attempt consumes RNG draws
     */
    public static RoomListValue place(long seed, int gridW, int gridH,
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
        List<Room> placed = new ArrayList<>(roomCount);
        int attempts = 0;
        while (placed.size() < roomCount && attempts < maxAttempts) {
            attempts++;
            int w = minSize + rng.nextInt(maxSize - minSize + 1);
            int h = minSize + rng.nextInt(maxSize - minSize + 1);
            int x = rng.nextInt(gridW - w + 1);
            int y = rng.nextInt(gridH - h + 1);
            Room candidate = new Room(
                    placed.size(),
                    x + w / 2f, y + h / 2f,
                    w / 2f, h / 2f);
            if (!collidesAny(candidate, placed)) {
                placed.add(candidate);
            }
        }
        return new RoomListValue(placed);
    }

    /** Two rooms collide if their AABBs (inflated by 0.5 units on each side) overlap. */
    static boolean collidesWithBuffer(Room a, Room b) {
        float buf = 0.5f;
        return a.minX() - buf < b.maxX() + buf
                && a.maxX() + buf > b.minX() - buf
                && a.minY() - buf < b.maxY() + buf
                && a.maxY() + buf > b.minY() - buf;
    }

    private static boolean collidesAny(Room candidate, List<Room> placed) {
        for (Room r : placed) {
            if (collidesWithBuffer(r, candidate)) return true;
        }
        return false;
    }
}
