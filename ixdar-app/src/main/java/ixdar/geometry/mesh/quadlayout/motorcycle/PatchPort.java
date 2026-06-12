package ixdar.geometry.mesh.quadlayout.motorcycle;

/**
 * One directed arc-end at a T-mesh node, used by the arrangement walk that
 * assembles patches. The direction is the travel direction leaving the node
 * along the directed arc this port represents (so an arc's in-port points back
 * along the trace), expressed in {@link #activeFace}'s chart with axis-aligned
 * integer components.
 */
public final class PatchPort {

    public final int nodeId;
    public final int arcId;

    /** True for the arc's start-node port (travel leaves the node forward). */
    public final boolean outgoing;

    public final int directionU;
    public final int directionV;
    public final int traceId;

    /**
     * Active face whose chart {@link #directionU}/{@link #directionV} are
     * expressed in, or -1 at interior intersection nodes where all incident
     * ports already share one chart. Vertex-located nodes need it: their ports
     * arrive through different fan faces, so cyclic ordering must go through
     * the vertex fan instead of a single-chart angle.
     */
    public final int activeFace;

    /** Cyclic ordering key around the node; assigned before sorting. */
    public double sortKey;

    /**
     * Creates one directed arc-end port.
     *
     * @param nodeId     T-mesh node the port sits at
     * @param arcId      arc this port belongs to
     * @param outgoing   true at the arc's start node, false at its end node
     * @param directionU chart-u component of the leaving travel direction
     * @param directionV chart-v component of the leaving travel direction
     * @param traceId    trace owning the arc
     * @param activeFace chart face of the direction, or -1 off-vertex
     */
    public PatchPort(int nodeId, int arcId, boolean outgoing,
            int directionU, int directionV, int traceId, int activeFace) {
        this.nodeId = nodeId;
        this.arcId = arcId;
        this.outgoing = outgoing;
        this.directionU = directionU;
        this.directionV = directionV;
        this.traceId = traceId;
        this.activeFace = activeFace;
    }
}
