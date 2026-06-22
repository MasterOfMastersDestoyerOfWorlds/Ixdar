package ixdar.geometry.mesh.quadlayout.motorcycle.records;

/**
 * Outgoing parametric port at a singularity: a face corner plus axis-aligned
 * chart direction (QEx §4.3 Algorithm 4).
 */
public final class TracePort {

    public final int singularityVertexId;
    public final int activeFace;
    public final int cornerIndex;
    public final TraceAxis axis;
    public final int sign;

    /**
     * Describes an outgoing iso-line port at a singularity corner.
     *
     * @param singularityVertexId mesh vertex id of the singularity
     * @param activeFace          active face index containing the port
     * @param cornerIndex         corner index in {@code [0, 3)} on that face
     * @param axis                parametric axis of the outgoing trace
     * @param sign                +1 or -1 along the axis
     */
    public TracePort(int singularityVertexId, int activeFace, int cornerIndex, TraceAxis axis, int sign) {
        this.singularityVertexId = singularityVertexId;
        this.activeFace = activeFace;
        this.cornerIndex = cornerIndex;
        this.axis = axis;
        this.sign = sign;
    }
}
