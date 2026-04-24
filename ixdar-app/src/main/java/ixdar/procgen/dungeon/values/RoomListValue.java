package ixdar.procgen.dungeon.values;

import java.util.List;

/**
 * Immutable list of rectangular rooms on a 2D grid, produced by the room-placement stage of the
 * dungeon generator and consumed by Delaunay / MST / A* stages.
 *
 * <p>3D rooms are deferred to PROCGEN-7 and will live in a sibling {@code RoomList3DValue} type.
 */
public record RoomListValue(List<Room> rooms) {

    public RoomListValue {
        rooms = List.copyOf(rooms);
    }

    public int size() {
        return rooms.size();
    }

    public Room get(int index) {
        return rooms.get(index);
    }

    /**
     * A single axis-aligned room. Coordinates are in grid units (floats for sub-cell room
     * centers, e.g. a 3-wide room centered at integer position has centerX at half-integer).
     *
     * @param id            stable zero-based room index
     * @param centerX       room center on the X axis in grid units
     * @param centerY       room center on the Y axis in grid units
     * @param halfExtentX   half the room's width
     * @param halfExtentY   half the room's height
     */
    public record Room(int id, float centerX, float centerY, float halfExtentX, float halfExtentY) {
        public float minX() { return centerX - halfExtentX; }
        public float maxX() { return centerX + halfExtentX; }
        public float minY() { return centerY - halfExtentY; }
        public float maxY() { return centerY + halfExtentY; }

        /** True if this room's AABB intersects {@code other}. */
        public boolean intersects(Room other) {
            return minX() < other.maxX() && maxX() > other.minX()
                    && minY() < other.maxY() && maxY() > other.minY();
        }
    }
}
