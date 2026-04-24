package unit.procgen.dungeon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.annotations.meshnode.PortType;
import ixdar.procgen.dungeon.values.CellType;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue;
import ixdar.procgen.dungeon.values.TileGridValue;

public class DungeonValueTypesTest {

    @Test
    public void portTypeHasDungeonEntries() {
        // Smoke check — PROCGEN-5 node wrappers will declare input/output ports of these types.
        assertEquals("ROOM_LIST", PortType.ROOM_LIST.name());
        assertEquals("EDGE_GRAPH", PortType.EDGE_GRAPH.name());
        assertEquals("TILE_GRID", PortType.TILE_GRID.name());
    }

    @Test
    public void roomListIsDefensiveCopied() {
        List<RoomListValue.Room> src = new ArrayList<>();
        src.add(new RoomListValue.Room(0, 1.5f, 1.5f, 1.5f, 1.5f));
        RoomListValue v = new RoomListValue(src);
        src.add(new RoomListValue.Room(1, 9f, 9f, 1f, 1f));
        assertEquals(1, v.size(), "mutating source list must not affect the value");
    }

    @Test
    public void roomIntersectionBasics() {
        RoomListValue.Room a = new RoomListValue.Room(0, 5, 5, 2, 2); // x:[3,7] y:[3,7]
        RoomListValue.Room b = new RoomListValue.Room(1, 8, 5, 2, 2); // x:[6,10] y:[3,7] -> overlaps
        RoomListValue.Room c = new RoomListValue.Room(2, 20, 20, 2, 2); // far away
        assertTrue(a.intersects(b));
        assertTrue(b.intersects(a));
        assertFalse(a.intersects(c));
    }

    @Test
    public void roomTouchingEdgesDoNotIntersect() {
        // Vazgriz placement requires a 1-unit buffer; rooms that touch exactly at an edge
        // should NOT be considered intersecting (strict inequality in AABB overlap test).
        RoomListValue.Room a = new RoomListValue.Room(0, 2, 2, 2, 2); // x:[0,4]
        RoomListValue.Room b = new RoomListValue.Room(1, 6, 2, 2, 2); // x:[4,8] -- touches at x=4
        assertFalse(a.intersects(b));
    }

    @Test
    public void edgeGraphDefensiveCopiesEdges() {
        int[][] edges = { { 0, 1 }, { 1, 2 } };
        EdgeGraphValue g = new EdgeGraphValue(3, edges);
        edges[0][0] = 99; // mutate source
        assertArrayEquals(new int[] { 0, 1 }, g.edge(0), "mutating source must not change stored edges");
    }

    @Test
    public void edgeGraphValidatesRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new EdgeGraphValue(2, new int[][] { { 0, 5 } }),
                "out-of-range edge index must reject");
        assertThrows(IllegalArgumentException.class,
                () -> new EdgeGraphValue(-1, new int[0][]),
                "negative node count must reject");
        assertThrows(IllegalArgumentException.class,
                () -> new EdgeGraphValue(2, new int[][] { { 0, 1, 2 } }),
                "edge row must be length 2");
    }

    @Test
    public void edgeGraphEdgeAccessorReturnsCopy() {
        EdgeGraphValue g = new EdgeGraphValue(3, new int[][] { { 0, 1 }, { 1, 2 } });
        int[] first = g.edge(0);
        int[] second = g.edge(0);
        assertNotSame(first, second, "edge() must return a fresh copy each call");
        first[0] = 99;
        assertEquals(0, g.edge(0)[0], "mutating returned copy must not affect the graph");
    }

    @Test
    public void tileGridDefensiveCopiesCells() {
        CellType[] cells = { CellType.EMPTY, CellType.ROOM, CellType.HALLWAY, CellType.EMPTY };
        TileGridValue t = new TileGridValue(2, 2, cells);
        cells[0] = CellType.ROOM;
        assertEquals(CellType.EMPTY, t.at(0, 0), "mutating source must not change stored grid");
    }

    @Test
    public void tileGridRejectsSizeMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> new TileGridValue(3, 3, new CellType[5]),
                "cell array length must equal width*height");
    }

    @Test
    public void tileGridAtRowMajor() {
        CellType[] cells = {
                CellType.EMPTY, CellType.ROOM, // y=0 row
                CellType.HALLWAY, CellType.EMPTY, // y=1 row
        };
        TileGridValue t = new TileGridValue(2, 2, cells);
        assertEquals(CellType.EMPTY, t.at(0, 0));
        assertEquals(CellType.ROOM, t.at(1, 0));
        assertEquals(CellType.HALLWAY, t.at(0, 1));
        assertEquals(CellType.EMPTY, t.at(1, 1));
    }

    @Test
    public void tileGridBoundsEnforced() {
        TileGridValue t = new TileGridValue(2, 2, new CellType[] {
                CellType.EMPTY, CellType.EMPTY, CellType.EMPTY, CellType.EMPTY });
        assertThrows(IndexOutOfBoundsException.class, () -> t.at(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> t.at(0, 2));
    }
}
