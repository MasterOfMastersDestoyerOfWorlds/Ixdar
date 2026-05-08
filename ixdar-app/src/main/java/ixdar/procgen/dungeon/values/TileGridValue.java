package ixdar.procgen.dungeon.values;

/**
 * Immutable 2D grid of {@link CellType} values, row-major. Produced by the A* corridor carving
 * stage and consumed by the grid-to-mesh stage.
 *
 * <p>3D grids (with stair cells) are deferred to PROCGEN-7 and will live in a sibling
 * {@code TileGrid3DValue}.
 */
public record TileGridValue(int width, int height, CellType[] cells) {

    /**
     * TODO: document {@code TileGridValue}.
     *
     * @throws IllegalArgumentException TODO: describe
     */
    public TileGridValue {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("width and height must be non-negative");
        }
        int expected = width * height;
        if (cells.length != expected) {
            throw new IllegalArgumentException(
                    "cells length " + cells.length + " does not match width*height " + expected);
        }
        cells = cells.clone();
    }

    /** Returns the cell at {@code (x, y)}. Zero is lower-left by convention. */
    public CellType at(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException("(" + x + "," + y + ") outside " + width + "x" + height);
        }
        return cells[y * width + x];
    }

    /** Returns the backing array's length (== width * height). */
    public int cellCount() {
        return cells.length;
    }
}
