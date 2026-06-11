package ixdar.geometry.mesh.quadlayout.motorcycle;

/**
 * One transversal crossing of a mesh edge by a motorcycle trace, recorded
 * during patch assembly so the patch-boundary walk can resolve each severed
 * mesh edge to the T-mesh arc that severed it.
 */
public final class EdgeCrossing {

    public final int traceId;

    /** Parametric length along the trace at the crossing point. */
    public final double parametricLength;

    /**
     * Records one trace-over-edge crossing.
     *
     * @param traceId          id of the crossing trace
     * @param parametricLength distance from the trace origin to the crossing
     */
    public EdgeCrossing(int traceId, double parametricLength) {
        this.traceId = traceId;
        this.parametricLength = parametricLength;
    }
}
