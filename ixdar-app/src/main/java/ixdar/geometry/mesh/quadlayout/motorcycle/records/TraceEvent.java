package ixdar.geometry.mesh.quadlayout.motorcycle.records;

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
    public final double u;
    public final double v;
    public final TraceSegment otherSegment;

    /**
     * Serial stamped from the trace's {@code pendingEventSerial} at enqueue
     * time. The main loop drops the event when the trace's serial has moved on
     * — exact supersession of outdated events, no parametric-length epsilon.
     * Synthetic events handled synchronously (never queued) carry the trace's
     * current serial.
     */
    public final int serial;

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
     * @param serial           owning trace's event serial at enqueue time
     */
    public TraceEvent(int type, double parametricLength, int traceId, int otherTraceId,
            int activeFace, double u, double v, TraceSegment otherSegment, int serial) {
        this.type = type;
        this.parametricLength = parametricLength;
        this.traceId = traceId;
        this.otherTraceId = otherTraceId;
        this.activeFace = activeFace;
        this.u = u;
        this.v = v;
        this.otherSegment = otherSegment;
        this.serial = serial;
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
