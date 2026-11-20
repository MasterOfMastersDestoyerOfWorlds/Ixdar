package ixdar.procgen.dungeon.algo;

import java.util.Random;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.PackedPoints;

/**
 * Deterministic seeded random placement of non-overlapping rooms on an integer grid with a
 * 1-unit buffer. Rooms flow as points at {@code (centerX, centerY, 0)} with half extents in
 * the {@link DungeonGrids#HALF_EXTENT} attribute; exhausting {@code maxAttempts} may leave
 * fewer than {@code roomCount}.
 */
public final class RoomPlacer {
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
        PackedPoints centers = new PackedPoints();
        PackedPoints halfExtents = new PackedPoints();
        int attempts = 0;
        while (centers.size() < roomCount && attempts < maxAttempts) {
            attempts++;
            int w = minSize + rng.nextInt(maxSize - minSize + 1);
            int h = minSize + rng.nextInt(maxSize - minSize + 1);
            int x = rng.nextInt(gridW - w + 1);
            int y = rng.nextInt(gridH - h + 1);
            Vector3f centre = new Vector3f(x + w / 2f, y + h / 2f, 0f);
            Vector3f half = new Vector3f(w / 2f, h / 2f, 0f);
            if (!RoomPlacer3D.collidesAny(centers, halfExtents, centre, half)) {
                centers.append(centre);
                halfExtents.append(half);
            }
        }
        return DungeonGrids.pointBundle(centers, halfExtents);
    }
}
