package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.TraceSegment;

/**
 * Per-arc geometry index: the strip of source active faces each arc's traced
 * curve passes through, and the lifted 3D polyline of that curve. Built in one
 * two-pointer merge per trace over its ordered segments and chain arcs —
 * the per-arc clipping is the same range-versus-segments sweep as
 * {@code LayoutPatchGeometry.arcPolylineForRange}, batched so the whole index
 * costs O(segments + arcs) per trace instead of a quadratic per-arc scan.
 */
public final class ArcStripIndex {

    public final MotorcycleGraph motorcycleGraph;

    /** Source active faces per arc id, travel order, consecutive dedupe. */
    public List<List<Integer>> stripFacesByArc;

    /** Lifted 3D polyline per arc id, travel order. */
    public List<List<Vector3f>> polylineByArc;

    /**
     * Stores inputs for the strip index.
     *
     * @param motorcycleGraph built T-mesh whose arcs get indexed
     */
    public ArcStripIndex(MotorcycleGraph motorcycleGraph) {
        this.motorcycleGraph = motorcycleGraph;
    }

    /**
     * Sweep every trace once, clipping each chain arc's parametric range
     * against the trace's segments to collect faces and lifted endpoints.
     *
     * @return this, with both per-arc indexes populated
     */
    public ArcStripIndex build() {
        int arcCount = motorcycleGraph.arcs.size();
        stripFacesByArc = new ArrayList<>(arcCount);
        polylineByArc = new ArrayList<>(arcCount);
        for (int arcId = 0; arcId < arcCount; arcId++) {
            stripFacesByArc.add(new ArrayList<>());
            polylineByArc.add(new ArrayList<>());
        }
        for (Trace trace : motorcycleGraph.traces) {
            sweepTrace(trace);
        }
        return this;
    }

    /**
     * Two-pointer merge of one trace's ordered segments against its ordered
     * chain arcs, attributing each overlap's face and clipped endpoints to the
     * arc.
     *
     * @param trace trace to sweep
     */
    private void sweepTrace(Trace trace) {
        int chainArcCount = trace.chainArcIds.size();
        if (chainArcCount == 0) {
            return;
        }
        int position = 0;
        for (TraceSegment segment : trace.segments) {
            double segmentStart = segment.parametricLengthAtEntry;
            double segmentEnd = segmentStart + segment.parametricLength();
            while (position < chainArcCount
                    && trace.chainNodeLengths.get(position + 1) <= segmentStart) {
                position++;
            }
            for (int scan = position; scan < chainArcCount; scan++) {
                double arcStart = trace.chainNodeLengths.get(scan);
                double arcEnd = trace.chainNodeLengths.get(scan + 1);
                if (arcStart >= segmentEnd) {
                    break;
                }
                double clipStart = Math.max(segmentStart, arcStart);
                double clipEnd = Math.min(segmentEnd, arcEnd);
                if (clipEnd < clipStart) {
                    continue;
                }
                int arcId = trace.chainArcIds.get(scan);
                List<Integer> strip = stripFacesByArc.get(arcId);
                if (strip.isEmpty() || strip.get(strip.size() - 1) != segment.activeFace) {
                    strip.add(segment.activeFace);
                }
                appendClipPoint(arcId, segment, clipStart - segmentStart);
                appendClipPoint(arcId, segment, clipEnd - segmentStart);
            }
        }
    }

    /**
     * Lift one clip offset on a segment's iso-line and append it to the arc's
     * polyline, skipping consecutive duplicates.
     *
     * @param arcId           arc the point belongs to
     * @param segment         trace segment supplying chart and iso value
     * @param offsetFromEntry parametric distance from the segment entry
     */
    private void appendClipPoint(int arcId, TraceSegment segment, double offsetFromEntry) {
        double entrySpan = segment.axis.holdsUConstant() ? segment.entryV : segment.entryU;
        double span = entrySpan + segment.sign * offsetFromEntry;
        double u = segment.axis.holdsUConstant() ? segment.isoValue : span;
        double v = segment.axis.holdsUConstant() ? span : segment.isoValue;
        Vector3f point = motorcycleGraph.parametricLift.liftToPosition(segment.activeFace, u, v);
        List<Vector3f> polyline = polylineByArc.get(arcId);
        if (!polyline.isEmpty() && polyline.get(polyline.size() - 1).distance(point) <= 0f) {
            return;
        }
        polyline.add(point);
    }
}
