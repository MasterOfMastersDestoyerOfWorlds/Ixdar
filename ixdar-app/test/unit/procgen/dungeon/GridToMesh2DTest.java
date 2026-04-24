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
    public void singleRoomCellEmitsOneBox() {
        CellType[] cells = new CellType[4];
        java.util.Arrays.fill(cells, CellType.EMPTY);
        cells[0] = CellType.ROOM;
        TileGridValue grid = new TileGridValue(2, 2, cells);
        ArrayMesh mesh = GridToMesh2D.emit(grid, 1.0f);
        assertEquals(8, mesh.vertexCount(), "one box = 8 vertices");
        assertEquals(6, mesh.faceCount(), "one box = 6 quad faces");
    }

    @Test
    public void vertexAndFaceCountScaleWithNonEmptyCells() {
        // 3 rooms + 2 hallway = 5 non-empty cells -> 5 boxes -> 40 verts, 30 quads.
        CellType[] cells = new CellType[9];
        java.util.Arrays.fill(cells, CellType.EMPTY);
        cells[0] = CellType.ROOM;
        cells[1] = CellType.ROOM;
        cells[2] = CellType.HALLWAY;
        cells[4] = CellType.HALLWAY;
        cells[8] = CellType.ROOM;
        TileGridValue grid = new TileGridValue(3, 3, cells);
        ArrayMesh mesh = GridToMesh2D.emit(grid, 2.0f);
        assertEquals(5 * 8, mesh.vertexCount());
        assertEquals(5 * 6, mesh.faceCount());
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
    public void hallwayBoxIsFlatterThanRoomBox() {
        CellType[] cellsRoom = { CellType.ROOM };
        CellType[] cellsHall = { CellType.HALLWAY };
        ArrayMesh meshRoom = GridToMesh2D.emit(new TileGridValue(1, 1, cellsRoom), 1f);
        ArrayMesh meshHall = GridToMesh2D.emit(new TileGridValue(1, 1, cellsHall), 1f);
        float heightRoom = bbox(meshRoom)[3];
        float heightHall = bbox(meshHall)[3];
        assertTrue(heightHall < heightRoom, "hallway should be visually shorter than room");
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
