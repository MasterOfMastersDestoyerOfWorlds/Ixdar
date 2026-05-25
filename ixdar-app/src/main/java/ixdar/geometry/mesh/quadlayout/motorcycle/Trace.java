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
    public final double spawnDirX;
    public final double spawnDirY;
    public final boolean featureTrace;

    public ChartWalker.State state;
    public double parametricLengthSoFar;
    public boolean alive = true;
    public int currentNodeId;
    public double eppsteinStopLength = Double.NaN;
    public boolean sawFirstSectorCollision;

    public final List<MetOtherTraceEntry> metOtherTraces = new ArrayList<>();
    public final List<TraceSegment> segments = new ArrayList<>();
    public final List<Integer> arcNodeIds = new ArrayList<>();

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
        double[] dir = port.axis.direction(port.sign);
        this.spawnDirX = dir[0];
        this.spawnDirY = dir[1];
        this.featureTrace = featureTrace;
        this.state = new ChartWalker.State(port.activeFace, startU, startV, port.axis, port.sign);
        this.currentNodeId = originNodeId;
        this.arcNodeIds.add(originNodeId);
    }

    /**
     * Signed angle from this trace's spawn direction to another trace's spawn
     * direction.
     *
     * @param other other trace
     * @return signed angle in radians
     */
    public double signedAngleTo(Trace other) {
        return UvPredicates.signedAngle(spawnDirX, spawnDirY, other.spawnDirX, other.spawnDirY);
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
     * Record a meeting with another trace and apply Lyon / Eppstein bookkeeping.
     *
     * @param other       other trace
     * @param ourLength   parametric length along this trace
     * @param theirLength parametric length along the other trace
     * @param alphaBound  Lyon α in radians
     */
    public void recordMeeting(Trace other, double ourLength, double theirLength, double alphaBound) {
        double angle = signedAngleTo(other);
        metOtherTraces.add(new MetOtherTraceEntry(other.traceId, angle, ourLength, theirLength));
        if (!sawFirstSectorCollision && theirLength < ourLength
                && angle > 0.0 && angle < Math.PI / 2.0) {
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
