package unit.procgen.dungeon.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.procgen.dungeon.physics.CapsuleMover;
import ixdar.procgen.dungeon.physics.CapsuleShape;
import ixdar.procgen.dungeon.physics.Vec3f;
import ixdar.procgen.dungeon.values.CellType;
import ixdar.procgen.dungeon.values.TileGridValue3D;

public class CapsuleMoverTest {

    /**
     * Build a 3x3x3 grid centered at the origin (cellSize=1). All-walkable interior so the
     * capsule can move freely; tests can override individual cells to obstruct paths.
     */
    private static CellType[] interiorGrid() {
        CellType[] cells = new CellType[27];
        for (int i = 0; i < 27; i++) cells[i] = CellType.ROOM;
        return cells;
    }

    private static int idx(int x, int y, int z, int w, int d) {
        return x + w * (z + d * y);
    }

    @Test
    public void noObstaclesReturnsStartPlusDelta() {
        TileGridValue3D grid = new TileGridValue3D(3, 3, 3, interiorGrid());
        CapsuleShape c = new CapsuleShape(0f, 0f, 0f, 0.3f, 0.2f);
        Vec3f delta = new Vec3f(0.4f, 0f, 0f);
        Vec3f end = CapsuleMover.moveAndSlide(c, delta, grid, 1.0f);
        assertEquals(0.4f, end.x(), 1e-4f);
        assertEquals(0f, end.y(), 1e-4f);
        assertEquals(0f, end.z(), 1e-4f);
    }

    @Test
    public void wallStopsForwardMotionFlush() {
        // 3x3x3 grid, center cell at (1,1,1) is ROOM, neighbor (2,1,1) is EMPTY (a wall).
        // World coords: cell (1,1,1) = [-0.5,0.5] in X/Y/Z; cell (2,1,1) = [0.5,1.5] in X.
        // Capsule starts at (-0.4, 0, 0) trying to go +X by 0.7. Wall at X=0.5 should stop it
        // when capsule's leading edge (X + radius=0.2) reaches X=0.5 -> capsule center X=0.3.
        CellType[] cells = interiorGrid();
        cells[idx(2, 1, 1, 3, 3)] = CellType.EMPTY;
        TileGridValue3D grid = new TileGridValue3D(3, 3, 3, cells);
        CapsuleShape c = new CapsuleShape(-0.4f, 0f, 0f, 0.0f, 0.2f);
        Vec3f end = CapsuleMover.moveAndSlide(c, new Vec3f(0.7f, 0f, 0f), grid, 1.0f);
        assertEquals(0.3f, end.x(), 1e-3f, "capsule should stop with surface flush against wall");
    }

    @Test
    public void slideAlongWallPreservesPerpendicularMotion() {
        // Wall at +X side. Capsule pushed diagonally (+X, +Z). +X component eaten by wall;
        // +Z component should survive (slide).
        CellType[] cells = interiorGrid();
        cells[idx(2, 1, 1, 3, 3)] = CellType.EMPTY;
        TileGridValue3D grid = new TileGridValue3D(3, 3, 3, cells);
        CapsuleShape c = new CapsuleShape(-0.4f, 0f, 0f, 0.0f, 0.2f);
        Vec3f end = CapsuleMover.moveAndSlide(c, new Vec3f(0.7f, 0f, 0.5f), grid, 1.0f);
        assertEquals(0.3f, end.x(), 1e-3f, "X stopped at wall");
        assertEquals(0.5f, end.z(), 1e-3f, "Z slide preserved (no obstacle on Z)");
    }

    @Test
    public void cannotEscapeGridThroughBoundary() {
        // Grid is finite. Cells at the boundary should still block the capsule from leaving.
        // 3x3x3 grid, all interior. Capsule near the +X edge of the grid.
        TileGridValue3D grid = new TileGridValue3D(3, 3, 3, interiorGrid());
        // Grid spans [-1.5, 1.5] on every axis. Capsule at (1.0, 0, 0) trying to escape +X.
        CapsuleShape c = new CapsuleShape(1.0f, 0f, 0f, 0.0f, 0.2f);
        Vec3f end = CapsuleMover.moveAndSlide(c, new Vec3f(2.0f, 0f, 0f), grid, 1.0f);
        // Out-of-grid cells are treated as obstacles, so the capsule should stop at X = 1.3
        // (1.5 boundary - 0.2 radius).
        assertTrue(end.x() <= 1.3f + 1e-3f, "capsule should not leave the grid: X=" + end.x());
    }

    @Test
    public void deterministicForFixedInputs() {
        TileGridValue3D grid = new TileGridValue3D(3, 3, 3, interiorGrid());
        CapsuleShape c = new CapsuleShape(-0.4f, 0f, -0.3f, 0.3f, 0.2f);
        Vec3f delta = new Vec3f(0.5f, 0.1f, 0.7f);
        Vec3f a = CapsuleMover.moveAndSlide(c, delta, grid, 1.0f);
        Vec3f b = CapsuleMover.moveAndSlide(c, delta, grid, 1.0f);
        assertEquals(a.x(), b.x(), 0f);
        assertEquals(a.y(), b.y(), 0f);
        assertEquals(a.z(), b.z(), 0f);
    }

    @Test
    public void zeroDeltaReturnsStart() {
        TileGridValue3D grid = new TileGridValue3D(3, 3, 3, interiorGrid());
        CapsuleShape c = new CapsuleShape(0.1f, 0.2f, 0.3f, 0.3f, 0.2f);
        Vec3f end = CapsuleMover.moveAndSlide(c, Vec3f.ZERO, grid, 1.0f);
        assertEquals(0.1f, end.x(), 1e-5f);
        assertEquals(0.2f, end.y(), 1e-5f);
        assertEquals(0.3f, end.z(), 1e-5f);
    }

    @Test
    public void performanceIsAdequate() {
        // 30x5x30 grid (vazgriz default size), mostly empty with a couple interior cells.
        // 1000 moveAndSlide calls should complete well under one frame (16ms).
        int W = 30, H = 5, D = 30;
        CellType[] cells = new CellType[W * H * D];
        for (int i = 0; i < cells.length; i++) cells[i] = CellType.EMPTY;
        // Carve a small interior corridor down the middle of floor 2.
        for (int x = 5; x < 25; x++) cells[x + W * (15 + D * 2)] = CellType.HALLWAY;
        TileGridValue3D grid = new TileGridValue3D(W, H, D, cells);
        CapsuleShape c = new CapsuleShape(0f, 0f, 0f, 0.3f, 0.2f);
        Vec3f delta = new Vec3f(0.05f, 0f, 0f);
        long t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            CapsuleMover.moveAndSlide(c, delta, grid, 1.0f);
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsedMs < 80,
                "1000 moveAndSlide calls should be under 80ms (one frame x 5 budget), got " + elapsedMs);
    }
}
