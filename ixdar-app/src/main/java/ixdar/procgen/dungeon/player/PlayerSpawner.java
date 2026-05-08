package ixdar.procgen.dungeon.player;

import ixdar.procgen.dungeon.physics.Vec3f;
import ixdar.procgen.dungeon.values.RoomListValue3D;
import ixdar.procgen.dungeon.values.RoomListValue3D.Room;

/**
 * Picks a {@link SpawnPoint} for the player at scene load.
 *
 * <p>Heuristic (this version): {@code RoomPlacer3D} guarantees room[0] is a start room at the
 * grid center, so we spawn at room[0]'s center. Yaw points toward room[1]'s center if it
 * exists (gives the player something to walk toward), otherwise faces along {@code +X}.
 *
 * <p>{@link RoomListValue3D.Room#centerY()} sits in the middle of the room's vertical extent.
 * The capsule center should rest with its bottom on the room floor:
 * {@code centerY = floorY + halfHeight + radius}. This method returns the spawn position the
 * caller should pass to a {@link PlayerController} of the given capsule dimensions.
 */
public final class PlayerSpawner {
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_0 = 0f;

    private PlayerSpawner() {
    }

    /**
     * TODO: document.
     *
     * @param rooms        result of {@code RoomPlacer3D.place} — must have at least one room
     * @param cellSize     world units per grid cell (used to position room floor against
     *                     {@code GridToMesh3D}'s origin-centered mesh layout)
     * @param gridW        grid width along X (must match {@code RoomPlacer3D}'s gridW)
     * @param gridH        grid height along Y (number of floors)
     * @param gridD        grid depth along Z
     * @param halfHeight   capsule body half-height
     * @param radius       capsule radius (sphere caps)
     * @throws IllegalArgumentException TODO: describe
     * @return TODO: describe
     */
    public static SpawnPoint pick(RoomListValue3D rooms,
                                  float cellSize,
                                  int gridW, int gridH, int gridD,
                                  float halfHeight, float radius) {
        if (rooms.size() == 0) {
            throw new IllegalArgumentException("cannot spawn into an empty room list");
        }
        Room start = rooms.get(0);
        // World offsets — must mirror GridToMesh3D's centering.
        float offsetX = -gridW * cellSize * NUM_0_5;
        float offsetY = -gridH * cellSize * NUM_0_5;
        float offsetZ = -gridD * cellSize * NUM_0_5;
        // Room center in grid units -> world.
        float wx = offsetX + start.centerX() * cellSize;
        float wz = offsetZ + start.centerZ() * cellSize;
        float floorY = offsetY + (start.centerY() - start.halfExtentY()) * cellSize;
        float wy = floorY + (halfHeight + radius);

        // Yaw toward room[1] if there is one, otherwise face +X (yaw = 0).
        float yawDeg = NUM_0;
        if (rooms.size() >= 2) {
            Room target = rooms.get(1);
            float tx = offsetX + target.centerX() * cellSize;
            float tz = offsetZ + target.centerZ() * cellSize;
            float dx = tx - wx;
            float dz = tz - wz;
            // Camera3D yaw convention: forward = (cos yaw, _, sin yaw). atan2(dz, dx) gives yaw in radians.
            yawDeg = (float) Math.toDegrees(Math.atan2(dz, dx));
        }
        return new SpawnPoint(new Vec3f(wx, wy, wz), yawDeg, NUM_0);
    }
}
