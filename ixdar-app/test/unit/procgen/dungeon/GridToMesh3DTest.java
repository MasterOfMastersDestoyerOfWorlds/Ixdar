package unit.procgen.dungeon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.procgen.dungeon.algo.GridToMesh3D;
import ixdar.procgen.dungeon.values.CellType;
import ixdar.procgen.dungeon.values.TileGridValue3D;

public class GridToMesh3DTest {

    private static TileGridValue3D allEmpty(int w, int h, int d) {
        CellType[] cells = new CellType[w * h * d];
        java.util.Arrays.fill(cells, CellType.EMPTY);
        return new TileGridValue3D(w, h, d, cells);
    }

    @Test
    public void emptyGridEmitsEmptyMesh() {
        ArrayMesh mesh = GridToMesh3D.emit(allEmpty(3, 3, 3), 1.0f);
        assertEquals(0, mesh.vertexCount());
        assertEquals(0, mesh.faceCount());
    }

    @Test
    public void singleRoomCellEmitsHollowBox() {
        CellType[] cells = new CellType[8];
        java.util.Arrays.fill(cells, CellType.EMPTY);
        cells[0] = CellType.ROOM;
        TileGridValue3D grid = new TileGridValue3D(2, 2, 2, cells);
        ArrayMesh mesh = GridToMesh3D.emit(grid, 1.0f);
        // 6 boundaries, both windings each = 12 quads.
        assertEquals(12, mesh.faceCount());
        assertEquals(12 * 4, mesh.vertexCount());
    }

    @Test
    public void verticallyAdjacentStairCellsShareNoFloorOrCeiling() {
        CellType[] cells = new CellType[2];
        cells[0] = CellType.STAIR_UP;
        cells[1] = CellType.STAIR_DOWN;
        TileGridValue3D grid = new TileGridValue3D(1, 2, 1, cells);
        ArrayMesh mesh = GridToMesh3D.emit(grid, 1.0f);
        // Each cell: 5 boundaries (4 walls + 1 floor/ceiling) -> 10 boundaries -> 20 quads.
        assertEquals(20, mesh.faceCount(),
                "stair cells should share their internal floor/ceiling boundary");
    }

    @Test
    public void vertexAndFaceCountScaleWithNonEmptyCells() {
        CellType[] cells = new CellType[27];
        java.util.Arrays.fill(cells, CellType.EMPTY);
        cells[0] = CellType.ROOM;
        cells[5] = CellType.HALLWAY;
        cells[10] = CellType.STAIR_UP;
        cells[15] = CellType.STAIR_DOWN;
        cells[26] = CellType.ROOM;
        TileGridValue3D grid = new TileGridValue3D(3, 3, 3, cells);
        ArrayMesh mesh = GridToMesh3D.emit(grid, 1.0f);
        // 5 isolated cells -> 30 boundaries -> 60 quads.
        assertEquals(5 * 6 * 2, mesh.faceCount());
        assertEquals(5 * 6 * 2 * 4, mesh.vertexCount());
    }

    @Test
    public void gridIsCenteredAtOrigin() {
        // 4x2x4 all-ROOM grid, cellSize=1 -> X/Z span [-2, 2], Y span [-1, 1].
        CellType[] cells = new CellType[32];
        java.util.Arrays.fill(cells, CellType.ROOM);
        TileGridValue3D grid = new TileGridValue3D(4, 2, 4, cells);
        ArrayMesh mesh = GridToMesh3D.emit(grid, 1.0f);
        float[] p = mesh.copyPositions();
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i + 2 < p.length; i += 3) {
            minX = Math.min(minX, p[i]); maxX = Math.max(maxX, p[i]);
            minY = Math.min(minY, p[i + 1]); maxY = Math.max(maxY, p[i + 1]);
            minZ = Math.min(minZ, p[i + 2]); maxZ = Math.max(maxZ, p[i + 2]);
        }
        assertEquals(-2f, minX, 1e-4f); assertEquals(2f, maxX, 1e-4f);
        assertEquals(-2f, minZ, 1e-4f); assertEquals(2f, maxZ, 1e-4f);
        assertEquals(-1f, minY, 1e-4f); assertEquals(1f, maxY, 1e-4f);
    }

    @Test
    public void allNonEmptyCellsShareUniformHeight() {
        // Hollow rooms use uniform cell height so adjacent cells form continuous walkable space.
        // Type-based visual distinction (ROOM vs HALLWAY) deferred to color/texture, not geometry.
        for (CellType t : new CellType[] { CellType.ROOM, CellType.HALLWAY,
                                            CellType.STAIR_UP, CellType.STAIR_DOWN }) {
            ArrayMesh m = GridToMesh3D.emit(new TileGridValue3D(1, 1, 1, new CellType[] { t }), 1f);
            assertEquals(1f, bboxY(m), 1e-4f, "cell type " + t + " should have height = cellSize");
        }
    }

    private static float bboxY(ArrayMesh mesh) {
        float[] p = mesh.copyPositions();
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i + 2 < p.length; i += 3) {
            minY = Math.min(minY, p[i + 1]);
            maxY = Math.max(maxY, p[i + 1]);
        }
        return maxY - minY;
    }
}
