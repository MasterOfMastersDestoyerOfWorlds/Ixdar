package ixdar.geometry.mesh.quadlayout.motorcycle;

/**
 * Record of a prior trace-trace meeting used by Lyon's two-sided stopping test.
 */
public final class MetOtherTraceEntry {

    public final int otherTraceId;
    public final double signedAngle;
    public final double ourParametricLength;
    public final double theirParametricLength;

    /**
     * Records one prior trace-trace meeting for Lyon's stopping test.
     *
     * @param otherTraceId          id of the other trace
     * @param signedAngle           signed angle from this trace to the other at
     *                              origin
     * @param ourParametricLength   parametric length along this trace to meeting
     * @param theirParametricLength parametric length along the other trace to
     *                              meeting
     */
    public MetOtherTraceEntry(int otherTraceId, double signedAngle,
            double ourParametricLength, double theirParametricLength) {
        this.otherTraceId = otherTraceId;
        this.signedAngle = signedAngle;
        this.ourParametricLength = ourParametricLength;
        this.theirParametricLength = theirParametricLength;
    }
}
