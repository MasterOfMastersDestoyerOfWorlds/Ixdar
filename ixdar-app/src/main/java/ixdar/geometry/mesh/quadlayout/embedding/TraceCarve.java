package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceSegment;

/**
 * Carves every traced motorcycle path into the working copy as an edge path by
 * building the arrangement: all crossings split their edge's fragment at the
 * exact parameter first, then every stretch is a straight chord walked by
 * {@link FaceChordWalk} — no routing, no search.
 *
 * <p>
 * See also: LCBK19 Section 6.1
 */
public final class TraceCarve {

    /** Corners (and edges) of a triangle. */
    private static final int CORNERS = 3;

    /** Fallback split position when a crossing's local parameter degenerates. */
    private static final double EDGE_MIDPOINT = 0.5;

    /** Stranded node events logged before the report goes quiet. */
    private static final int STRANDED_SAMPLE_LIMIT = 8;

    public final HalfEdgeMesh sourceMesh;

    /**
     * Reserved crossing vertex per segment, materialized and claimed for its arc
     * before any chord is walked, so no arc can take another's passage.
     */
    public final Map<TraceSegment, Integer> waypointBySegment = new IdentityHashMap<>();

    public final EmbeddedMeshTopology topology;
    public final MotorcycleGraph motorcycleGraph;

    /** The exact straight-chord walker connecting consecutive carve points. */
    public final FaceChordWalk chordWalk;

    /** Copy vertex per T-mesh node id, filled by the node placement stage. */
    public final int[] vertexIdByNode;

    /** Embedded path per arc id, filled in place. */
    public final ArcEdgePath[] pathByArc;

    /** Crossings that landed exactly on an existing free vertex. */
    public int snappedCrossingCount;

    /** Crossings materialized by splitting their edge fragment. */
    public int splitCrossingCount;

    public int carvedArcCount;
    public int carvedTraceCount;

    /**
     * Node events whose parametric length precedes the segment they were taken in.
     */
    public int strandedNodeEventCount;

    /**
     * Stores the inputs for the carve and builds its chord walker.
     *
     * @param topology        working copy with provenance and claims
     * @param motorcycleGraph traced T-mesh to carve
     * @param vertexIdByNode  copy vertex per node id, already placed
     * @param pathByArc       per-arc paths, filled by {@link #build}
     */
    public TraceCarve(EmbeddedMeshTopology topology, MotorcycleGraph motorcycleGraph,
            int[] vertexIdByNode, ArcEdgePath[] pathByArc) {
        this.topology = topology;
        this.motorcycleGraph = motorcycleGraph;
        this.vertexIdByNode = vertexIdByNode;
        this.pathByArc = pathByArc;
        this.sourceMesh = motorcycleGraph.seamless.mesh;
        this.chordWalk = new FaceChordWalk(topology);
        this.chordWalk.arcsById = motorcycleGraph.arcs;
    }

    /**
     * Carve every trace, all interleaved in trace-parametric order — the motorcycle
     * simulation's own clock — so no lane can pre-seal a channel a later stretch of
     * another trace was traced through.
     *
     * @return this, with {@link #pathByArc} populated for every arc
     */
    public TraceCarve build() {
        reserveCrossings();
        PriorityQueue<TraceCursor> cursors = new PriorityQueue<>();
        for (Trace trace : motorcycleGraph.traces) {
            if (trace.chainArcIds.isEmpty()) {
                continue;
            }
            TraceCursor cursor = new TraceCursor(trace, vertexIdByNode[trace.arcNodeIds.get(0)]);
            advanceToNextEvent(cursor);
            if (!cursor.finished) {
                cursors.add(cursor);
            }
            carvedTraceCount++;
        }
        while (!cursors.isEmpty()) {
            TraceCursor cursor = cursors.poll();
            carveNextStretch(cursor);
            advanceToNextEvent(cursor);
            if (!cursor.finished) {
                cursors.add(cursor);
            }
        }
        return this;
    }

    /**
     * Materialize and claim every crossing vertex the carve will use, walking each
     * trace's chain the same way the cursors will. Reserving all waypoints before
     * any chord means no arc can take another's passage. A crossing coinciding with
     * a chain node is not reserved — the node is the waypoint.
     */
    private void reserveCrossings() {
        for (Trace trace : motorcycleGraph.traces) {
            if (trace.chainArcIds.isEmpty()) {
                continue;
            }
            int nodeIndex = 1;
            for (TraceSegment segment : trace.segments) {
                if (nodeIndex >= trace.arcNodeIds.size()) {
                    break;
                }
                double exitLength = segment.parametricLengthAtEntry + segment.parametricLength();
                while (nodeIndex < trace.arcNodeIds.size()
                        && trace.chainNodeLengths.get(nodeIndex) <= exitLength) {
                    nodeIndex++;
                }
                if (nodeIndex >= trace.arcNodeIds.size() || segment.exitLocalEdgeIndex < 0
                        || Double.isNaN(segment.exitEdgeParameter)) {
                    continue;
                }
                if (trace.chainNodeLengths.get(nodeIndex - 1) == exitLength) {
                    continue;
                }
                int arcId = trace.chainArcIds.get(nodeIndex - 1);
                int waypoint = resolveCrossing(segment, arcId);
                topology.ownerArcByCopyVertex[waypoint] = arcId;
                waypointBySegment.put(segment, waypoint);
            }
        }
    }

    /**
     * Position a cursor on its next carve event: the next chain node inside the
     * current segment, else the segment's exit crossing, else the next segment.
     *
     * @param cursor cursor to advance
     * @throws IllegalStateException when the trace runs out of segments with chain
     *                               nodes left to reach
     */
    private void advanceToNextEvent(TraceCursor cursor) {
        Trace trace = cursor.trace;
        while (cursor.segmentIndex < trace.segments.size()) {
            if (cursor.nodeIndex >= trace.arcNodeIds.size()) {
                cursor.finished = true;
                return;
            }
            TraceSegment segment = trace.segments.get(cursor.segmentIndex);
            double exitLength = segment.parametricLengthAtEntry + segment.parametricLength();
            double nodeLength = trace.chainNodeLengths.get(cursor.nodeIndex);
            if (nodeLength <= exitLength) {
                if (nodeLength < segment.parametricLengthAtEntry) {
                    strandedNodeEventCount++;
                    if (strandedNodeEventCount <= STRANDED_SAMPLE_LIMIT) {
                        System.out.printf("[carve-diag] trace %d node index %d at %.6f precedes"
                                + " segment %d entry %.6f (face %d); the chord would be walked in"
                                + " the wrong face%n", trace.traceId, cursor.nodeIndex, nodeLength,
                                cursor.segmentIndex, segment.parametricLengthAtEntry,
                                segment.activeFace);
                    }
                }
                cursor.nextEventIsNode = true;
                cursor.nextEventParameter = nodeLength;
                return;
            }
            if (waypointBySegment.containsKey(segment)) {
                cursor.nextEventIsNode = false;
                cursor.nextEventParameter = exitLength;
                return;
            }
            cursor.segmentIndex++;
        }
        if (cursor.nodeIndex < trace.arcNodeIds.size()) {
            throw new IllegalStateException("trace " + trace.traceId + " ran out of segments with "
                    + (trace.arcNodeIds.size() - cursor.nodeIndex) + " chain nodes left to reach");
        }
        cursor.finished = true;
    }

    /**
     * Carve a cursor's pending stretch: walk the straight chord to the chain node
     * or reserved crossing, claim the lane, and emit the arc when a chain node
     * completes it.
     *
     * @param cursor cursor whose pending event is carved
     */
    private void carveNextStretch(TraceCursor cursor) {
        TraceSegment segment = cursor.trace.segments.get(cursor.segmentIndex);
        int arcId = cursor.trace.chainArcIds.get(cursor.nodeIndex - 1);
        int claimFrom = cursor.chain.size();
        if (cursor.nextEventIsNode) {
            int targetVertex1 = vertexIdByNode[cursor.trace.arcNodeIds.get(cursor.nodeIndex)];
            cursor.head = walkStretch(arcId, segment.activeFace, cursor.head, targetVertex1,
                    cursor.chain);
            claimStretch(arcId, cursor.chain, claimFrom);
            cursor.chainPositionByNode[cursor.nodeIndex] = cursor.chain.size() - 1;
            emitArc(cursor.trace, cursor.nodeIndex - 1, cursor.chain, cursor.chainPositionByNode);
            cursor.nodeIndex++;
        } else {
            int targetVertex = waypointBySegment.get(segment);
            cursor.head = walkStretch(arcId, segment.activeFace, cursor.head, targetVertex,
                    cursor.chain);
            claimStretch(arcId, cursor.chain, claimFrom);
            cursor.segmentIndex++;
        }
    }

    /**
     * Walk one stretch as the exact straight chord between two carve points,
     * materializing every retriangulation edge it crosses.
     *
     * @param arcId        arc the stretch belongs to
     * @param sourceFace   source active face the segment lies in
     * @param startVertex  carve point the stretch leaves
     * @param targetVertex carve point the stretch must reach
     * @param chain        the trace's vertex chain, extended in place
     * @return the target vertex, the new head
     */
    private int walkStretch(int arcId, int sourceFace, int startVertex, int targetVertex,
            List<Integer> chain) {
        if (startVertex == targetVertex) {
            return targetVertex;
        }
        double[] targetBarycentric = topology.barycentricOf(sourceFace, targetVertex);
        if (targetBarycentric == null) {
            throw new IllegalStateException("arc " + arcId + " stretch target copy vertex "
                    + targetVertex + " has no barycentric in source face " + sourceFace);
        }
        int reached = chordWalk.walk(arcId, sourceFace, startVertex, targetBarycentric,
                targetVertex, chain);
        if (reached != targetVertex) {
            throw new IllegalStateException("arc " + arcId + " chord walk ended on copy vertex "
                    + reached + " instead of " + targetVertex + " in source face " + sourceFace);
        }
        return targetVertex;
    }

    /**
     * The copy vertex realizing a segment's exit crossing: an existing vertex of
     * the crossed edge's fragment chain exactly at the crossing's parameter, or a
     * fresh vertex split from the containing fragment. Coincidences with an
     * unusable vertex perturb the parameter into the fragment, never share.
     *
     * @param segment segment whose recorded exit crossing is being carved
     * @param arcId   arc the crossing belongs to, whose own claims stay usable
     * @throws IllegalStateException when the crossed edge's fragment chain is
     *                               broken
     * @return the crossing's copy vertex
     */
    private int resolveCrossing(TraceSegment segment, int arcId) {
        int fromVertexId = exitFromVertexId(segment);
        int toVertexId = exitToVertexId(segment);
        int lowerSourceVertex = Math.min(fromVertexId, toVertexId);
        int upperSourceVertex = Math.max(fromVertexId, toVertexId);
        int lowerCopy = topology.copyVertexForSourceVertexId(lowerSourceVertex);
        int upperCopy = topology.copyVertexForSourceVertexId(upperSourceVertex);
        double parameter = canonicalParameter(segment);
        if (parameter == 0.0 && vertexUsable(lowerCopy, arcId)) {
            snappedCrossingCount++;
            return lowerCopy;
        }
        if (parameter == 1.0 && vertexUsable(upperCopy, arcId)) {
            snappedCrossingCount++;
            return upperCopy;
        }
        int sourceEdgeActive = sourceEdgeActiveIndex(segment, fromVertexId, toVertexId);
        int stepCap = topology.copy.vertexCount() + 2;
        int previous = EmbeddedMeshTopology.UNCLAIMED;
        int current = lowerCopy;
        double currentParameter = 0.0;
        for (int step = 0; step <= stepCap; step++) {
            int nextVertex = EmbeddedMeshTopology.UNCLAIMED;
            int fragmentEdge = EmbeddedMeshTopology.UNCLAIMED;
            for (int index = 0; index < topology.copy.vertexEdgeCount(current); index++) {
                int edgeId = topology.copy.vertexEdgeAt(current, index);
                if (topology.sourceEdgeByCopyEdge[edgeId] != sourceEdgeActive) {
                    continue;
                }
                int other = topology.otherEndpoint(edgeId, current);
                if (other == previous) {
                    continue;
                }
                nextVertex = other;
                fragmentEdge = edgeId;
                break;
            }
            if (nextVertex == EmbeddedMeshTopology.UNCLAIMED) {
                throw new IllegalStateException("source edge " + sourceEdgeActive
                        + " has a broken fragment chain at copy vertex " + current);
            }
            double nextParameter = nextVertex == upperCopy ? 1.0
                    : fragmentParameter(segment, nextVertex);
            if (parameter == nextParameter && nextVertex != upperCopy) {
                if (vertexUsable(nextVertex, arcId)) {
                    snappedCrossingCount++;
                    return nextVertex;
                }
                parameter = (currentParameter + nextParameter) / 2.0;
            } else if (parameter >= nextParameter && nextVertex == upperCopy) {
                parameter = (currentParameter + nextParameter) / 2.0;
            }
            if (parameter < nextParameter) {
                double local = (parameter - currentParameter) / (nextParameter - currentParameter);
                if (!(local > 0.0 && local < 1.0)) {
                    local = EDGE_MIDPOINT;
                }
                splitCrossingCount++;
                int canonicalStart = topology.copy.halfEdgeVertex(
                        topology.copy.edgeHalfEdge(fragmentEdge));
                return topology.splitEdgeAtParameter(fragmentEdge,
                        canonicalStart == current ? local : 1.0 - local);
            }
            previous = current;
            current = nextVertex;
            currentParameter = nextParameter;
        }
        throw new IllegalStateException("source edge " + sourceEdgeActive
                + " fragment walk did not terminate resolving parameter " + parameter);
    }

    /**
     * Canonical 1D parameter of a fragment vertex along the segment's crossed
     * source edge, read off the vertex's stored barycentric in the recording face.
     *
     * @param segment segment whose exit edge the vertex lies on
     * @param vertex  fragment copy vertex
     * @throws IllegalStateException when the vertex has no barycentric in the
     *                               recording face
     * @return the vertex's parameter measured from the edge's lower-id endpoint
     */
    private double fragmentParameter(TraceSegment segment, int vertex) {
        double[] barycentric = topology.barycentricOf(segment.activeFace, vertex);
        if (barycentric == null) {
            throw new IllegalStateException("fragment copy vertex " + vertex
                    + " has no barycentric in source face " + segment.activeFace);
        }
        double faceParameter = barycentric[(segment.exitLocalEdgeIndex + 1) % CORNERS];
        return exitFromVertexId(segment) < exitToVertexId(segment)
                ? faceParameter
                : 1.0 - faceParameter;
    }

    /**
     * The source active edge index of a segment's crossed edge, resolved from the
     * cross field's edge map so fragment edges can be recognized by their inherited
     * tag.
     *
     * @param segment      segment whose exit edge is being resolved
     * @param fromVertexId source vertex the exit parameter is measured from
     * @param toVertexId   source vertex the exit parameter runs toward
     * @throws IllegalStateException when the two vertices share no active edge
     * @return the crossed edge's source active index
     */
    private int sourceEdgeActiveIndex(TraceSegment segment, int fromVertexId, int toVertexId) {
        for (int index = 0; index < sourceMesh.vertexEdgeCount(fromVertexId); index++) {
            int edgeId = sourceMesh.vertexEdgeAt(fromVertexId, index);
            int halfEdge = sourceMesh.edgeHalfEdge(edgeId);
            int start = sourceMesh.halfEdgeVertex(halfEdge);
            int other = start == fromVertexId ? sourceMesh.halfEdgeEndVertex(halfEdge) : start;
            if (other != toVertexId) {
                continue;
            }
            Integer active = motorcycleGraph.seamless.crossField.edgeIdToActive.get(edgeId);
            if (active == null) {
                break;
            }
            return active;
        }
        throw new IllegalStateException("segment in source face " + segment.activeFace
                + " crosses source vertices " + fromVertexId + ".." + toVertexId
                + " that share no active edge");
    }

    /**
     * Whether a crossing of an arc may land on a copy vertex: no node owns it, and
     * no arc other than the crossing's own has claimed it.
     *
     * @param copyVertex copy vertex to test
     * @param arcId      arc whose crossing wants the vertex
     * @return true when the vertex is usable for the arc
     */
    private boolean vertexUsable(int copyVertex, int arcId) {
        return topology.ownerNodeByCopyVertex[copyVertex] == EmbeddedMeshTopology.UNCLAIMED
                && (topology.ownerArcByCopyVertex[copyVertex] == EmbeddedMeshTopology.UNCLAIMED
                        || topology.ownerArcByCopyVertex[copyVertex] == arcId);
    }

    /**
     * Claim the stretch of chain an arc just carved, so later arcs see the lane as
     * taken. Node vertices keep their node ownership.
     *
     * @param arcId     arc that carved the stretch
     * @param chain     the trace's vertex chain
     * @param claimFrom index of the first newly appended vertex
     */
    private void claimStretch(int arcId, List<Integer> chain, int claimFrom) {
        for (int index = Math.max(1, claimFrom); index < chain.size(); index++) {
            topology.claimEdgeBetween(chain.get(index - 1), chain.get(index), arcId);
            int vertexId = chain.get(index);
            if (topology.ownerNodeByCopyVertex[vertexId] == EmbeddedMeshTopology.UNCLAIMED) {
                topology.ownerArcByCopyVertex[vertexId] = arcId;
            }
        }
    }

    /**
     * Cut one completed arc out of the trace's chain and record it.
     *
     * @param trace               the carved trace
     * @param chainIndex          index of the arc within the trace's chain
     * @param chain               the trace's vertex chain
     * @param chainPositionByNode chain position of each chain node
     */
    private void emitArc(Trace trace, int chainIndex, List<Integer> chain,
            int[] chainPositionByNode) {
        int arcId = trace.chainArcIds.get(chainIndex);
        int from = chainPositionByNode[chainIndex];
        int to = chainPositionByNode[chainIndex + 1];
        List<Integer> vertices = new ArrayList<>(chain.subList(from, to + 1));
        List<Integer> edges = new ArrayList<>(Math.max(0, vertices.size() - 1));
        for (int index = 1; index < vertices.size(); index++) {
            int edgeId = topology.edgeBetween(vertices.get(index - 1), vertices.get(index));
            if (edgeId == EmbeddedMeshTopology.UNCLAIMED) {
                throw new IllegalStateException("arc " + arcId + " has no copy edge between "
                        + vertices.get(index - 1) + " and " + vertices.get(index));
            }
            edges.add(edgeId);
        }
        pathByArc[arcId] = new ArcEdgePath(arcId, vertices, edges);
        carvedArcCount++;
    }

    /**
     * Source vertex the exit edge's parameter is measured from, in the recording
     * face's own orientation.
     *
     * @param segment segment whose exit edge is being read
     * @return the source vertex id at parameter zero
     */
    private int exitFromVertexId(TraceSegment segment) {
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
    private int exitToVertexId(TraceSegment segment) {
        return sourceMesh.faceVertexAt(sourceMesh.faceIdAt(segment.activeFace),
                (segment.exitLocalEdgeIndex + 1) % CORNERS);
    }

    /**
     * Parameter of a crossing along its source edge, measured from the edge's
     * lower-id endpoint so that crossings recorded from either incident face are
     * directly comparable.
     *
     * @param segment segment whose exit crossing is being measured
     * @return the crossing's parameter in the edge's canonical direction
     */
    private double canonicalParameter(TraceSegment segment) {
        return exitFromVertexId(segment) < exitToVertexId(segment)
                ? segment.exitEdgeParameter
                : 1.0 - segment.exitEdgeParameter;
    }
}
