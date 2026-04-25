package unit.procgen.dungeon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder2D;
import ixdar.procgen.dungeon.algo.AStarCorridorPathfinder3D;
import ixdar.procgen.dungeon.values.CellType;
import ixdar.procgen.dungeon.values.EdgeGraphValue;
import ixdar.procgen.dungeon.values.RoomListValue3D;
import ixdar.procgen.dungeon.values.RoomListValue3D.Room;
import ixdar.procgen.dungeon.values.TileGridValue3D;

public class AStarCorridorPathfinder3DTest {

    private static RoomListValue3D rooms(float... data) {
        // (cx, cy, cz, hxz_half_extent) per 4 floats; halfY fixed at 0.5
        List<Room> list = new ArrayList<>();
        for (int i = 0; i < data.length; i += 4) {
            list.add(new Room(i / 4, data[i], data[i + 1], data[i + 2],
                    data[i + 3], 0.5f, data[i + 3]));
        }
        return new RoomListValue3D(list);
    }

    private static int countByType(TileGridValue3D grid, CellType type) {
        int n = 0;
        for (int i = 0; i < grid.cellCount(); i++) {
            if (grid.cells()[i] == type) n++;
        }
        return n;
    }

    @Test
    public void sameFloorTwoRoomsConnectedByHallway() {
        // Two rooms on floor 0, edge connects them.
        RoomListValue3D rs = rooms(
                3, 0.5f, 5, 1.5f,
                20, 0.5f, 5, 1.5f);
        EdgeGraphValue edges = new EdgeGraphValue(2, new int[][] { { 0, 1 } });
        TileGridValue3D grid = AStarCorridorPathfinder3D.carve(
                30, 5, 30, rs, edges, AStarCorridorPathfinder2D.DEFAULT_WEIGHTS);
        assertTrue(countByType(grid, CellType.HALLWAY) > 0, "expected hallway carved");
    }

    @Test
    public void multiFloorRoomsTriggerStairs() {
        // Two rooms, one on floor 0, one on floor 2 — must use stairs.
        RoomListValue3D rs = rooms(
                3, 0.5f, 5, 1.5f,
                20, 2.5f, 20, 1.5f);
        EdgeGraphValue edges = new EdgeGraphValue(2, new int[][] { { 0, 1 } });
        TileGridValue3D grid = AStarCorridorPathfinder3D.carve(
                30, 5, 30, rs, edges, AStarCorridorPathfinder2D.DEFAULT_WEIGHTS);
        int stairsUp = countByType(grid, CellType.STAIR_UP);
        int stairsDown = countByType(grid, CellType.STAIR_DOWN);
        assertTrue(stairsUp >= 2,
                "going up 2 floors should produce at least 2 STAIR_UP cells, got " + stairsUp);
        assertEquals(stairsUp, stairsDown, "STAIR_UP and STAIR_DOWN should pair 1:1");
    }

    @Test
    public void sameFloorEdgeProducesNoStairs() {
        RoomListValue3D rs = rooms(
                3, 1.5f, 5, 1.5f,
                20, 1.5f, 5, 1.5f);
        EdgeGraphValue edges = new EdgeGraphValue(2, new int[][] { { 0, 1 } });
        TileGridValue3D grid = AStarCorridorPathfinder3D.carve(
                30, 5, 30, rs, edges, AStarCorridorPathfinder2D.DEFAULT_WEIGHTS);
        assertEquals(0, countByType(grid, CellType.STAIR_UP),
                "same-floor connection should not create stairs");
        assertEquals(0, countByType(grid, CellType.STAIR_DOWN));
    }

    @Test
    public void roomsArePreservedDuringCarving() {
        RoomListValue3D rs = rooms(
                10, 0.5f, 10, 2.0f,
                10, 2.5f, 10, 2.0f);
        EdgeGraphValue edges = new EdgeGraphValue(2, new int[][] { { 0, 1 } });
        TileGridValue3D grid = AStarCorridorPathfinder3D.carve(
                30, 5, 30, rs, edges, AStarCorridorPathfinder2D.DEFAULT_WEIGHTS);
        // Room A interior cells (8..12 in X/Z, floor 0) should remain ROOM.
        for (int x = 8; x < 12; x++) {
            for (int z = 8; z < 12; z++) {
                assertEquals(CellType.ROOM, grid.at(x, 0, z),
                        "room A cell (" + x + ",0," + z + ") was overwritten");
            }
        }
    }

    @Test
    public void carveIsDeterministic() {
        RoomListValue3D rs = rooms(
                3, 0.5f, 3, 1.5f,
                20, 1.5f, 15, 1.5f,
                10, 3.5f, 25, 1.5f);
        EdgeGraphValue edges = new EdgeGraphValue(3, new int[][] {
                { 0, 1 }, { 1, 2 }, { 0, 2 } });
        TileGridValue3D a = AStarCorridorPathfinder3D.carve(
                30, 5, 30, rs, edges, AStarCorridorPathfinder2D.DEFAULT_WEIGHTS);
        TileGridValue3D b = AStarCorridorPathfinder3D.carve(
                30, 5, 30, rs, edges, AStarCorridorPathfinder2D.DEFAULT_WEIGHTS);
        for (int i = 0; i < a.cellCount(); i++) {
            assertEquals(a.cells()[i], b.cells()[i], "cell " + i + " mismatch");
        }
    }

    @Test
    public void zeroEdgesLeavesOnlyRooms() {
        RoomListValue3D rs = rooms(
                5, 0.5f, 5, 1.5f,
                15, 2.5f, 15, 1.5f);
        EdgeGraphValue edges = new EdgeGraphValue(2, new int[0][]);
        TileGridValue3D grid = AStarCorridorPathfinder3D.carve(
                30, 5, 30, rs, edges, AStarCorridorPathfinder2D.DEFAULT_WEIGHTS);
        assertEquals(0, countByType(grid, CellType.HALLWAY));
        assertEquals(0, countByType(grid, CellType.STAIR_UP));
        assertEquals(0, countByType(grid, CellType.STAIR_DOWN));
    }
}
