package unit.procgen.dungeon.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.procgen.dungeon.camera.CameraGridSweep;
import ixdar.procgen.dungeon.physics.Vec3f;
import ixdar.procgen.dungeon.values.CellType;
import ixdar.procgen.dungeon.values.TileGridValue3D;

public class CameraGridSweepTest {

    private static CellType[] interiorGrid(int n) {
        CellType[] cells = new CellType[n * n * n];
        for (int i = 0; i < cells.length; i++) cells[i] = CellType.ROOM;
        return cells;
    }

    private static int idx(int x, int y, int z, int w, int d) {
        return x + w * (z + d * y);
    }

    @Test
    public void clearPathReturnsDesiredPosition() {
        // 3x3x3 all-interior grid; sweep along X with no obstacles.
        TileGridValue3D grid = new TileGridValue3D(3, 3, 3, interiorGrid(3));
        Vec3f pivot = new Vec3f(0f, 0f, 0f);
        Vec3f desired = new Vec3f(1.0f, 0f, 0f);
        Vec3f cam = CameraGridSweep.sweep(pivot, desired, 0.15f, grid, 1.0f, 0.05f);
        assertEquals(desired.x(), cam.x(), 1e-3f);
        assertEquals(desired.y(), cam.y(), 1e-3f);
        assertEquals(desired.z(), cam.z(), 1e-3f);
    }

    @Test
    public void wallClipsCameraDistance() {
        // 3x3x3 grid; cell (2,1,1) is EMPTY (wall). Pivot at (-0.4, 0, 0); aim camera through wall.
        // Wall face at X=0.5; with cameraRadius=0.15 + padding=0.05, the camera center should stop
        // around X=0.3-0.05=0.25 (well below desired X=1.4).
        CellType[] cells = interiorGrid(3);
        cells[idx(2, 1, 1, 3, 3)] = CellType.EMPTY;
        TileGridValue3D grid = new TileGridValue3D(3, 3, 3, cells);
        Vec3f pivot = new Vec3f(-0.4f, 0f, 0f);
        Vec3f desired = new Vec3f(1.4f, 0f, 0f);
        Vec3f cam = CameraGridSweep.sweep(pivot, desired, 0.15f, grid, 1.0f, 0.05f);
        // Distance from pivot must be substantially less than the requested 1.8.
        float dist = cam.sub(pivot).length();
        float requested = desired.sub(pivot).length();
        assertTrue(dist < requested - 0.5f,
                "camera should be clipped well short of desired (dist=" + dist + ")");
        // Camera must stay on the requested ray (no slide into adjacent cells).
        assertEquals(0f, cam.y(), 1e-4f);
        assertEquals(0f, cam.z(), 1e-4f);
        // And it must clear the wall at X=0.5.
        assertTrue(cam.x() + 0.15f < 0.5f + 1e-3f,
                "camera sphere should not penetrate wall (cam.x=" + cam.x() + ")");
    }

    @Test
    public void zeroLengthSweepReturnsDesired() {
        TileGridValue3D grid = new TileGridValue3D(3, 3, 3, interiorGrid(3));
        Vec3f pivot = new Vec3f(0.1f, 0.2f, 0.3f);
        Vec3f cam = CameraGridSweep.sweep(pivot, pivot, 0.15f, grid, 1.0f, 0.05f);
        assertEquals(pivot.x(), cam.x(), 1e-6f);
        assertEquals(pivot.y(), cam.y(), 1e-6f);
        assertEquals(pivot.z(), cam.z(), 1e-6f);
    }
}
