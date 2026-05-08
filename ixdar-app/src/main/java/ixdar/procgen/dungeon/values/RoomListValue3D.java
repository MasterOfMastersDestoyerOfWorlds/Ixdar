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
     * TODO: document {@code RoomListValue3D}.
     */
    public RoomListValue3D {
        rooms = List.copyOf(rooms);
    }

    /**
     * TODO: document {@code size}.
     *
     * @return TODO: describe
     */
    public int size() {
        return rooms.size();
    }

    /**
     * TODO: document {@code get}.
     *
     * @param index TODO: describe
     * @return TODO: describe
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
         * TODO: document {@code minX}.
         *
         * @return TODO: describe
         */
        public float minX() { return centerX - halfExtentX; }
        /**
         * TODO: document {@code maxX}.
         *
         * @return TODO: describe
         */
        public float maxX() { return centerX + halfExtentX; }
        /**
         * TODO: document {@code minY}.
         *
         * @return TODO: describe
         */
        public float minY() { return centerY - halfExtentY; }
        /**
         * TODO: document {@code maxY}.
         *
         * @return TODO: describe
         */
        public float maxY() { return centerY + halfExtentY; }
        /**
         * TODO: document {@code minZ}.
         *
         * @return TODO: describe
         */
        public float minZ() { return centerZ - halfExtentZ; }
        /**
         * TODO: document {@code maxZ}.
         *
         * @return TODO: describe
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
