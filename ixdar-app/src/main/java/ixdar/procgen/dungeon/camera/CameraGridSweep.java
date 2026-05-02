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

    private CameraGridSweep() {
    }

    public static Vec3f sweep(Vec3f pivot, Vec3f desired, float cameraRadius,
                              TileGridValue3D grid, float cellSize, float padding) {
        if (cellSize <= 0f) {
            throw new IllegalArgumentException("cellSize must be > 0, got " + cellSize);
        }
        Vec3f delta = desired.sub(pivot);
        float dist = delta.length();
        if (dist < 1e-6f) return desired;
        Vec3f dir = delta.scale(1f / dist);

        float step = Math.max(1e-4f, Math.min(cameraRadius * 0.5f, dist / 32f));
        float lastClear = 0f;
        for (float t = step; t <= dist; t += step) {
            Vec3f p = new Vec3f(pivot.x() + dir.x() * t,
                                pivot.y() + dir.y() * t,
                                pivot.z() + dir.z() * t);
            if (overlapsObstacle(p, cameraRadius, grid, cellSize)) {
                float clipped = Math.max(0f, lastClear - padding);
                return new Vec3f(pivot.x() + dir.x() * clipped,
                                 pivot.y() + dir.y() * clipped,
                                 pivot.z() + dir.z() * clipped);
            }
            lastClear = t;
        }
        // Final endpoint check (loop may stop just short of dist due to step granularity).
        if (overlapsObstacle(desired, cameraRadius, grid, cellSize)) {
            float clipped = Math.max(0f, lastClear - padding);
            return new Vec3f(pivot.x() + dir.x() * clipped,
                             pivot.y() + dir.y() * clipped,
                             pivot.z() + dir.z() * clipped);
        }
        return desired;
    }

    private static boolean overlapsObstacle(Vec3f center, float radius,
                                            TileGridValue3D grid, float cellSize) {
        float offsetX = -grid.width() * cellSize * 0.5f;
        float offsetY = -grid.height() * cellSize * 0.5f;
        float offsetZ = -grid.depth() * cellSize * 0.5f;
        CapsuleShape sphere = new CapsuleShape(center.x(), center.y(), center.z(), 0f, radius);
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
