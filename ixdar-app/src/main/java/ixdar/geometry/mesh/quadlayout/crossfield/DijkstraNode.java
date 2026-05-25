package ixdar.geometry.mesh.quadlayout.crossfield;


public final class DijkstraNode implements Comparable<DijkstraNode> {
    public final float distance;
    public final int vertexOrFace;

    /**
     * Heap entry for Dijkstra/Voronoi-style traversals.
     *
     * @param distance     priority key (smaller = earlier pop)
     * @param vertexOrFace mesh vertex or face id this entry refers to
     */
    public DijkstraNode(float distance, int vertexOrFace) {
        this.distance = distance;
        this.vertexOrFace = vertexOrFace;
    }

    /**
     * {@inheritDoc} Orders by ascending {@code distance} for the priority queue.
     */
    @Override
    public int compareTo(DijkstraNode other) {
        return Float.compare(this.distance, other.distance);
    }
}
