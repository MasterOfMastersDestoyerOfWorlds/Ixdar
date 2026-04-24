package unit.procgen.dungeon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder2D;
import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder2D.CostWeights;
import ixdar.procgen.dungeon.values.CellType;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue;
import ixdar.procgen.dungeon.values.RoomListValue.Room;
import ixdar.procgen.dungeon.values.TileGridValue;

public class AStarCorridorPathfinder2DTest {

    private static RoomListValue roomsAt(float... xy) {
        List<Room> rs = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 4) {
            rs.add(new Room(i / 4,
                    xy[i], xy[i + 1],
                    xy[i + 2], xy[i + 3]));
        }
        return new RoomListValue(rs);
    }

    private static boolean hasPathBetween(TileGridValue grid, int sx, int sy, int tx, int ty) {
        int w = grid.width();
        int h = grid.height();
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[w * h];
        int sp = 0;
        int start = sy * w + sx;
        seen[start] = true;
        stack[sp++] = start;
        while (sp > 0) {
            int v = stack[--sp];
            int cx = v % w;
            int cy = v / w;
            if (cx == tx && cy == ty) return true;
            for (int d = 0; d < 4; d++) {
                int nx = cx + new int[]{1, -1, 0, 0}[d];
                int ny = cy + new int[]{0, 0, 1, -1}[d];
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                int idx = ny * w + nx;
                if (seen[idx]) continue;
                CellType c = grid.at(nx, ny);
                if (c == CellType.EMPTY) continue;
                seen[idx] = true;
                stack[sp++] = idx;
            }
        }
        return false;
    }

    @Test
    public void twoRoomsGetConnectedByHallway() {
        // Room A at (3,5) 3x3, Room B at (20,5) 3x3 — needs corridor between them.
        RoomListValue rooms = roomsAt(
                3, 5, 1.5f, 1.5f,
                20, 5, 1.5f, 1.5f);
        EdgeGraphValue edges = new EdgeGraphValue(2, new int[][] { { 0, 1 } });
        TileGridValue grid = AStarCorridorPathfinder2D.carve(30, 10, rooms, edges,
                AStarCorridorPathfinder2D.DEFAULT_WEIGHTS);
        assertTrue(hasPathBetween(grid, 3, 5, 20, 5),
                "should find a ROOM-or-HALLWAY path between the two room centers");
    }

    @Test
    public void roomsArePaintedInOutputGrid() {
        RoomListValue rooms = roomsAt(5, 5, 1.5f, 1.5f);
        EdgeGraphValue edges = new EdgeGraphValue(1, new int[0][]);
        TileGridValue grid = AStarCorridorPathfinder2D.carve(20, 20, rooms, edges,
                AStarCorridorPathfinder2D.DEFAULT_WEIGHTS);
        // Room AABB covers cells roughly (3..7) x (3..7); center cell must be ROOM.
        assertEquals(CellType.ROOM, grid.at(5, 5));
    }

    @Test
    public void corridorsAvoidRoomsWhenCostHigh() {
        // Three rooms roughly in a triangle: A, B, C. Edge A-C should normally go straight
        // across but with very high throughRoomCost it should go around room B.
        RoomListValue rooms = roomsAt(
                5, 5, 1.5f, 1.5f,    // A
                15, 5, 2.5f, 2.5f,   // B (wider, in the way)
                25, 5, 1.5f, 1.5f);  // C
        EdgeGraphValue edges = new EdgeGraphValue(3, new int[][] { { 0, 2 } });
        // Cost weights make room interior prohibitively expensive.
        CostWeights weights = new CostWeights(1.0, 5.0, 10_000.0);
        TileGridValue grid = AStarCorridorPathfinder2D.carve(30, 15, rooms, edges, weights);
        // Assert: HALLWAY cells never overlap room B's painted interior.
        Room b = rooms.get(1);
        int bx0 = (int) Math.floor(b.minX()), bx1 = (int) Math.ceil(b.maxX());
        int by0 = (int) Math.floor(b.minY()), by1 = (int) Math.ceil(b.maxY());
        for (int y = by0; y < by1; y++) {
            for (int x = bx0; x < bx1; x++) {
                CellType c = grid.at(x, y);
                assertTrue(c == CellType.ROOM,
                        "cell (" + x + "," + y + ") inside room B must stay ROOM, got " + c);
            }
        }
        // And A connects to C.
        assertTrue(hasPathBetween(grid, 5, 5, 25, 5));
    }

    @Test
    public void reuseIncentiveConsolidatesCorridors() {
        // Two far rooms plus a third nearby room. If reuse cost << empty cost, the second
        // corridor should heavily overlap the first rather than cutting a parallel path.
        RoomListValue rooms = roomsAt(
                3, 5, 1.5f, 1.5f,    // A
                25, 5, 1.5f, 1.5f,   // B (far)
                25, 6, 1.5f, 1.5f);  // C (right next to B) — oops this overlaps B, use:
        rooms = roomsAt(
                3, 5, 1.5f, 1.5f,    // A
                25, 3, 1.5f, 1.5f,   // B
                25, 8, 1.5f, 1.5f);  // C
        EdgeGraphValue edges = new EdgeGraphValue(3, new int[][] {
                { 0, 1 }, // A-B first (lays trunk)
                { 0, 2 }, // A-C second (should reuse trunk)
        });
        CostWeights cheapReuse = new CostWeights(0.01, 10.0, 10_000.0);
        TileGridValue grid = AStarCorridorPathfinder2D.carve(30, 15, rooms, edges, cheapReuse);
        // Count HALLWAY cells. If corridors share a trunk, count should be less than two
        // independent Manhattan paths (A->B and A->C) which would total roughly 22+23 = 45.
        int hallway = 0;
        for (int y = 0; y < grid.height(); y++) {
            for (int x = 0; x < grid.width(); x++) {
                if (grid.at(x, y) == CellType.HALLWAY) hallway++;
            }
        }
        assertTrue(hallway < 40,
                "aggressive reuse cost should consolidate corridors, got " + hallway + " hallway cells");
    }

    @Test
    public void carveIsDeterministic() {
        RoomListValue rooms = roomsAt(
                3, 3, 1.5f, 1.5f,
                20, 15, 1.5f, 1.5f,
                10, 20, 1.5f, 1.5f);
        EdgeGraphValue edges = new EdgeGraphValue(3, new int[][] { { 0, 1 }, { 1, 2 }, { 0, 2 } });
        TileGridValue a = AStarCorridorPathfinder2D.carve(30, 30, rooms, edges,
                AStarCorridorPathfinder2D.DEFAULT_WEIGHTS);
        TileGridValue b = AStarCorridorPathfinder2D.carve(30, 30, rooms, edges,
                AStarCorridorPathfinder2D.DEFAULT_WEIGHTS);
        for (int i = 0; i < a.cellCount(); i++) {
            assertEquals(a.cells()[i], b.cells()[i], "cell " + i + " must match across runs");
        }
    }

    @Test
    public void zeroEdgesLeavesOnlyRooms() {
        RoomListValue rooms = roomsAt(5, 5, 1.5f, 1.5f, 15, 15, 1.5f, 1.5f);
        EdgeGraphValue edges = new EdgeGraphValue(2, new int[0][]);
        TileGridValue grid = AStarCorridorPathfinder2D.carve(20, 20, rooms, edges,
                AStarCorridorPathfinder2D.DEFAULT_WEIGHTS);
        int hallway = 0;
        for (int i = 0; i < grid.cellCount(); i++) {
            if (grid.cells()[i] == CellType.HALLWAY) hallway++;
        }
        assertEquals(0, hallway, "no edges -> no hallway cells");
    }
}
