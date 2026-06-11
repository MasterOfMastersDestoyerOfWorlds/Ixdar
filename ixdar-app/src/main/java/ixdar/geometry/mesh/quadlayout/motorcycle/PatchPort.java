package ixdar.geometry.mesh.quadlayout.motorcycle;

/**
 * One directed arc-end at a T-mesh node, used by the arrangement walk that
 * assembles patches. The direction is the travel direction leaving the node
 * along the directed arc this port represents (so an arc's in-port points back
 * along the trace), expressed in the node's chart with axis-aligned integer
 * components.
 */
public final class PatchPort {

    public final int nodeId;
    public final int arcId;

    /** True for the arc's start-node port (travel leaves the node forward). */
    public final boolean outgoing;

    public final int directionU;
    public final int directionV;
    public final int traceId;

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
     */
    public PatchPort(int nodeId, int arcId, boolean outgoing,
            int directionU, int directionV, int traceId) {
        this.nodeId = nodeId;
        this.arcId = arcId;
        this.outgoing = outgoing;
        this.directionU = directionU;
        this.directionV = directionV;
        this.traceId = traceId;
    }
}
