package ixdar.geometry.mesh.quadlayout.motorcycle.records;

/**
 * Chain-length interval that one alignment/boundary mesh edge covers along its
 * owning feature trace. Lets the patch-boundary walk translate a boundary
 * stretch running along the feature curve into the T-mesh arcs underneath it,
 * in the correct direction.
 */
public final class FeatureEdgeSpan {

    public final int traceId;

    /** Mesh vertex id at the chain-entry end of the edge (orients the span). */
    public final int fromVertexId;

    /** Cumulative chain length at the edge's {@code fromVertexId} end. */
    public final double entryLength;

    /** Cumulative chain length at the edge's far end. */
    public final double exitLength;

    /**
     * Records the chain interval of one feature edge.
     *
     * @param traceId      owning feature trace id
     * @param fromVertexId vertex at the chain-entry end of the edge
     * @param entryLength  cumulative chain length at {@code fromVertexId}
     * @param exitLength   cumulative chain length at the far end
     */
    public FeatureEdgeSpan(int traceId, int fromVertexId, double entryLength, double exitLength) {
        this.traceId = traceId;
        this.fromVertexId = fromVertexId;
        this.entryLength = entryLength;
        this.exitLength = exitLength;
    }
}
