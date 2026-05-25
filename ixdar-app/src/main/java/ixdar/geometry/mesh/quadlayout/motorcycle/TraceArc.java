package ixdar.geometry.mesh.quadlayout.motorcycle;

/**
 * One arc of the motorcycle T-mesh between two nodes along a trace.
 */
public final class TraceArc {

    public final int arcId;
    public final int traceId;
    public final int startNodeId;
    public final int endNodeId;
    public final TraceAxis axis;
    public final double parametricLength;
    public int oppositeArcId = -1;

    /**
     * Creates one parametric arc between two T-mesh nodes.
     *
     * @param arcId            unique arc id
     * @param traceId          owning trace id
     * @param startNodeId      start node id
     * @param endNodeId        end node id
     * @param axis             parametric axis
     * @param parametricLength chart-space length
     */
    public TraceArc(int arcId, int traceId, int startNodeId, int endNodeId,
            TraceAxis axis, double parametricLength) {
        this.arcId = arcId;
        this.traceId = traceId;
        this.startNodeId = startNodeId;
        this.endNodeId = endNodeId;
        this.axis = axis;
        this.parametricLength = parametricLength;
    }
}
