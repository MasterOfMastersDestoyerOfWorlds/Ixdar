package unit.procgen.dungeon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.procgen.dungeon.algo.GridToMesh2D;
import ixdar.procgen.dungeon.values.CellType;
import ixdar.procgen.dungeon.values.TileGridValue;

public class GridToMesh2DTest {

    private static TileGridValue allEmpty(int w, int h) {
        CellType[] cells = new CellType[w * h];
        java.util.Arrays.fill(cells, CellType.EMPTY);
        return new TileGridValue(w, h, cells);
    }

    @Test
    public void emptyGridEmitsEmptyMesh() {
        ArrayMesh mesh = GridToMesh2D.emit(allEmpty(3, 3), 1.0f);
        assertEquals(0, mesh.vertexCount());
        assertEquals(0, mesh.faceCount());
    }

    @Test
    public void singleRoomCellEmitsHollowBox() {
        CellType[] cells = new CellType[4];
        java.util.Arrays.fill(cells, CellType.EMPTY);
        cells[0] = CellType.ROOM;
        TileGridValue grid = new TileGridValue(2, 2, cells);
        ArrayMesh mesh = GridToMesh2D.emit(grid, 1.0f);
        // One isolated cell: 6 boundary faces, each emitted with both windings = 12 quads.
        assertEquals(12, mesh.faceCount());
        assertEquals(12 * 4, mesh.vertexCount());
    }

    @Test
    public void adjacentCellsShareNoInternalWall() {
        // Two ROOM cells side by side: shared boundary should NOT have a wall.
        // Each cell: 5 boundary faces (3 walls EMPTY + floor + ceiling) -> 10 boundaries
        // -> 20 quads after the inward+outward double-emit.
        CellType[] cells = { CellType.ROOM, CellType.ROOM };
        TileGridValue grid = new TileGridValue(2, 1, cells);
        ArrayMesh mesh = GridToMesh2D.emit(grid, 1.0f);
        assertEquals(20, mesh.faceCount(),
                "two adjacent cells should share their internal boundary (no wall there)");
    }

    @Test
    public void vertexAndFaceCountScaleWithNonEmptyCells() {
        // 5 isolated non-empty cells (no adjacency): 5 * 6 = 30 boundaries -> 60 quads.
        CellType[] cells = new CellType[9];
        java.util.Arrays.fill(cells, CellType.EMPTY);
        cells[0] = CellType.ROOM;
        cells[2] = CellType.ROOM;
        cells[4] = CellType.HALLWAY;
        cells[6] = CellType.HALLWAY;
        cells[8] = CellType.ROOM;
        TileGridValue grid = new TileGridValue(3, 3, cells);
        ArrayMesh mesh = GridToMesh2D.emit(grid, 2.0f);
        assertEquals(5 * 6 * 2, mesh.faceCount());
        assertEquals(5 * 6 * 2 * 4, mesh.vertexCount());
    }

    @Test
    public void cellSizeScalesGeometry() {
        CellType[] cells = new CellType[1];
        cells[0] = CellType.ROOM;
        TileGridValue grid = new TileGridValue(1, 1, cells);
        ArrayMesh mesh = GridToMesh2D.emit(grid, 3.0f);
        float[] bbox = bbox(mesh);
        // A single ROOM cell at grid (0,0) with cellSize=3, centered on world origin, spans
        // [-1.5, 1.5] in X/Z (centering offset) and [0, 3] in Y (wall height above floor).
        assertEquals(-1.5f, bbox[0], 1e-4f);
        assertEquals(1.5f, bbox[1], 1e-4f);
        assertEquals(0f, bbox[2], 1e-4f);
        assertEquals(3f, bbox[3], 1e-4f);
        assertEquals(-1.5f, bbox[4], 1e-4f);
        assertEquals(1.5f, bbox[5], 1e-4f);
    }

    @Test
    public void gridIsCenteredAtOrigin() {
        // 4x4 grid, all ROOM cells, cellSize=1 -> spans 4 units; centered -> [-2, 2] in X/Z.
        CellType[] cells = new CellType[16];
        java.util.Arrays.fill(cells, CellType.ROOM);
        TileGridValue grid = new TileGridValue(4, 4, cells);
        ArrayMesh mesh = GridToMesh2D.emit(grid, 1.0f);
        float[] bbox = bbox(mesh);
        assertEquals(-2f, bbox[0], 1e-4f, "X min");
        assertEquals(2f, bbox[1], 1e-4f, "X max");
        assertEquals(-2f, bbox[4], 1e-4f, "Z min");
        assertEquals(2f, bbox[5], 1e-4f, "Z max");
    }

    @Test
    public void roomAndHallwayCellsHaveSameHeight() {
        // Hollow-room refactor uses a uniform cell height across types so that adjacent
        // ROOM/HALLWAY cells form a continuous walkable space without ceiling jumps.
        CellType[] cellsRoom = { CellType.ROOM };
        CellType[] cellsHall = { CellType.HALLWAY };
        ArrayMesh meshRoom = GridToMesh2D.emit(new TileGridValue(1, 1, cellsRoom), 1f);
        ArrayMesh meshHall = GridToMesh2D.emit(new TileGridValue(1, 1, cellsHall), 1f);
        float heightRoom = bbox(meshRoom)[3];
        float heightHall = bbox(meshHall)[3];
        assertEquals(heightRoom, heightHall, 1e-4f,
                "ROOM and HALLWAY now use the same height for walkable interiors");
    }

    @Test
    public void isDeterministic() {
        CellType[] cells = new CellType[9];
        for (int i = 0; i < 9; i++) cells[i] = (i % 2 == 0) ? CellType.ROOM : CellType.HALLWAY;
        TileGridValue grid = new TileGridValue(3, 3, cells);
        ArrayMesh a = GridToMesh2D.emit(grid, 1.0f);
        ArrayMesh b = GridToMesh2D.emit(grid, 1.0f);
        assertEquals(a.vertexCount(), b.vertexCount());
        assertEquals(a.faceCount(), b.faceCount());
        float[] pa = a.copyPositions();
        float[] pb = b.copyPositions();
        assertEquals(pa.length, pb.length);
        for (int i = 0; i < pa.length; i++) {
            assertEquals(pa[i], pb[i], 0f, "position " + i);
        }
    }

    /** Returns {minX, maxX, minY, maxY, minZ, maxZ}. */
    private static float[] bbox(ArrayMesh mesh) {
        float[] p = mesh.copyPositions();
        float[] b = { Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                      Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                      Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY };
        for (int i = 0; i + 2 < p.length; i += 3) {
            b[0] = Math.min(b[0], p[i]);
            b[1] = Math.max(b[1], p[i]);
            b[2] = Math.min(b[2], p[i + 1]);
            b[3] = Math.max(b[3], p[i + 1]);
            b[4] = Math.min(b[4], p[i + 2]);
            b[5] = Math.max(b[5], p[i + 2]);
        }
        return b;
    }
}
