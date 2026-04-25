package unit.procgen.dungeon.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.procgen.dungeon.player.PlayerSpawner;
import ixdar.procgen.dungeon.player.SpawnPoint;
import ixdar.procgen.dungeon.values.RoomListValue3D;
import ixdar.procgen.dungeon.values.RoomListValue3D.Room;

public class PlayerSpawnerTest {

    private static RoomListValue3D rooms(float... data) {
        // (cx, cy, cz, halfXZ) per 4 floats; halfY fixed at 0.5
        List<Room> list = new ArrayList<>();
        for (int i = 0; i < data.length; i += 4) {
            list.add(new Room(i / 4, data[i], data[i + 1], data[i + 2],
                    data[i + 3], 0.5f, data[i + 3]));
        }
        return new RoomListValue3D(list);
    }

    @Test
    public void emptyRoomListThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> PlayerSpawner.pick(rooms(), 1f, 30, 5, 30, 0.3f, 0.2f));
    }

    @Test
    public void singleRoomCenteredOnGridSpawnsAtWorldOrigin() {
        // For a 30x5x30 grid with cellSize=1, GridToMesh3D centers at world origin with
        // offsetX = -15, offsetY = -2.5, offsetZ = -15. A start room at grid center (15, 2.5, 15)
        // -> world center (0, 0, 0). Capsule rests on room floor: y = floorY + halfH + radius.
        // floorY = -2.5 + (2.5 - 0.5)*1 = -0.5. spawn.y = -0.5 + 0.3 + 0.2 = 0.
        RoomListValue3D r = rooms(15f, 2.5f, 15f, 2f);
        SpawnPoint sp = PlayerSpawner.pick(r, 1f, 30, 5, 30, 0.3f, 0.2f);
        assertEquals(0f, sp.position().x(), 1e-4f);
        assertEquals(0f, sp.position().y(), 1e-4f);
        assertEquals(0f, sp.position().z(), 1e-4f);
        assertEquals(0f, sp.pitchDegrees(), 1e-4f);
        // Yaw arbitrary for single-room (defaults to 0 = facing +X).
        assertEquals(0f, sp.yawDegrees(), 1e-4f);
    }

    @Test
    public void multiRoomYawPointsTowardRoomOne() {
        // Room 0 at grid center (15, 2.5, 15), room 1 at (25, 2.5, 15) -> world (10, 0, 0)
        // direction is +X, atan2(dz=0, dx=10) = 0 radians = 0 degrees.
        RoomListValue3D r = rooms(
                15f, 2.5f, 15f, 2f,
                25f, 2.5f, 15f, 1.5f);
        SpawnPoint sp = PlayerSpawner.pick(r, 1f, 30, 5, 30, 0.3f, 0.2f);
        assertEquals(0f, sp.yawDegrees(), 1e-3f, "room[1] is +X of room[0] => yaw 0");
    }

    @Test
    public void multiRoomYawTowardOtherDirections() {
        // Room 1 at (15, 2.5, 25): world (0, 0, 10). +Z direction. atan2(10, 0) = pi/2 = 90°.
        RoomListValue3D r = rooms(
                15f, 2.5f, 15f, 2f,
                15f, 2.5f, 25f, 1.5f);
        SpawnPoint sp = PlayerSpawner.pick(r, 1f, 30, 5, 30, 0.3f, 0.2f);
        assertEquals(90f, sp.yawDegrees(), 1e-3f, "+Z direction => yaw 90");
    }

    @Test
    public void capsuleRestsOnRoomFloor() {
        // Room at grid floor 1 (y range [1, 2]). cellSize=2 -> world Y room range [0, 2]
        //   (offsetY = -5, room minY world = -5 + 1*2 = -3. Hmm wait, for gridH=5, offsetY = -5.)
        // floorY = offsetY + (centerY - halfY)*cellSize = -5 + (1.5-0.5)*2 = -5+2 = -3.
        // spawn.y = -3 + 0.3 + 0.2 = -2.5.
        RoomListValue3D r = rooms(15f, 1.5f, 15f, 2f);
        SpawnPoint sp = PlayerSpawner.pick(r, 2f, 30, 5, 30, 0.3f, 0.2f);
        assertEquals(-2.5f, sp.position().y(), 1e-4f, "capsule should rest on room floor");
    }

    @Test
    public void deterministicForFixedInputs() {
        RoomListValue3D r = rooms(
                15f, 2.5f, 15f, 2f,
                10f, 2.5f, 5f, 1.5f);
        SpawnPoint a = PlayerSpawner.pick(r, 1f, 30, 5, 30, 0.3f, 0.2f);
        SpawnPoint b = PlayerSpawner.pick(r, 1f, 30, 5, 30, 0.3f, 0.2f);
        assertEquals(a.position().x(), b.position().x(), 0f);
        assertEquals(a.position().y(), b.position().y(), 0f);
        assertEquals(a.position().z(), b.position().z(), 0f);
        assertEquals(a.yawDegrees(), b.yawDegrees(), 0f);
    }

    @Test
    public void spawnPositionIsInsideRoomXZBounds() {
        // Sanity: regardless of cellSize, the spawn's XZ should fall inside room[0]'s footprint.
        RoomListValue3D r = rooms(15f, 2.5f, 15f, 2f);
        for (float cs : new float[] { 0.5f, 1f, 2f, 0.0667f }) {
            SpawnPoint sp = PlayerSpawner.pick(r, cs, 30, 5, 30, 0.3f, 0.2f);
            float roomMinX = -15 * cs + (15 - 2) * cs;
            float roomMaxX = -15 * cs + (15 + 2) * cs;
            assertTrue(sp.position().x() >= roomMinX - 1e-4f
                    && sp.position().x() <= roomMaxX + 1e-4f,
                    "spawn X outside room at cellSize=" + cs);
        }
    }
}
