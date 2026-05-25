package ixdar.geometry.mesh.quadlayout.motorcycle;

/**
 * One straight iso-line chord of a trace on a single triangle, stored in that
 * face's local chart.
 */
public final class TraceSegment {

    public final int traceId;
    public final int activeFace;
    public final float entryU;
    public final float entryV;
    public final float exitU;
    public final float exitV;
    public final TraceAxis axis;
    public final int sign;
    public final double parametricLengthAtEntry;
    public final float isoValue;
    public final float spanStart;
    public final float spanEnd;

    /**
     * Stores one iso-line chord on a single triangle face.
     *
     * @param traceId                 owning trace id
     * @param activeFace              active face index
     * @param entryU                  entry u
     * @param entryV                  entry v
     * @param exitU                   exit u
     * @param exitV                   exit v
     * @param axis                    parametric axis
     * @param sign                    direction sign along axis
     * @param parametricLengthAtEntry trace parametric length accumulated from the
     *                                trace origin up to this chord's entry
     */
    public TraceSegment(int traceId, int activeFace,
            float entryU, float entryV, float exitU, float exitV,
            TraceAxis axis, int sign, double parametricLengthAtEntry) {
        this.traceId = traceId;
        this.activeFace = activeFace;
        this.entryU = entryU;
        this.entryV = entryV;
        this.exitU = exitU;
        this.exitV = exitV;
        this.axis = axis;
        this.sign = sign;
        this.parametricLengthAtEntry = parametricLengthAtEntry;
        this.isoValue = axis.holdsUConstant() ? entryU : entryV;
        float spanA = axis.holdsUConstant() ? entryV : entryU;
        float spanB = axis.holdsUConstant() ? exitV : exitU;
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
