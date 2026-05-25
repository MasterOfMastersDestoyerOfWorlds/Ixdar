package ixdar.geometry.mesh.quadlayout.motorcycle;

/**
 * Priority-queue event during motorcycle graph simulation.
 */
public final class TraceEvent implements Comparable<TraceEvent> {

    /** Advance to the next triangle-edge crossing. */
    public static final int TYPE_EDGE = 0;
    /** Two traces intersect inside a triangle chart. */
    public static final int TYPE_INTERSECTION = 1;
    /** Trace reaches a singularity. */
    public static final int TYPE_SINGULARITY = 2;
    /** Trace reaches a mesh boundary. */
    public static final int TYPE_BOUNDARY = 3;

    public final int type;
    public final double parametricLength;
    public final int traceId;
    public final int otherTraceId;
    public final int activeFace;
    public final float u;
    public final float v;
    public final TraceSegment otherSegment;

    /**
     * Schedules one simulation event on the motorcycle priority queue.
     *
     * @param type             event type constant
     * @param parametricLength parametric distance from trace origin
     * @param traceId          primary trace id
     * @param otherTraceId     other trace id for intersections, else -1
     * @param activeFace       face where the event occurs
     * @param u                event u
     * @param v                event v
     * @param otherSegment     matched other-trace segment for
     *                         {@link #TYPE_INTERSECTION} events, else {@code null}
     */
    public TraceEvent(int type, double parametricLength, int traceId, int otherTraceId,
            int activeFace, float u, float v, TraceSegment otherSegment) {
        this.type = type;
        this.parametricLength = parametricLength;
        this.traceId = traceId;
        this.otherTraceId = otherTraceId;
        this.activeFace = activeFace;
        this.u = u;
        this.v = v;
        this.otherSegment = otherSegment;
    }

    @Override
    public int compareTo(TraceEvent other) {
        int cmp = Double.compare(parametricLength, other.parametricLength);
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(traceId, other.traceId);
    }
}
