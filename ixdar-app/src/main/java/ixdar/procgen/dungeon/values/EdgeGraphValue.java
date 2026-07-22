package ixdar.procgen.dungeon.values;

/**
 * Immutable undirected graph of edges between room indices, produced by Delaunay triangulation
 * and consumed by the corridor carving stage.
 *
 * <p>{@code edges} is an {@code int[edgeCount][2]} of {@code [fromRoomIdx, toRoomIdx]} rows,
 * defensive-copied on construction; index order within a row carries no meaning.
 */
public record EdgeGraphValue(int nodeCount, int[][] edges) {

    /**
     * Validates and defensive-copies the edge array.
     *
     * @throws IllegalArgumentException if {@code nodeCount} is negative, any row is not exactly
     *     length 2, or any endpoint index is outside {@code [0, nodeCount)}
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
     * Number of edges in the graph.
     *
     * @return {@code edges.length}
     */
    public int edgeCount() {
        return edges.length;
    }

    /** Returns a defensive copy of the {@code i}th edge. */
    public int[] edge(int i) {
        return new int[] { edges[i][0], edges[i][1] };
    }
}
