package ixdar.geometry.mesh.data;

/**
 * Packed {@code long} map keys for vertex pairs. Undirected keys canonicalize
 * with the smaller id in the high 32 bits; directed keys keep {@code from}
 * high. Every mesh edge-key map in the codebase uses this layout.
 */
public final class EdgeKey {

    private static final long LOW_MASK = 0xFFFFFFFFL;

    private EdgeKey() {
    }

    /**
     * Canonical key of the undirected edge {@code (a, b)}.
     *
     * @param a one endpoint vertex id
     * @param b the other endpoint vertex id
     * @return packed key, smaller id in the high 32 bits
     */
    public static long undirected(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << Integer.SIZE) | (hi & LOW_MASK);
    }

    /**
     * Key of the directed edge {@code (from, to)}; order is significant.
     *
     * @param from start vertex id
     * @param to end vertex id
     * @return packed key, {@code from} in the high 32 bits
     */
    public static long directed(int from, int to) {
        return ((long) from << Integer.SIZE) | (to & LOW_MASK);
    }

    /**
     * The smaller endpoint of an undirected key (the {@code from} of a directed one).
     *
     * @param key packed key
     * @return the high 32 bits
     */
    public static int minVertex(long key) {
        return (int) (key >>> Integer.SIZE);
    }

    /**
     * The larger endpoint of an undirected key (the {@code to} of a directed one).
     *
     * @param key packed key
     * @return the low 32 bits
     */
    public static int maxVertex(long key) {
        return (int) (key & LOW_MASK);
    }
}
