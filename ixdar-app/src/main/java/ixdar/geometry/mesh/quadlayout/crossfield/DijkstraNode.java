package ixdar.geometry.mesh.quadlayout.crossfield;


public final class DijkstraNode implements Comparable<DijkstraNode> {
    public final float distance;
    public final int vertexOrFace;

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
