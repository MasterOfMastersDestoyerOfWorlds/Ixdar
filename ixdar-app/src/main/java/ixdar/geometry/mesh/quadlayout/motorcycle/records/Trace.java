package ixdar.geometry.mesh.quadlayout.motorcycle.records;

import java.util.ArrayList;
import java.util.List;

import ixdar.geometry.mesh.quadlayout.motorcycle.ChartWalker;

/**
 * One motorcycle trace: iso-line state, collision history, and segment chain.
 */
public final class Trace {

    public final int traceId;
    public final int originNodeId;
    public final int singularityVertexId;
    public final TraceAxis spawnAxis;
    public final int spawnSign;
    public final TracePort spawnPort;
    public final boolean featureTrace;

    public final List<MetOtherTraceEntry> metOtherTraces = new ArrayList<>();
    public final List<TraceSegment> segments = new ArrayList<>();
    public final List<Integer> arcNodeIds = new ArrayList<>();

    /**
     * Arc ids along this trace in travel order, filled by the post-build
     * subdivision pass; {@code chainArcIds.get(k)} spans parametric lengths
     * {@code [chainNodeLengths.get(k), chainNodeLengths.get(k + 1))}.
     */
    public final List<Integer> chainArcIds = new ArrayList<>();

    /**
     * Cumulative parametric length at each chain node, parallel to
     * {@link #arcNodeIds}; one entry longer than {@link #chainArcIds}.
     */
    public final List<Double> chainNodeLengths = new ArrayList<>();

    public ChartWalker.State state;
    public double parametricLengthSoFar;
    public boolean alive = true;
    public int currentNodeId;
    public double eppsteinStopLength = Double.NaN;
    public boolean sawFirstSectorCollision;

    /**
     * Ordinal of the current face visit, incremented every time the trace enters a
     * face (edge crossing or vertex fan transition; spawn is visit 0). Stamped onto
     * laid {@link TraceSegment}s so meetings can be identified combinatorially as
     * chord pairs.
     */
    public int faceVisitCount;

    /**
     * Serial of this trace's latest enqueued event. A trace has exactly one live
     * pending event; any state change enqueues a successor with a higher serial, so
     * the main loop drops an event whose serial no longer matches — exact
     * supersession, replacing the parametric-length staleness epsilon.
     */
    public int pendingEventSerial;

    /**
     * Creates a live motorcycle trace from a spawn port.
     *
     * @param traceId             unique trace id
     * @param originNodeId        T-mesh node at trace origin
     * @param singularityVertexId singularity vertex id, or -1 for feature traces
     * @param port                spawn port
     * @param startU              initial u
     * @param startV              initial v
     * @param featureTrace        true for immortal boundary/feature traces
     */
    public Trace(int traceId, int originNodeId, int singularityVertexId, TracePort port,
            double startU, double startV, boolean featureTrace) {
        this.traceId = traceId;
        this.originNodeId = originNodeId;
        this.singularityVertexId = singularityVertexId;
        this.spawnAxis = port.axis;
        this.spawnSign = port.sign;
        this.spawnPort = port;
        this.featureTrace = featureTrace;
        this.state = new ChartWalker.State(port.activeFace, startU, startV, port.axis, port.sign);
        this.currentNodeId = originNodeId;
        this.arcNodeIds.add(originNodeId);
    }

    /**
     * Locate the arc containing parametric length {@code parametricLength} along
     * this trace, using the chain tables built by the subdivision pass.
     *
     * @param parametricLength distance from the trace origin in chart units
     * @return arc id of the containing arc, or -1 when the trace has no arcs
     */
    public int arcAtParametricLength(double parametricLength) {
        if (chainArcIds.isEmpty()) {
            return -1;
        }
        int low = 0;
        int high = chainArcIds.size() - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (chainNodeLengths.get(mid) <= parametricLength) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return chainArcIds.get(low);
    }

    /**
     * Signed αij at the start of trace ti: the ccw angle from ti's forward
     * direction to {@code lij · ti.forward − lji · tj.forward}, positive when j
     * lies on ti's ccw side.
     *
     * <p>See also: Lyon 2021 Section 3
     *
     * @param tiAxis parametric axis of trace ti at the intersection face
     * @param tiSign sign of ti along {@code tiAxis}
     * @param tjAxis parametric axis of trace tj at the same intersection face (in
     *               ti's local chart)
     * @param tjSign sign of tj along {@code tjAxis}
     * @param lij    parametric length along ti from i to the intersection
     * @param lji    parametric length along tj from j to the intersection
     * @return signed αij in radians in (−π, π]
     */
    public static double computeAlphaIj(TraceAxis tiAxis, int tiSign,
            TraceAxis tjAxis, int tjSign, double lij, double lji) {
        double[] tiForward = tiAxis.direction(tiSign);
        double[] tjForward = tjAxis.direction(tjSign);
        double cross = tiForward[0] * tjForward[1] - tiForward[1] * tjForward[0];
        double dot = tiForward[0] * tjForward[0] + tiForward[1] * tjForward[1];
        return Math.atan2(-lji * cross, lij - lji * dot);
    }

    /**
     * Record a meeting with another trace and apply stopping test bookkeeping.
     *
     * @param other        other trace
     * @param ourLength    parametric length along this trace to the intersection
     * @param theirLength  parametric length along the other trace to the
     *                     intersection
     * @param alphaIj      signed αij from {@code computeAlphaIj} at this meeting
     * @param alphaBound   maximum deviation in radians
     * @param ourAxis      this trace's travel axis in the meeting face's chart
     * @param ourSign      this trace's travel sign along {@code ourAxis}
     * @param otherAxis    the other trace's travel axis in the same chart
     * @param otherSign    the other trace's travel sign along {@code otherAxis}
     * @param ourVisitId   this trace's face-visit ordinal at the meeting
     * @param otherVisitId the other trace's face-visit ordinal at the meeting
     */
    public void recordMeeting(Trace other, double ourLength, double theirLength,
            double alphaIj, double alphaBound,
            TraceAxis ourAxis, int ourSign, TraceAxis otherAxis, int otherSign,
            int ourVisitId, int otherVisitId) {
        metOtherTraces.add(new MetOtherTraceEntry(other.traceId, alphaIj, ourLength, theirLength,
                ourAxis, ourSign, otherAxis, otherSign, ourVisitId, otherVisitId));
        if (other == this) {
            // A trace crossing its own earlier path must be noded (recorded
            // above) but is not a meeting with a nearby singularity, so it does
            // not count toward Lyon's two-sided α stop.
            return;
        }
        if (!sawFirstSectorCollision && theirLength < ourLength
                && Math.abs(alphaIj) < Math.PI / 4.0) {
            sawFirstSectorCollision = true;
            if (Double.isNaN(eppsteinStopLength)) {
                eppsteinStopLength = ourLength;
            }
        }
        if (featureTrace) {
            alive = true;
            return;
        }
        boolean positive = false;
        boolean negative = false;
        for (MetOtherTraceEntry entry : metOtherTraces) {
            if (entry.ourAxis == entry.otherAxis) {
                continue;
            }
            if (entry.signedAngle >= 0.0 && entry.signedAngle <= alphaBound) {
                positive = true;
            }
            if (entry.signedAngle <= 0.0 && entry.signedAngle >= -alphaBound) {
                negative = true;
            }
        }
        if (positive && negative) {
            alive = false;
        }
    }
}
