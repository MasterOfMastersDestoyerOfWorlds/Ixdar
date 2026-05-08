package ixdar.procgen.dungeon.values;

/**
 * Immutable 3D grid of {@link CellType} values, indexed (x, y, z) with x as the inner-most
 * stride. Produced by {@code AStarCorridorPathfinder3D} and consumed by {@code GridToMesh3D}.
 *
 * <p>{@code height} is the number of vertical layers (Y axis); typical default is 5 per
 * vazgriz. Floor index 0 is the bottom floor.
 */
public record TileGridValue3D(int width, int height, int depth, CellType[] cells) {
    public static final String STR = ",";
    public static final String X = "x";

    /**
     * Validates dimensions and clones the cells array so the value is fully immutable.
     *
     * @throws IllegalArgumentException if any dimension is negative, or if {@code cells.length}
     *     does not equal {@code width * height * depth}
     */
    public TileGridValue3D {
        if (width < 0 || height < 0 || depth < 0) {
            throw new IllegalArgumentException("dimensions must be non-negative");
        }
        int expected = width * height * depth;
        if (cells.length != expected) {
            throw new IllegalArgumentException(
                    "cells length " + cells.length + " does not match width*height*depth " + expected);
        }
        cells = cells.clone();
    }

    /** Returns the cell at {@code (x, y, z)}. (0,0,0) is the lower-back-left corner. */
    public CellType at(int x, int y, int z) {
        if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth) {
            throw new IndexOutOfBoundsException(
                    "(" + x + STR + y + STR + z + ") outside " + width + X + height + X + depth);
        }
        return cells[index(x, y, z)];
    }

    /** Linearized index: {@code x + width * (z + depth * y)}. Y-major, then Z, then X. */
    public int index(int x, int y, int z) {
        return x + width * (z + depth * y);
    }

    /**
     * Backing array length.
     *
     * @return {@code width * height * depth}
     */
    public int cellCount() {
        return cells.length;
    }
}
