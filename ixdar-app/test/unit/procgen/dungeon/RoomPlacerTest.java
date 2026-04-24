package unit.procgen.dungeon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.procgen.dungeon.algo.RoomPlacer;
import ixdar.procgen.dungeon.values.RoomListValue;
import ixdar.procgen.dungeon.values.RoomListValue.Room;

public class RoomPlacerTest {

    @Test
    public void sameSeedProducesIdenticalPlacement() {
        RoomListValue a = RoomPlacer.place(42L, 30, 30, 15, 3, 8, 1000);
        RoomListValue b = RoomPlacer.place(42L, 30, 30, 15, 3, 8, 1000);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i), b.get(i), "room " + i + " must match across runs");
        }
    }

    @Test
    public void differentSeedsProduceDifferentPlacements() {
        RoomListValue a = RoomPlacer.place(1L, 30, 30, 15, 3, 8, 1000);
        RoomListValue b = RoomPlacer.place(2L, 30, 30, 15, 3, 8, 1000);
        // Not a strict guarantee but overwhelmingly likely: first room x-center should differ
        // for two uncorrelated seeds. If this ever fires on a real seed collision, it's still
        // informational — rerun with different seeds.
        if (a.size() > 0 && b.size() > 0) {
            assertFalse(a.get(0).equals(b.get(0)), "different seeds should produce different first rooms");
        }
    }

    @Test
    public void noRoomsOverlapWithBuffer() {
        RoomListValue rooms = RoomPlacer.place(7L, 30, 30, 15, 3, 8, 2000);
        for (int i = 0; i < rooms.size(); i++) {
            for (int j = i + 1; j < rooms.size(); j++) {
                Room a = rooms.get(i);
                Room b = rooms.get(j);
                // 1-unit buffer: gap on at least one axis must be >= 1 unit.
                float gapX = Math.max(0, Math.max(a.minX() - b.maxX(), b.minX() - a.maxX()));
                float gapY = Math.max(0, Math.max(a.minY() - b.maxY(), b.minY() - a.maxY()));
                assertTrue(gapX >= 1 || gapY >= 1,
                        "rooms " + i + " and " + j + " violate 1-unit buffer: gapX=" + gapX + " gapY=" + gapY);
            }
        }
    }

    @Test
    public void allRoomsFitInGrid() {
        int w = 30, h = 20;
        RoomListValue rooms = RoomPlacer.place(11L, w, h, 10, 3, 6, 1000);
        for (int i = 0; i < rooms.size(); i++) {
            Room r = rooms.get(i);
            assertTrue(r.minX() >= 0, "room " + i + " min x < 0: " + r.minX());
            assertTrue(r.minY() >= 0, "room " + i + " min y < 0: " + r.minY());
            assertTrue(r.maxX() <= w, "room " + i + " max x > grid: " + r.maxX());
            assertTrue(r.maxY() <= h, "room " + i + " max y > grid: " + r.maxY());
        }
    }

    @Test
    public void roomIdsAreSequential() {
        RoomListValue rooms = RoomPlacer.place(3L, 30, 30, 10, 3, 6, 1000);
        for (int i = 0; i < rooms.size(); i++) {
            assertEquals(i, rooms.get(i).id(), "room id should match index");
        }
    }

    @Test
    public void rejectsInvalidSizeRange() {
        assertThrows(IllegalArgumentException.class,
                () -> RoomPlacer.place(0L, 30, 30, 5, 0, 5, 100),
                "minSize 0 must reject");
        assertThrows(IllegalArgumentException.class,
                () -> RoomPlacer.place(0L, 30, 30, 5, 6, 5, 100),
                "minSize > maxSize must reject");
    }

    @Test
    public void rejectsTooSmallGrid() {
        assertThrows(IllegalArgumentException.class,
                () -> RoomPlacer.place(0L, 4, 4, 5, 3, 5, 100),
                "grid smaller than maxSize must reject");
    }

    @Test
    public void maxAttemptsCapsTheLoop() {
        // Impossible to fit 100 8x8 rooms in 20x20 with buffer, but the call must return rather
        // than loop forever.
        RoomListValue rooms = RoomPlacer.place(0L, 20, 20, 100, 8, 8, 500);
        assertTrue(rooms.size() < 100, "should hit attempt cap before placing all rooms");
    }
}
