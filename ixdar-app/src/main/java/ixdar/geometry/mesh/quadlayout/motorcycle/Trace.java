package ixdar.geometry.mesh.quadlayout.motorcycle;

import java.util.ArrayList;
import java.util.List;

/**
 * One motorcycle trace: iso-line state, collision history, and segment chain.
 */
public final class Trace {

    public final int traceId;
    public final int originNodeId;
    public final int singularityVertexId;
    public final TraceAxis spawnAxis;
    public final int spawnSign;
    public final boolean featureTrace;

    public final List<MetOtherTraceEntry> metOtherTraces = new ArrayList<>();
    public final List<TraceSegment> segments = new ArrayList<>();
    public final List<Integer> arcNodeIds = new ArrayList<>();

    public ChartWalker.State state;
    public double parametricLengthSoFar;
    public boolean alive = true;
    public int currentNodeId;
    public double eppsteinStopLength = Double.NaN;
    public boolean sawFirstSectorCollision;

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
            float startU, float startV, boolean featureTrace) {
        this.traceId = traceId;
        this.originNodeId = originNodeId;
        this.singularityVertexId = singularityVertexId;
        this.spawnAxis = port.axis;
        this.spawnSign = port.sign;
        this.featureTrace = featureTrace;
        this.state = new ChartWalker.State(port.activeFace, startU, startV, port.axis, port.sign);
        this.currentNodeId = originNodeId;
        this.arcNodeIds.add(originNodeId);
    }

    /**
     * Lyon 2021 §3 signed angle αij at the start of trace ti.
     *
     * <p>
     * αij is the signed (ccw) angle of the right triangle whose legs Sij
     * (length {@code lij} along ti's forward) and Sji (length {@code lji}
     * along tj's forward) meet at the intersection node. Equivalently it is
     * the signed angle from ti's forward direction to the vector from
     * singularity i to singularity j, i.e. {@code lij · ti.forward − lji ·
     * tj.forward}. Positive when j lies on ti's ccw side; in (−π/2, π/2) for
     * non-collinear configurations.
     *
     * @param tiAxis parametric axis of trace ti at the intersection face
     * @param tiSign sign of ti along {@code tiAxis}
     * @param tjAxis parametric axis of trace tj at the same intersection face
     *               (in ti's local chart)
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
     * Lyon §3 two-sided stopping test: one met trace with angle in {@code [0, α]}
     * and one with angle in {@code [-α, 0]}.
     *
     * @param alphaBound maximum deviation in radians
     * @return true when this trace should stop
     */
    public boolean satisfiesLyonStop(double alphaBound) {
        if (featureTrace) {
            return false;
        }
        boolean positive = false;
        boolean negative = false;
        for (MetOtherTraceEntry entry : metOtherTraces) {
            if (entry.signedAngle >= 0.0 && entry.signedAngle <= alphaBound) {
                positive = true;
            }
            if (entry.signedAngle <= 0.0 && entry.signedAngle >= -alphaBound) {
                negative = true;
            }
        }
        return positive && negative;
    }

    /**
     * Lyon §3 {@code ni*}: the closest meeting on this trace whose
     * companion-trace origin sits inside ti's π/2-sector (i.e. {@code |αij| <
     * π/4}, equivalent to {@code theirParametricLength < ourParametricLength}).
     * This is the intersection at which Lyon §4.2's validity constraint
     * anchors — at least one arc in {@code Si*} (origin to {@code ni*}) must
     * quantize to ≥ 1.
     *
     * @return the {@link MetOtherTraceEntry} for {@code ni*}, or {@code null}
     *         if this trace has no meeting in its π/2-sector
     */
    public MetOtherTraceEntry firstSectorMeeting() {
        MetOtherTraceEntry best = null;
        for (MetOtherTraceEntry entry : metOtherTraces) {
            if (entry.theirParametricLength >= entry.ourParametricLength) {
                continue;
            }
            if (best == null || entry.ourParametricLength < best.ourParametricLength) {
                best = entry;
            }
        }
        return best;
    }

    /**
     * Record a meeting with another trace and apply Lyon / Eppstein bookkeeping.
     *
     * @param other       other trace
     * @param ourLength   parametric length along this trace to the intersection
     * @param theirLength parametric length along the other trace to the
     *                    intersection
     * @param alphaIj     Lyon §3 signed αij from {@code computeAlphaIj} at this
     *                    meeting
     * @param alphaBound  Lyon α in radians
     */
    public void recordMeeting(Trace other, double ourLength, double theirLength,
            double alphaIj, double alphaBound) {
        metOtherTraces.add(new MetOtherTraceEntry(other.traceId, alphaIj, ourLength, theirLength));
        if (!sawFirstSectorCollision && theirLength < ourLength
                && Math.abs(alphaIj) < Math.PI / 4.0) {
            sawFirstSectorCollision = true;
            if (Double.isNaN(eppsteinStopLength)) {
                eppsteinStopLength = ourLength;
            }
        }
        if (satisfiesLyonStop(alphaBound)) {
            alive = false;
        }
    }
}
