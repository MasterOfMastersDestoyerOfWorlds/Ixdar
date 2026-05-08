package ixdar.procgen.dungeon.values;

import java.util.List;

/**
 * Immutable list of axis-aligned 3D rooms (rectangular prisms) on a 3D grid. Produced by
 * {@code RoomPlacer3D} and consumed by 3D Delaunay / MST / A* stages.
 *
 * <p>Rooms always sit with their floor on integer Y values (no half-floors). Vertical extent
 * is typically 1 cell, but the type allows multi-cell tall rooms.
 */
public record RoomListValue3D(List<Room> rooms) {

    /**
     * Defensive-copies the room list so callers can't mutate it through the original reference.
     */
    public RoomListValue3D {
        rooms = List.copyOf(rooms);
    }

    /**
     * Number of rooms in the list.
     *
     * @return {@code rooms.size()}
     */
    public int size() {
        return rooms.size();
    }

    /**
     * Room at the given index.
     *
     * @param index zero-based room index, must satisfy {@code 0 <= index < size()}
     * @return the {@link Room} at that position
     */
    public Room get(int index) {
        return rooms.get(index);
    }

    /**
     * One axis-aligned 3D room.
     *
     * @param id            stable zero-based room index
     * @param centerX       room center on the X axis in grid units
     * @param centerY       room center on the Y axis (floor index + halfHeightY)
     * @param centerZ       room center on the Z axis in grid units
     * @param halfExtentX   half the room's width  along X
     * @param halfExtentY   half the room's height along Y (typically 0.5 for single-cell-tall)
     * @param halfExtentZ   half the room's depth  along Z
     */
    public record Room(int id,
                       float centerX, float centerY, float centerZ,
                       float halfExtentX, float halfExtentY, float halfExtentZ) {
        /**
         * Left edge of the room AABB on the X axis.
         *
         * @return {@code centerX - halfExtentX}
         */
        public float minX() { return centerX - halfExtentX; }
        /**
         * Right edge of the room AABB on the X axis.
         *
         * @return {@code centerX + halfExtentX}
         */
        public float maxX() { return centerX + halfExtentX; }
        /**
         * Floor of the room on the Y axis.
         *
         * @return {@code centerY - halfExtentY}
         */
        public float minY() { return centerY - halfExtentY; }
        /**
         * Ceiling of the room on the Y axis.
         *
         * @return {@code centerY + halfExtentY}
         */
        public float maxY() { return centerY + halfExtentY; }
        /**
         * Near edge of the room AABB on the Z axis.
         *
         * @return {@code centerZ - halfExtentZ}
         */
        public float minZ() { return centerZ - halfExtentZ; }
        /**
         * Far edge of the room AABB on the Z axis.
         *
         * @return {@code centerZ + halfExtentZ}
         */
        public float maxZ() { return centerZ + halfExtentZ; }

        /** True if this room's AABB intersects {@code other} (strict overlap, not edge-touching). */
        public boolean intersects(Room other) {
            return minX() < other.maxX() && maxX() > other.minX()
                    && minY() < other.maxY() && maxY() > other.minY()
                    && minZ() < other.maxZ() && maxZ() > other.minZ();
        }
    }
}
