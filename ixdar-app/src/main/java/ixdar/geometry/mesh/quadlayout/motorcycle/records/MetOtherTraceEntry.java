package ixdar.geometry.mesh.quadlayout.motorcycle.records;

/**
 * Record of a prior trace-trace meeting used by Lyon's two-sided stopping test
 * and, after the build, by the arrangement walk that assembles patches (the
 * stored axes give both traces' travel directions in the shared face chart at
 * the meeting, which fixes the cyclic order of arcs around the node).
 */
public final class MetOtherTraceEntry {

    public final int otherTraceId;
    public final double signedAngle;
    public final double ourParametricLength;
    public final double theirParametricLength;

    /** Travel axis of the owning trace in the meeting face's chart. */
    public final TraceAxis ourAxis;

    /** Travel sign of the owning trace along {@link #ourAxis}. */
    public final int ourSign;

    /** Travel axis of the other trace in the same chart. */
    public final TraceAxis otherAxis;

    /** Travel sign of the other trace along {@link #otherAxis}. */
    public final int otherSign;

    /**
     * Owning trace's face-visit ordinal at this meeting. Together with
     * {@link #otherVisitId} this identifies the crossing combinatorially: a pair
     * of face-visit chords crosses at most once, so meeting dedupe compares
     * visit pairs instead of parametric positions.
     */
    public final int ourVisitId;

    /** Other trace's face-visit ordinal at this meeting. */
    public final int otherVisitId;

    /**
     * T-mesh node id at this meeting, shared between both traces. Set by
     * {@code MotorcycleGraph.handleIntersection} after the intersection node is
     * created, or {@code -1} if not yet wired (e.g. recordMeeting called
     * synthetically in unit tests).
     */
    public int intersectionNodeId = -1;

    /**
     * Records one prior trace-trace meeting for Lyon's stopping test.
     *
     * @param otherTraceId          id of the other trace
     * @param signedAngle           signed angle from this trace to the other at
     *                              origin
     * @param ourParametricLength   parametric length along this trace to meeting
     * @param theirParametricLength parametric length along the other trace to
     *                              meeting
     * @param ourAxis               owning trace's travel axis in the meeting chart
     * @param ourSign               owning trace's travel sign along its axis
     * @param otherAxis             other trace's travel axis in the same chart
     * @param otherSign             other trace's travel sign along its axis
     * @param ourVisitId            owning trace's face-visit ordinal at the meeting
     * @param otherVisitId          other trace's face-visit ordinal at the meeting
     */
    public MetOtherTraceEntry(int otherTraceId, double signedAngle,
            double ourParametricLength, double theirParametricLength,
            TraceAxis ourAxis, int ourSign, TraceAxis otherAxis, int otherSign,
            int ourVisitId, int otherVisitId) {
        this.otherTraceId = otherTraceId;
        this.signedAngle = signedAngle;
        this.ourParametricLength = ourParametricLength;
        this.theirParametricLength = theirParametricLength;
        this.ourAxis = ourAxis;
        this.ourSign = ourSign;
        this.otherAxis = otherAxis;
        this.otherSign = otherSign;
        this.ourVisitId = ourVisitId;
        this.otherVisitId = otherVisitId;
    }
}
