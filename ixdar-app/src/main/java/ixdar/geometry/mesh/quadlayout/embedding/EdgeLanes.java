package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceSegment;

/**
 * Where each traced crossing may land on its source edge: the n crossings of one edge
 * take the uniform positions {@code i/(n+1)}, in their traced order, so the smallest
 * fragment is {@code 1/(n+1)} however close two traces run.
 *
 * <p>
 * See also: LCBK19 Section 6.1
 */
public final class EdgeLanes {

    /** Corners (and edges) of a triangle. */
    private static final int CORNERS = 3;

    /** Rows of the packing histograms before the tail is summarised. */
    private static final int HISTOGRAM_ROWS = 8;

    /** Source edge index meaning the cross field does not know the segment's exit edge. */
    private static final int UNKNOWN_EDGE = -1;

    public final MotorcycleGraph motorcycleGraph;

    /** The mesh the traces were laid on, whose edges carry the lanes. */
    public final HalfEdgeMesh sourceMesh;

    /** Lane position of each segment's exit crossing, in its edge's canonical direction. */
    public final Map<TraceSegment, Double> laneParameterBySegment = new IdentityHashMap<>();

    /** Crossings sharing each source active edge, indexed by active edge. */
    public int[] crossingCountBySourceEdge;

    /** Distinct traces crossing each source active face, indexed by active face. */
    public int[] traceCountBySourceFace;

    /** Most crossings any one source edge carries. */
    public int mostCrossingsOnAnEdge;

    /** Most distinct traces any one source face carries. */
    public int mostTracesOnAFace;

    /**
     * Closest two traced crossings come on one edge, before quantization. This is the
     * fragment the exact-parameter carve would have minted, so it is the direct
     * predictor of the needles downstream.
     */
    public double smallestTracedGap = 1.0;

    /** Furthest quantization moves a crossing along its edge, as a fraction of it. */
    public double worstLaneDeviation;

    /**
     * Stores the traced graph whose crossings are assigned lanes.
     *
     * @param motorcycleGraph traced T-mesh whose segments record the crossings
     */
    public EdgeLanes(MotorcycleGraph motorcycleGraph) {
        this.motorcycleGraph = motorcycleGraph;
        this.sourceMesh = motorcycleGraph.seamless.mesh;
    }

    /**
     * Groups every recorded crossing by the source edge it lands on, orders each
     * edge's crossings along it, and gives them the uniform lane positions.
     *
     * @return this, assigned
     */
    public EdgeLanes build() {
        int edgeCount = motorcycleGraph.seamless.crossField.edgeCount;
        int faceCount = motorcycleGraph.seamless.crossField.faceCount;
        crossingCountBySourceEdge = new int[edgeCount];
        traceCountBySourceFace = new int[faceCount];
        int[] lastTraceOnFace = new int[faceCount];
        Arrays.fill(lastTraceOnFace, UNKNOWN_EDGE);
        List<List<TraceSegment>> crossingsByEdge = new ArrayList<>(edgeCount);
        for (int edge = 0; edge < edgeCount; edge++) {
            crossingsByEdge.add(null);
        }
        for (Trace trace : motorcycleGraph.traces) {
            for (TraceSegment segment : trace.segments) {
                if (segment.activeFace >= 0 && segment.activeFace < faceCount
                        && lastTraceOnFace[segment.activeFace] != trace.traceId) {
                    lastTraceOnFace[segment.activeFace] = trace.traceId;
                    traceCountBySourceFace[segment.activeFace]++;
                }
                if (segment.exitLocalEdgeIndex < 0 || Double.isNaN(segment.exitEdgeParameter)) {
                    continue;
                }
                int sourceEdge = sourceEdgeActiveIndex(segment);
                if (sourceEdge == UNKNOWN_EDGE) {
                    continue;
                }
                if (crossingsByEdge.get(sourceEdge) == null) {
                    crossingsByEdge.set(sourceEdge, new ArrayList<>());
                }
                crossingsByEdge.get(sourceEdge).add(segment);
                crossingCountBySourceEdge[sourceEdge]++;
            }
        }
        assignLanes(crossingsByEdge);
        for (int face = 0; face < faceCount; face++) {
            mostTracesOnAFace = Math.max(mostTracesOnAFace, traceCountBySourceFace[face]);
        }
        return this;
    }

    /**
     * Sorts each edge's crossings along it and gives crossing {@code i} of {@code n}
     * the position {@code (i + 1) / (n + 1)}, recording how far that moved it.
     *
     * @param crossingsByEdge segments crossing each source active edge, with gaps
     */
    private void assignLanes(List<List<TraceSegment>> crossingsByEdge) {
        Comparator<TraceSegment> alongEdge =
                Comparator.comparingDouble(this::canonicalParameter);
        for (List<TraceSegment> crossings : crossingsByEdge) {
            if (crossings == null) {
                continue;
            }
            crossings.sort(alongEdge);
            mostCrossingsOnAnEdge = Math.max(mostCrossingsOnAnEdge, crossings.size());
            double previous = 0.0;
            for (TraceSegment segment : crossings) {
                double traced = canonicalParameter(segment);
                smallestTracedGap = Math.min(smallestTracedGap, traced - previous);
                previous = traced;
            }
            smallestTracedGap = Math.min(smallestTracedGap, 1.0 - previous);
            for (int lane = 0; lane < crossings.size(); lane++) {
                double position = (lane + 1.0) / (crossings.size() + 1.0);
                worstLaneDeviation = Math.max(worstLaneDeviation,
                        Math.abs(position - canonicalParameter(crossings.get(lane))));
                laneParameterBySegment.put(crossings.get(lane), position);
            }
        }
    }

    /**
     * The lane a segment's exit crossing must land on.
     *
     * @param segment segment whose exit crossing is being carved
     * @throws IllegalStateException when the segment records no exit crossing
     * @return its lane position in the edge's canonical direction
     */
    public double parameterOf(TraceSegment segment) {
        Double lane = laneParameterBySegment.get(segment);
        if (lane == null) {
            throw new IllegalStateException("segment on source face " + segment.activeFace
                    + " has no lane; its exit crossing was never collected, so the carve and"
                    + " the lane assignment disagree about which segments cross an edge");
        }
        return lane;
    }

    /**
     * Reports how tightly the traces pack, which is what decides whether the carve can
     * mint a measurable triangle.
     */
    public void report() {
        System.out.printf("[lanes] crossings per edge:%s | most=%d smallestTracedGap=%.3e"
                + " worstLaneDeviation=%.4f%n", histogramOf(crossingCountBySourceEdge),
                mostCrossingsOnAnEdge, smallestTracedGap, worstLaneDeviation);
        System.out.printf("[lanes] traces per face:%s | most=%d%n",
                histogramOf(traceCountBySourceFace), mostTracesOnAFace);
    }

    /**
     * A one-line histogram of a per-element count, with the tail summed into one row.
     *
     * @param countByElement the count per source element
     * @return the rendered rows, each {@code count:elements}
     */
    private String histogramOf(int[] countByElement) {
        int[] histogram = new int[HISTOGRAM_ROWS + 1];
        for (int count : countByElement) {
            histogram[Math.min(HISTOGRAM_ROWS, count)]++;
        }
        StringBuilder rows = new StringBuilder();
        for (int count = 1; count < HISTOGRAM_ROWS; count++) {
            rows.append(' ').append(count).append(':').append(histogram[count]);
        }
        return rows.append(" >=").append(HISTOGRAM_ROWS).append(':')
                .append(histogram[HISTOGRAM_ROWS]).toString();
    }

    /**
     * The source active edge index a segment exits through, resolved from the cross
     * field's edge map.
     *
     * @param segment segment whose exit edge is being resolved
     * @return the crossed edge's source active index, or {@link #UNKNOWN_EDGE} when
     *         the two vertices share no active edge
     */
    public int sourceEdgeActiveIndex(TraceSegment segment) {
        int fromVertexId = exitFromVertexId(segment);
        int toVertexId = exitToVertexId(segment);
        for (int index = 0; index < sourceMesh.vertexEdgeCount(fromVertexId); index++) {
            int edgeId = sourceMesh.vertexEdgeAt(fromVertexId, index);
            int halfEdge = sourceMesh.edgeHalfEdge(edgeId);
            int start = sourceMesh.halfEdgeVertex(halfEdge);
            int other = start == fromVertexId ? sourceMesh.halfEdgeEndVertex(halfEdge) : start;
            if (other != toVertexId) {
                continue;
            }
            Integer active = motorcycleGraph.seamless.crossField.edgeIdToActive.get(edgeId);
            return active == null ? UNKNOWN_EDGE : active;
        }
        return UNKNOWN_EDGE;
    }

    /**
     * Parameter of a crossing along its source edge, measured from the edge's
     * lower-id endpoint so crossings recorded from either incident face compare
     * directly.
     *
     * @param segment segment whose exit crossing is being measured
     * @return the crossing's traced parameter in the edge's canonical direction
     */
    public double canonicalParameter(TraceSegment segment) {
        return exitFromVertexId(segment) < exitToVertexId(segment)
                ? segment.exitEdgeParameter
                : 1.0 - segment.exitEdgeParameter;
    }

    /**
     * Source vertex the exit edge's parameter is measured from, in the recording
     * face's own orientation.
     *
     * @param segment segment whose exit edge is being read
     * @return the source vertex id at parameter zero
     */
    public int exitFromVertexId(TraceSegment segment) {
        return sourceMesh.faceVertexAt(sourceMesh.faceIdAt(segment.activeFace),
                segment.exitLocalEdgeIndex);
    }

    /**
     * Source vertex the exit edge's parameter runs toward, in the recording face's
     * own orientation.
     *
     * @param segment segment whose exit edge is being read
     * @return the source vertex id at parameter one
     */
    public int exitToVertexId(TraceSegment segment) {
        return sourceMesh.faceVertexAt(sourceMesh.faceIdAt(segment.activeFace),
                (segment.exitLocalEdgeIndex + 1) % CORNERS);
    }
}
