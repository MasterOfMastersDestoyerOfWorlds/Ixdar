package ixdar.procgen.dungeon.values;

/**
 * Immutable undirected graph of edges between room indices. Produced by Delaunay triangulation,
 * filtered by the MST + extra-edge pass, and consumed by the A* corridor carving stage.
 *
 * <p>{@code nodeCount} is typically the size of the source {@link RoomListValue}.
 * {@code edges} is a tightly-packed {@code int[edgeCount][2]} where each row is
 * {@code [fromRoomIdx, toRoomIdx]}. Ordering of the two indices within a row is not
 * significant (the graph is undirected), but the array is defensive-copied so consumers can
 * iterate without mutation concerns.
 */
public record EdgeGraphValue(int nodeCount, int[][] edges) {

    /**
     * TODO: document {@code EdgeGraphValue}.
     *
     * @throws IllegalArgumentException TODO: describe
     */
    public EdgeGraphValue {
        if (nodeCount < 0) {
            throw new IllegalArgumentException("nodeCount must be non-negative");
        }
        int[][] copy = new int[edges.length][];
        for (int i = 0; i < edges.length; i++) {
            int[] edge = edges[i];
            if (edge.length != 2) {
                throw new IllegalArgumentException(
                        "each edge must be [fromIdx, toIdx] (length 2), got length " + edge.length);
            }
            if (edge[0] < 0 || edge[0] >= nodeCount || edge[1] < 0 || edge[1] >= nodeCount) {
                throw new IllegalArgumentException(
                        "edge [" + edge[0] + "," + edge[1] + "] out of range [0," + nodeCount + ")");
            }
            copy[i] = new int[] { edge[0], edge[1] };
        }
        edges = copy;
    }

    /**
     * TODO: document {@code edgeCount}.
     *
     * @return TODO: describe
     */
    public int edgeCount() {
        return edges.length;
    }

    /** Returns a defensive copy of the {@code i}th edge. */
    public int[] edge(int i) {
        return new int[] { edges[i][0], edges[i][1] };
    }
}
