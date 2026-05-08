package ixdar.procgen.dungeon.camera;

import ixdar.procgen.dungeon.physics.AabbBox;
import ixdar.procgen.dungeon.physics.CapsuleAabbTest;
import ixdar.procgen.dungeon.physics.CapsuleShape;
import ixdar.procgen.dungeon.physics.Vec3f;
import ixdar.procgen.dungeon.values.CellType;
import ixdar.procgen.dungeon.values.TileGridValue3D;

/**
 * Casts a sphere of {@code cameraRadius} from {@code pivot} toward {@code desired} and stops
 * when the sphere first overlaps an obstacle cell. Returns the clipped endpoint, biased back
 * toward the pivot by {@code padding} so the camera doesn't sit exactly against the wall.
 *
 * <p>This is intentionally a hard-stop sweep, not a slide: third-person convention is to pull
 * the camera in along the line of sight rather than let it skate sideways into adjacent cells.
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
     * TODO: document {@code sweep}.
     *
     * @param pivot TODO: describe
     * @param desired TODO: describe
     * @param cameraRadius TODO: describe
     * @param grid TODO: describe
     * @param cellSize TODO: describe
     * @param padding TODO: describe
     * @throws IllegalArgumentException TODO: describe
     * @return TODO: describe
     */
    public static Vec3f sweep(Vec3f pivot, Vec3f desired, float cameraRadius,
                              TileGridValue3D grid, float cellSize, float padding) {
        if (cellSize <= NUM_0) {
            throw new IllegalArgumentException("cellSize must be > 0, got " + cellSize);
        }
        Vec3f delta = desired.sub(pivot);
        float dist = delta.length();
        if (dist < NUM_1e_6) return desired;
        Vec3f dir = delta.scale(NUM_1 / dist);

        float step = Math.max(NUM_1e_4, Math.min(cameraRadius * NUM_0_5, dist / NUM_32));
        float lastClear = NUM_0;
        for (float t = step; t <= dist; t += step) {
            Vec3f p = new Vec3f(pivot.x() + dir.x() * t,
                                pivot.y() + dir.y() * t,
                                pivot.z() + dir.z() * t);
            if (overlapsObstacle(p, cameraRadius, grid, cellSize)) {
                float clipped = Math.max(NUM_0, lastClear - padding);
                return new Vec3f(pivot.x() + dir.x() * clipped,
                                 pivot.y() + dir.y() * clipped,
                                 pivot.z() + dir.z() * clipped);
            }
            lastClear = t;
        }
        // Final endpoint check (loop may stop just short of dist due to step granularity).
        if (overlapsObstacle(desired, cameraRadius, grid, cellSize)) {
            float clipped = Math.max(NUM_0, lastClear - padding);
            return new Vec3f(pivot.x() + dir.x() * clipped,
                             pivot.y() + dir.y() * clipped,
                             pivot.z() + dir.z() * clipped);
        }
        return desired;
    }

    private static boolean overlapsObstacle(Vec3f center, float radius,
                                            TileGridValue3D grid, float cellSize) {
        float offsetX = -grid.width() * cellSize * NUM_0_5;
        float offsetY = -grid.height() * cellSize * NUM_0_5;
        float offsetZ = -grid.depth() * cellSize * NUM_0_5;
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
                    if (!isObstacle(grid, gx, gy, gz)) continue;
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

    private static boolean isObstacle(TileGridValue3D grid, int x, int y, int z) {
        if (x < 0 || x >= grid.width()) return true;
        if (y < 0 || y >= grid.height()) return true;
        if (z < 0 || z >= grid.depth()) return true;
        return grid.at(x, y, z) == CellType.EMPTY;
    }
}
