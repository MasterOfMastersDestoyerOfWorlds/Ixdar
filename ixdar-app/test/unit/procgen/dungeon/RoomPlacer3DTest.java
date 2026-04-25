package unit.procgen.dungeon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.procgen.dungeon.algo.RoomPlacer3D;
import ixdar.procgen.dungeon.values.RoomListValue3D;
import ixdar.procgen.dungeon.values.RoomListValue3D.Room;

public class RoomPlacer3DTest {

    @Test
    public void sameSeedProducesIdenticalPlacement() {
        RoomListValue3D a = RoomPlacer3D.place(42L, 30, 5, 30, 15, 3, 8, 2000);
        RoomListValue3D b = RoomPlacer3D.place(42L, 30, 5, 30, 15, 3, 8, 2000);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i), b.get(i), "room " + i + " must match across runs");
        }
    }

    @Test
    public void noRoomsOverlapWithBuffer() {
        RoomListValue3D rooms = RoomPlacer3D.place(7L, 30, 5, 30, 15, 3, 8, 2000);
        for (int i = 0; i < rooms.size(); i++) {
            for (int j = i + 1; j < rooms.size(); j++) {
                Room a = rooms.get(i);
                Room b = rooms.get(j);
                // 1-unit buffer along at least one axis (rooms on different floors are
                // automatically separated by Y axis).
                float gapX = Math.max(0, Math.max(a.minX() - b.maxX(), b.minX() - a.maxX()));
                float gapY = Math.max(0, Math.max(a.minY() - b.maxY(), b.minY() - a.maxY()));
                float gapZ = Math.max(0, Math.max(a.minZ() - b.maxZ(), b.minZ() - a.maxZ()));
                assertTrue(gapX >= 1 || gapY >= 1 || gapZ >= 1,
                        "rooms " + i + " and " + j + " too close: gapX=" + gapX
                                + " gapY=" + gapY + " gapZ=" + gapZ);
            }
        }
    }

    @Test
    public void allRoomsFitInGrid() {
        int w = 30, h = 5, d = 30;
        RoomListValue3D rooms = RoomPlacer3D.place(11L, w, h, d, 10, 3, 6, 1000);
        for (int i = 0; i < rooms.size(); i++) {
            Room r = rooms.get(i);
            assertTrue(r.minX() >= 0, "room " + i + " minX < 0");
            assertTrue(r.maxX() <= w, "room " + i + " maxX > grid");
            assertTrue(r.minY() >= 0, "room " + i + " minY < 0");
            assertTrue(r.maxY() <= h, "room " + i + " maxY > grid");
            assertTrue(r.minZ() >= 0, "room " + i + " minZ < 0");
            assertTrue(r.maxZ() <= d, "room " + i + " maxZ > grid");
        }
    }

    @Test
    public void roomsDistributeAcrossFloors() {
        // With 5 floors and 25 rooms, we'd expect rooms on multiple floors most of the time.
        // Statistical test, not strict — but very high seed coverage should hit this.
        RoomListValue3D rooms = RoomPlacer3D.place(42L, 30, 5, 30, 25, 3, 6, 5000);
        boolean[] floorUsed = new boolean[5];
        for (int i = 0; i < rooms.size(); i++) {
            int floor = (int) Math.floor(rooms.get(i).centerY());
            floorUsed[floor] = true;
        }
        int floorsUsed = 0;
        for (boolean f : floorUsed) if (f) floorsUsed++;
        assertTrue(floorsUsed >= 2, "expected rooms on multiple floors with seed 42, got " + floorsUsed);
    }

    @Test
    public void rejectsTooSmallGrid() {
        assertThrows(IllegalArgumentException.class,
                () -> RoomPlacer3D.place(0L, 4, 5, 30, 5, 3, 5, 100));
        assertThrows(IllegalArgumentException.class,
                () -> RoomPlacer3D.place(0L, 30, 0, 30, 5, 3, 5, 100));
    }

    @Test
    public void singleFloorActsLike2D() {
        // gridH=1 -> all rooms on floor 0.
        RoomListValue3D rooms = RoomPlacer3D.place(0L, 30, 1, 30, 10, 3, 6, 1000);
        for (int i = 0; i < rooms.size(); i++) {
            assertEquals(0.5f, rooms.get(i).centerY(), 1e-4f, "room " + i + " not on floor 0");
        }
    }

    @Test
    public void roomZeroIsStartRoomAtGridCenter() {
        // Room 0 is now the guaranteed START room: centered on the grid (so it lands on the
        // world origin after GridToMesh3D centers the mesh) and on the middle floor.
        RoomListValue3D rooms = RoomPlacer3D.place(42L, 30, 5, 30, 15, 3, 6, 2000);
        Room start = rooms.get(0);
        // For 30x30 grid and a 4-cell start room, center should be at grid X=15, Z=15.
        assertEquals(15f, start.centerX(), 1e-4f, "start room should be centered on X");
        assertEquals(15f, start.centerZ(), 1e-4f, "start room should be centered on Z");
        // Middle floor of a 5-floor grid is index 2 (centerY = 2.5).
        assertEquals(2.5f, start.centerY(), 1e-4f, "start room should be on the middle floor");
    }

    @Test
    public void startRoomDoesNotOverlapRandomRooms() {
        // Random rooms should respect the buffer around the start room.
        RoomListValue3D rooms = RoomPlacer3D.place(7L, 30, 5, 30, 20, 3, 6, 3000);
        Room start = rooms.get(0);
        for (int i = 1; i < rooms.size(); i++) {
            Room r = rooms.get(i);
            float gapX = Math.max(0, Math.max(start.minX() - r.maxX(), r.minX() - start.maxX()));
            float gapY = Math.max(0, Math.max(start.minY() - r.maxY(), r.minY() - start.maxY()));
            float gapZ = Math.max(0, Math.max(start.minZ() - r.maxZ(), r.minZ() - start.maxZ()));
            assertTrue(gapX >= 1 || gapY >= 1 || gapZ >= 1,
                    "room " + i + " too close to start room");
        }
    }

    @Test
    public void noOverlapWithinSameFloor() {
        // Force gridH=1 to test horizontal collision logic against a 2D-equivalent setup.
        RoomListValue3D rooms = RoomPlacer3D.place(0L, 30, 1, 30, 10, 3, 6, 2000);
        for (int i = 0; i < rooms.size(); i++) {
            for (int j = i + 1; j < rooms.size(); j++) {
                Room a = rooms.get(i);
                Room b = rooms.get(j);
                assertFalse(a.intersects(b),
                        "rooms " + i + " and " + j + " intersect on a single floor");
            }
        }
    }
}
