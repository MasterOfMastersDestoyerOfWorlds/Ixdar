package ixdar.procgen.dungeon.values;

public enum CellType {
    /** Unoccupied cell. */
    EMPTY,
    /** Inside the AABB of a placed room. */
    ROOM,
    /** Carved corridor cell on a single floor. */
    HALLWAY,
    /** Lower half of a stair (the cell where the player begins ascending). */
    STAIR_UP,
    /** Upper half of a stair (the cell where the player finishes descending). */
    STAIR_DOWN;
}
