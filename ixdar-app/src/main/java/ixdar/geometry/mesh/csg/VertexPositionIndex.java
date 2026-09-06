package ixdar.geometry.mesh.csg;

import java.util.Arrays;

/**
 * Exact-position lookup from a vertex's coordinates to its index, matching a boolean's output
 * vertices bit for bit to the input vertices the kernel copied. Negative zero folds into zero.
 */
public final class VertexPositionIndex {

    /** Minimum table size, so tiny meshes still get a sparse table. */
    public static final int MINIMUM_CAPACITY = 16;

    /** Multiplier from a 64-bit mix used to spread coordinate bits across the table. */
    public static final long MIX_MULTIPLIER = 0x9E3779B97F4A7C15L;

    /** Shift applied when folding a mixed 64-bit hash down to a table slot. */
    public static final int MIX_SHIFT = 32;

    /** X coordinate bits per slot. */
    public final long[] slotX;

    /** Y coordinate bits per slot. */
    public final long[] slotY;

    /** Z coordinate bits per slot. */
    public final long[] slotZ;

    /** Vertex index per slot, {@code -1} for an empty slot. */
    public final int[] slotValue;

    /** Table size minus one; the table size is a power of two. */
    public final int mask;

    /**
     * Allocate a table with room for the given number of vertices at low load.
     *
     * @param expectedCount vertices that will be added
     */
    public VertexPositionIndex(int expectedCount) {
        int capacity = MINIMUM_CAPACITY;
        while (capacity < expectedCount * 2) {
            capacity <<= 1;
        }
        slotX = new long[capacity];
        slotY = new long[capacity];
        slotZ = new long[capacity];
        slotValue = new int[capacity];
        Arrays.fill(slotValue, -1);
        mask = capacity - 1;
    }

    /**
     * Record a vertex at a position; a later vertex at the same position keeps the earlier index.
     *
     * @param x x coordinate
     * @param y y coordinate
     * @param z z coordinate
     * @param vertex index to store for the position
     */
    public void put(double x, double y, double z, int vertex) {
        long bitsX = bits(x);
        long bitsY = bits(y);
        long bitsZ = bits(z);
        int slot = slotOf(bitsX, bitsY, bitsZ);
        while (slotValue[slot] != -1) {
            if (slotX[slot] == bitsX && slotY[slot] == bitsY && slotZ[slot] == bitsZ) {
                return;
            }
            slot = (slot + 1) & mask;
        }
        slotX[slot] = bitsX;
        slotY[slot] = bitsY;
        slotZ[slot] = bitsZ;
        slotValue[slot] = vertex;
    }

    /**
     * The vertex recorded at a position.
     *
     * @param x x coordinate
     * @param y y coordinate
     * @param z z coordinate
     * @return the vertex index, or {@code -1} when no vertex sits exactly there
     */
    public int find(double x, double y, double z) {
        long bitsX = bits(x);
        long bitsY = bits(y);
        long bitsZ = bits(z);
        int slot = slotOf(bitsX, bitsY, bitsZ);
        while (slotValue[slot] != -1) {
            if (slotX[slot] == bitsX && slotY[slot] == bitsY && slotZ[slot] == bitsZ) {
                return slotValue[slot];
            }
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    /**
     * Bit pattern of a coordinate with negative zero folded into zero.
     *
     * @param coordinate the coordinate
     * @return its canonical bits
     */
    private static long bits(double coordinate) {
        return Double.doubleToLongBits(coordinate + 0.0);
    }

    /**
     * Home slot of a position.
     *
     * @param bitsX canonical x bits
     * @param bitsY canonical y bits
     * @param bitsZ canonical z bits
     * @return slot index within the table
     */
    private int slotOf(long bitsX, long bitsY, long bitsZ) {
        long mixed = (bitsX * MIX_MULTIPLIER + bitsY) * MIX_MULTIPLIER + bitsZ;
        mixed *= MIX_MULTIPLIER;
        return (int) (mixed >>> MIX_SHIFT) & mask;
    }
}
