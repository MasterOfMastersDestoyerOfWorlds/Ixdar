package ixdar.procgen.dungeon.player;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.api.Vector3Field;

/**
 * Picks a {@link SpawnPoint} at room[0]'s center — the start room {@code RoomPlacer3D}
 * guarantees as the first vertex — facing room[1] when one exists. The position is a capsule
 * center resting on the floor, so it only suits a {@link PlayerController} of the same capsule
 * dimensions.
 */
public final class PlayerSpawner {
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_0 = 0f;

    private PlayerSpawner() {
    }

    /**
     * Picks a spawn point at room[0]'s center, sitting the capsule on the room's floor and
     * yawing the camera toward room[1] when one exists.
     *
     * @param rooms        room point cloud from the rooms graph — must have at least one vertex
     * @param halfExtents  per-vertex room half extents in dense vertex order
     * @param cellSize     world units per grid cell (used to position room floor against
     *                     {@code GridToMesh3D}'s origin-centered mesh layout)
     * @param gridW        grid width along X (must match room placement's gridW)
     * @param gridH        grid height along Y (number of floors)
     * @param gridD        grid depth along Z
     * @param halfHeight   capsule body half-height
     * @param radius       capsule radius (sphere caps)
     * @throws IllegalArgumentException if {@code rooms} has no vertices
     * @return spawn point with world-space capsule center, yaw, and pitch (pitch is always 0)
     */
    public static SpawnPoint pick(MeshTopology rooms,
                                  Vector3Field halfExtents,
                                  float cellSize,
                                  int gridW, int gridH, int gridD,
                                  float halfHeight, float radius) {
        if (rooms.vertexCount() == 0) {
            throw new IllegalArgumentException("cannot spawn into an empty room list");
        }
        Vector3f start = rooms.vertexPosition(rooms.vertexIdAt(0), new Vector3f());
        // World offsets — must mirror GridToMesh3D's centering.
        float offsetX = -gridW * cellSize * NUM_0_5;
        float offsetY = -gridH * cellSize * NUM_0_5;
        float offsetZ = -gridD * cellSize * NUM_0_5;
        // Room center in grid units -> world.
        float wx = offsetX + start.x * cellSize;
        float wz = offsetZ + start.z * cellSize;
        float floorY = offsetY + (start.y - halfExtents.getY(0)) * cellSize;
        float wy = floorY + (halfHeight + radius);

        // Yaw toward room[1] if there is one, otherwise face +X (yaw = 0).
        float yawDeg = NUM_0;
        if (rooms.vertexCount() >= 2) {
            Vector3f target = rooms.vertexPosition(rooms.vertexIdAt(1), new Vector3f());
            float tx = offsetX + target.x * cellSize;
            float tz = offsetZ + target.z * cellSize;
            float dx = tx - wx;
            float dz = tz - wz;
            // Camera3D yaw convention: forward = (cos yaw, _, sin yaw). atan2(dz, dx) gives yaw in radians.
            yawDeg = (float) Math.toDegrees(Math.atan2(dz, dx));
        }
        return new SpawnPoint(new Vector3f(wx, wy, wz), yawDeg, NUM_0);
    }
}
