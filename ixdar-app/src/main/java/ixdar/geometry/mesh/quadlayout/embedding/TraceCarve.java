package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceSegment;

/**
 * Carves every traced motorcycle path into the working copy as an edge path,
 * replaying the tracer's walk rather than searching for a route.
 *
 * <p>
 * Carve points merge the trace's chain of nodes with its recorded edge
 * crossings, since a node need not be a segment endpoint.
 *
 * <p>
 * See also: LCBK19 Section 6.1
 */
public final class TraceCarve {

    /** Corners (and edges) of a triangle. */
    private static final int CORNERS = 3;

    /** A crossing may only snap onto the endpoint of its own half of the edge. */
    private static final double NEARER_HALF = 0.5;

    public final HalfEdgeMesh sourceMesh;

    /**
     * Source vertex each snappable crossing may move onto, keyed by the segment
     * that records it.
     */
    public final Map<TraceSegment, Integer> snapSourceVertexBySegment;

    /** Recorded crossings the plan considered. */
    public int crossingCount;

    public final EmbeddedMeshTopology topology;
    public final MotorcycleGraph motorcycleGraph;
    public final FaceChordWalk chordWalk;

    /** Copy vertex per T-mesh node id, filled by the node placement stage. */
    public final int[] vertexIdByNode;

    /** Embedded path per arc id, filled in place. */
    public final ArcEdgePath[] pathByArc;

    public int carvedArcCount;
    public int carvedTraceCount;

    /**
     * Stores the inputs for the carve.
     *
     * @param chordWalk       the exact in-face walk, shared with node placement
     * @param motorcycleGraph traced T-mesh to carve
     * @param vertexIdByNode  copy vertex per node id, already placed
     * @param pathByArc       per-arc paths, filled by {@link #build}
     */
    public TraceCarve(FaceChordWalk chordWalk, MotorcycleGraph motorcycleGraph,
            int[] vertexIdByNode, ArcEdgePath[] pathByArc) {
        this.topology = chordWalk.topology;
        this.motorcycleGraph = motorcycleGraph;
        this.vertexIdByNode = vertexIdByNode;
        this.pathByArc = pathByArc;
        this.chordWalk = chordWalk;
        this.sourceMesh = motorcycleGraph.seamless.mesh;
        this.snapSourceVertexBySegment = new IdentityHashMap<>();

        Map<Long, TraceSegment> extremalTowardStart = new HashMap<>();
        Map<Long, TraceSegment> extremalTowardEnd = new HashMap<>();
        for (Trace trace : motorcycleGraph.traces) {
            for (TraceSegment segment : trace.segments) {
                if (segment.exitLocalEdgeIndex < 0 || Double.isNaN(segment.exitEdgeParameter)) {
                    continue;
                }
                crossingCount++;
                long edgeKey = edgeKey(segment);
                double parameter = canonicalParameter(segment);
                TraceSegment towardStart = extremalTowardStart.get(edgeKey);
                if (towardStart == null || parameter < canonicalParameter(towardStart)) {
                    extremalTowardStart.put(edgeKey, segment);
                }
                TraceSegment towardEnd = extremalTowardEnd.get(edgeKey);
                if (towardEnd == null || parameter > canonicalParameter(towardEnd)) {
                    extremalTowardEnd.put(edgeKey, segment);
                }
            }
        }

        for (Map.Entry<Long, TraceSegment> entry : extremalTowardStart.entrySet()) {
            TraceSegment segment = entry.getValue();
            if (canonicalParameter(segment) < NEARER_HALF) {
                snapSourceVertexBySegment.put(segment, (int) (entry.getKey() >> Integer.SIZE));
            }
        }
        for (Map.Entry<Long, TraceSegment> entry : extremalTowardEnd.entrySet()) {
            TraceSegment segment = entry.getValue();
            if (canonicalParameter(segment) > NEARER_HALF) {
                snapSourceVertexBySegment.put(segment, (int) (entry.getKey() & 0xFFFFFFFFL));
            }
        }
    }

    /**
     * Carve every trace.
     *
     * @return this, with {@link #pathByArc} populated for every arc
     */
    public TraceCarve build() {
        for (Trace trace : motorcycleGraph.traces) {
            if (trace.chainArcIds.isEmpty()) {
                continue;
            }
            carveTrace(trace);
            carvedTraceCount++;
        }
        return this;
    }

    /**
     * Carve one trace end to end, emitting each of its arcs as it is completed so
     * that later traces — and this trace's own later arcs — see the lane as taken.
     *
     * @param trace trace to carve
     */
    private void carveTrace(Trace trace) {
        List<Integer> chain = new ArrayList<>();
        int[] chainPositionByNode = new int[trace.arcNodeIds.size()];
        int head = vertexIdByNode[trace.arcNodeIds.get(0)];
        chain.add(head);
        int nodeIndex = 1;
        for (TraceSegment segment : trace.segments) {
            if (nodeIndex >= trace.arcNodeIds.size()) {
                break;
            }
            double exitLength = segment.parametricLengthAtEntry + segment.parametricLength();
            while (nodeIndex < trace.arcNodeIds.size()
                    && trace.chainNodeLengths.get(nodeIndex) <= exitLength) {
                int arcId = trace.chainArcIds.get(nodeIndex - 1);
                int targetVertex = vertexIdByNode[trace.arcNodeIds.get(nodeIndex)];
                int claimFrom = chain.size();
                double[] barycentric = topology.barycentricOf(segment.activeFace, targetVertex);
                head = chordWalk.walk(arcId, segment.activeFace, head, barycentric, targetVertex, chain);
                claimStretch(arcId, chain, claimFrom);
                chainPositionByNode[nodeIndex] = chain.size() - 1;
                emitArc(trace, nodeIndex - 1, chain, chainPositionByNode);
                nodeIndex++;
            }
            if (nodeIndex >= trace.arcNodeIds.size() || segment.exitLocalEdgeIndex < 0) {
                continue;
            }
            int arcId = trace.chainArcIds.get(nodeIndex - 1);
            int claimFrom = chain.size();
            int snapVertex = EmbeddedMeshTopology.UNCLAIMED;
            Integer sourceVertexId = snapSourceVertexBySegment.get(segment);
            if (sourceVertexId != null) {
                int copyVertex = topology.copyVertexForSourceVertexId(sourceVertexId);
                if (copyVertex == EmbeddedMeshTopology.UNCLAIMED
                        || topology.ownerNodeByCopyVertex[copyVertex] != EmbeddedMeshTopology.UNCLAIMED
                        || topology.ownerArcByCopyVertex[copyVertex] != EmbeddedMeshTopology.UNCLAIMED) {
                    snapVertex = EmbeddedMeshTopology.UNCLAIMED;
                } else {
                    snapVertex = copyVertex;
                }
            }
            if (snapVertex == EmbeddedMeshTopology.UNCLAIMED) {
                double[] barycentric = new double[CORNERS];
                int localEdge = segment.exitLocalEdgeIndex;
                double parameter = segment.exitEdgeParameter;
                barycentric[localEdge] = 1.0 - parameter;
                barycentric[(localEdge + 1) % CORNERS] = parameter;
                head = chordWalk.walk(arcId, segment.activeFace, head, barycentric,
                        EmbeddedMeshTopology.UNCLAIMED, chain);
            } else {
                chordWalk.snappedCrossingCount++;
                double[] barycentric = topology.barycentricOf(segment.activeFace, snapVertex);
                head = chordWalk.walk(arcId, segment.activeFace, head, barycentric, snapVertex, chain);
            }
            claimStretch(arcId, chain, claimFrom);
        }
        if (nodeIndex < trace.arcNodeIds.size()) {
            throw new IllegalStateException("trace " + trace.traceId + " ran out of segments with "
                    + (trace.arcNodeIds.size() - nodeIndex) + " chain nodes left to reach");
        }
    }

    /**
     * Claim the stretch of chain an arc just carved, so the walk cannot snap back
     * onto its own lane and later arcs see it as taken. Node vertices keep their
     * node ownership.
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
     * Identity of the source edge a crossing lies on, orientation-independent so
     * that the two faces sharing the edge agree on it.
     *
     * @param segment segment whose exit crossing is being keyed
     * @return the edge's two source vertex ids packed lower-first into a long
     */
    private long edgeKey(TraceSegment segment) {
        int fromVertexId = exitFromVertexId(segment);
        int toVertexId = exitToVertexId(segment);
        int lower = Math.min(fromVertexId, toVertexId);
        int upper = Math.max(fromVertexId, toVertexId);
        return ((long) lower << Integer.SIZE) | (upper & 0xFFFFFFFFL);
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
}
