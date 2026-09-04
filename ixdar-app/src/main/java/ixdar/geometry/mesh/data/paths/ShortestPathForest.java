package ixdar.geometry.mesh.data.paths;

/**
 * The product of one multi-source Dijkstra run: per-vertex distance to the
 * nearest source and the parent that relaxation reached the vertex from.
 */
public final class ShortestPathForest {

    /**
     * Distance to the nearest source per vertex slot;
     * {@link Double#POSITIVE_INFINITY} for unreached slots.
     */
    public final double[] distance;

    /**
     * Parent vertex each slot was relaxed from: a source is its own parent,
     * unreached slots hold -1.
     */
    public final int[] parent;

    /**
     * Wraps a finished run's arrays.
     *
     * @param distance distance to the nearest source per vertex slot
     * @param parent   parent vertex per slot
     */
    public ShortestPathForest(double[] distance, int[] parent) {
        this.distance = distance;
        this.parent = parent;
    }
}
