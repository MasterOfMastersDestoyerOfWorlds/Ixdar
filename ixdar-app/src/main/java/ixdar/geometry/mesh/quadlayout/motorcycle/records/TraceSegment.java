package ixdar.geometry.mesh.quadlayout.motorcycle.records;

/**
 * One straight iso-line chord of a trace on a single triangle, stored in that
 * face's local chart.
 */
public final class TraceSegment {

    public final int traceId;
    public final int activeFace;

    /**
     * Ordinal of the owning trace's face visit that laid this segment. Two
     * sub-chords of one traversal share a visit id; a later re-entry of the same
     * face (different level after loop holonomy) gets a new one. Together with
     * the trace id this combinatorially identifies the chord a segment belongs
     * to, which is what meeting dedupe keys on — a pair of chords crosses at
     * most once, no positional epsilon needed.
     */
    public final int visitId;
    public final double entryU;
    public final double entryV;
    public final double exitU;
    public final double exitV;
    public final TraceAxis axis;
    public final int sign;
    public final double parametricLengthAtEntry;
    public final double isoValue;
    public final double spanStart;
    public final double spanEnd;

    /**
     * Local edge index (0/1/2) of {@link #activeFace} that the chord exits through,
     * or {@code -1} when it ends inside the face — a trace termination, or a chord
     * cut short by an intersection event. Local edge {@code i} runs from corner
     * {@code i} to corner {@code (i + 1) % 3}.
     *
     * <p>The LCBK19 §6.1 embedding carve splits exactly this edge at exactly
     * {@link #exitEdgeParameter}; both are computed by the walker anyway, and
     * re-deriving them from the lifted 3D position is what forces geometric
     * tolerances into the embedding.
     */
    public int exitLocalEdgeIndex = -1;

    /**
     * Exact parameter of the exit point along {@link #exitLocalEdgeIndex}, running
     * from corner {@code exitLocalEdgeIndex} to the next corner. {@code NaN} when
     * the chord has no exit edge.
     */
    public double exitEdgeParameter = Double.NaN;

    /**
     * Local corner index (0/1/2) when the chord exits exactly through a corner of
     * {@link #activeFace}, else {@code -1}. The carve reuses that corner's existing
     * vertex rather than splitting an edge at its endpoint.
     */
    public int exitAtCorner = -1;

    /**
     * Stores one iso-line chord on a single triangle face.
     *
     * @param traceId                 owning trace id
     * @param activeFace              active face index
     * @param visitId                 ordinal of the owning trace's face visit
     * @param entryU                  entry u
     * @param entryV                  entry v
     * @param exitU                   exit u
     * @param exitV                   exit v
     * @param axis                    parametric axis
     * @param sign                    direction sign along axis
     * @param parametricLengthAtEntry trace parametric length accumulated from the
     *                                trace origin up to this chord's entry
     */
    public TraceSegment(int traceId, int activeFace, int visitId,
            double entryU, double entryV, double exitU, double exitV,
            TraceAxis axis, int sign, double parametricLengthAtEntry) {
        this.traceId = traceId;
        this.activeFace = activeFace;
        this.visitId = visitId;
        this.entryU = entryU;
        this.entryV = entryV;
        this.exitU = exitU;
        this.exitV = exitV;
        this.axis = axis;
        this.sign = sign;
        this.parametricLengthAtEntry = parametricLengthAtEntry;
        this.isoValue = axis.holdsUConstant() ? entryU : entryV;
        double spanA = axis.holdsUConstant() ? entryV : entryU;
        double spanB = axis.holdsUConstant() ? exitV : exitU;
        this.spanStart = Math.min(spanA, spanB);
        this.spanEnd = Math.max(spanA, spanB);
    }

    /**
     * Parametric length of this segment along its trace axis.
     *
     * @return chart-space length
     */
    public double parametricLength() {
        if (axis == TraceAxis.U) {
            return Math.abs(exitU - entryU);
        }
        return Math.abs(exitV - entryV);
    }
}
