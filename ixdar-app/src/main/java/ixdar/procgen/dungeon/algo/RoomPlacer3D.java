package ixdar.procgen.dungeon.algo;

import java.util.Random;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.PackedPoints;

/**
 * 3D analog of {@link RoomPlacer}: non-overlapping 1-cell-tall rooms at integer floor levels
 * [0, gridH) with a 1-unit buffer, flowing as points with half extents in the
 * {@link DungeonGrids#HALF_EXTENT} attribute. Exhausting {@code maxAttempts} may leave fewer
 * than {@code roomCount}.
 */
public final class RoomPlacer3D {
    public static final String X = "x";
    public static final int DEFAULT_START_ROOM_SIZE = 4;

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

        PackedPoints centers = new PackedPoints();
        PackedPoints halfExtents = new PackedPoints();

        int startSize = Math.max(minSize, Math.min(maxSize, DEFAULT_START_ROOM_SIZE));
        if (startSize % 2 != 0) {
            startSize = Math.max(minSize, startSize - 1);
        }
        if (startSize > maxSize) {
            startSize = maxSize;
        }
        int startX = Math.max(0, gridW / 2 - startSize / 2);
        int startZ = Math.max(0, gridD / 2 - startSize / 2);
        int startFloor = gridH / 2;
        centers.append(startX + startSize / 2f, startFloor + 0.5f, startZ + startSize / 2f);
        halfExtents.append(startSize / 2f, 0.5f, startSize / 2f);

        Random rng = new Random(seed);
        int attempts = 0;
        while (centers.size() < roomCount && attempts < maxAttempts) {
            attempts++;
            int w = minSize + rng.nextInt(maxSize - minSize + 1);
            int d = minSize + rng.nextInt(maxSize - minSize + 1);
            int x = rng.nextInt(gridW - w + 1);
            int floor = rng.nextInt(gridH);
            int z = rng.nextInt(gridD - d + 1);
            Vector3f centre = new Vector3f(x + w / 2f, floor + 0.5f, z + d / 2f);
            Vector3f half = new Vector3f(w / 2f, 0.5f, d / 2f);
            if (!collidesAny(centers, halfExtents, centre, half)) {
                centers.append(centre);
                halfExtents.append(half);
            }
        }
        return DungeonGrids.pointBundle(centers, halfExtents);
    }

    /**
     * Whether a box comes within the 1-unit buffer of any placed box.
     *
     * @param centers     placed box centres
     * @param halfExtents placed box half extents, one per centre
     * @param centre      the candidate's centre
     * @param half        the candidate's half extents
     * @return true when the candidate overlaps a placed box once both grow by half the buffer
     */
    public static boolean collidesAny(PackedPoints centers, PackedPoints halfExtents, Vector3f centre,
            Vector3f half) {
        float buf = 0.5f;
        for (int i = 0; i < centers.size(); i++) {
            boolean collides = true;
            for (int axis = 0; axis < PackedPoints.XYZ; axis++) {
                float other = centers.get(i, axis);
                float otherHalf = halfExtents.get(i, axis);
                collides &= other - otherHalf - buf < centre.get(axis) + half.get(axis) + buf
                        && other + otherHalf + buf > centre.get(axis) - half.get(axis) - buf;
            }
            if (collides) {
                return true;
            }
        }
        return false;
    }
}
