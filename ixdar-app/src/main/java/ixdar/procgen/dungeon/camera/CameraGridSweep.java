package ixdar.procgen.dungeon.camera;

import org.joml.Vector3f;

import ixdar.procgen.dungeon.physics.AabbBox;
import ixdar.procgen.dungeon.physics.CapsuleAabbTest;
import ixdar.procgen.dungeon.physics.CapsuleShape;
import ixdar.procgen.dungeon.values.CellType;

/**
 * Casts a sphere of {@code cameraRadius} from {@code pivot} toward {@code desired}, stopping at
 * the first obstacle cell of a dense grid indexed {@code x + gridW * (z + gridD * y)} and
 * backing off by {@code padding}. The sweep hard-stops, so the result lies on the
 * pivot-to-desired line.
 */
public final class CameraGridSweep {
    public static final float NUM_0 = 0f;
    public static final float NUM_1e_6 = 1e-6f;
    public static final float NUM_1 = 1f;
    public static final float NUM_1e_4 = 1e-4f;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_32 = 32f;

    private CameraGridSweep() {
    }

    /**
     * March a sphere of {@code cameraRadius} from {@code pivot} toward {@code desired} in
     * fixed-size steps and stop the first time it overlaps an obstacle cell, then back the
     * endpoint off by {@code padding} so the camera doesn't sit flush against the wall.
     *
     * @param pivot        ray origin (typically the player's head)
     * @param desired      unobstructed target position
     * @param cameraRadius sphere radius used for the sweep
     * @param cells        3D cell grid; EMPTY cells and out-of-bounds count as obstacles
     * @param gridW        grid width in cells (X)
     * @param gridH        grid height in floors (Y)
     * @param gridD        grid depth in cells (Z)
     * @param cellSize     world-space size of a grid cell (must be &gt; 0)
     * @param padding      world-space pull-back distance applied after a hit
     * @throws IllegalArgumentException if {@code cellSize} is not strictly positive
     * @return the clipped endpoint, or {@code desired} when the sweep is fully clear
     */
    public static Vector3f sweep(Vector3f pivot, Vector3f desired, float cameraRadius,
                              CellType[] cells, int gridW, int gridH, int gridD,
                              float cellSize, float padding) {
        if (cellSize <= NUM_0) {
            throw new IllegalArgumentException("cellSize must be > 0, got " + cellSize);
        }
        Vector3f delta = desired.sub(pivot);
        float dist = delta.length();
        if (dist < NUM_1e_6) return desired;
        Vector3f dir = delta.mul(NUM_1 / dist);

        float step = Math.max(NUM_1e_4, Math.min(cameraRadius * NUM_0_5, dist / NUM_32));
        float lastClear = NUM_0;
        for (float t = step; t <= dist; t += step) {
            Vector3f p = new Vector3f(pivot.x() + dir.x() * t,
                                pivot.y() + dir.y() * t,
                                pivot.z() + dir.z() * t);
            if (overlapsObstacle(p, cameraRadius, cells, gridW, gridH, gridD, cellSize)) {
                float clipped = Math.max(NUM_0, lastClear - padding);
                return new Vector3f(pivot.x() + dir.x() * clipped,
                                 pivot.y() + dir.y() * clipped,
                                 pivot.z() + dir.z() * clipped);
            }
            lastClear = t;
        }
        // Final endpoint check (loop may stop just short of dist due to step granularity).
        if (overlapsObstacle(desired, cameraRadius, cells, gridW, gridH, gridD, cellSize)) {
            float clipped = Math.max(NUM_0, lastClear - padding);
            return new Vector3f(pivot.x() + dir.x() * clipped,
                             pivot.y() + dir.y() * clipped,
                             pivot.z() + dir.z() * clipped);
        }
        return desired;
    }

    private static boolean overlapsObstacle(Vector3f center, float radius,
                                            CellType[] cells, int gridW, int gridH, int gridD,
                                            float cellSize) {
        float offsetX = -gridW * cellSize * NUM_0_5;
        float offsetY = -gridH * cellSize * NUM_0_5;
        float offsetZ = -gridD * cellSize * NUM_0_5;
        CapsuleShape sphere = new CapsuleShape(center.x(), center.y(), center.z(), NUM_0, radius);
        int xLo = (int) Math.floor((center.x() - radius - offsetX) / cellSize);
        int xHi = (int) Math.floor((center.x() + radius - offsetX) / cellSize);
        int yLo = (int) Math.floor((center.y() - radius - offsetY) / cellSize);
        int yHi = (int) Math.floor((center.y() + radius - offsetY) / cellSize);
        int zLo = (int) Math.floor((center.z() - radius - offsetZ) / cellSize);
        int zHi = (int) Math.floor((center.z() + radius - offsetZ) / cellSize);
        for (int gy = yLo; gy <= yHi; gy++) {
            for (int gz = zLo; gz <= zHi; gz++) {
                for (int gx = xLo; gx <= xHi; gx++) {
                    if (!isObstacle(cells, gridW, gridH, gridD, gx, gy, gz)) continue;
                    AabbBox cell = new AabbBox(
                            offsetX + gx * cellSize,
                            offsetY + gy * cellSize,
                            offsetZ + gz * cellSize,
                            offsetX + (gx + 1) * cellSize,
                            offsetY + (gy + 1) * cellSize,
                            offsetZ + (gz + 1) * cellSize);
                    if (CapsuleAabbTest.intersects(sphere, cell)) return true;
                }
            }
        }
        return false;
    }

    private static boolean isObstacle(CellType[] cells, int gridW, int gridH, int gridD,
                                      int x, int y, int z) {
        if (x < 0 || x >= gridW) return true;
        if (y < 0 || y >= gridH) return true;
        if (z < 0 || z >= gridD) return true;
        return cells[x + gridW * (z + gridD * y)] == CellType.EMPTY;
    }
}
