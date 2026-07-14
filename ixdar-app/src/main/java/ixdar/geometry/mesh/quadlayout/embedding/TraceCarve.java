package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.List;

import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceSegment;

/**
 * Carves every traced motorcycle path into the working copy as an edge path
 * (LCBK19 §6.1). This is the paper's initial embedding, which costs nothing to
 * find: <em>"by construction, an initial embedding of regular arcs onto the input
 * surface is given by the traced motorcycle paths"</em>. There is no search and no
 * routing — the tracer already walked the surface face by face, and this stage
 * simply replays that walk, materializing each step.
 *
 * <p>A trace is carved as one continuous chain from its origin node to its
 * terminal node, through the T-mesh nodes it meets on the way. Its carve points
 * are the merge of two exactly-known sequences: the nodes on the trace's chain,
 * and the crossings of mesh edges recorded by the walker as
 * {@link TraceSegment#exitLocalEdgeIndex} and {@link TraceSegment#exitEdgeParameter}.
 * The merge is not optional — a chord-chord intersection node lies strictly
 * <em>inside</em> a segment of at least one of the two traces that meet there, so
 * nodes are not always segment endpoints.
 *
 * <p>Consecutive carve points are connected by {@link FaceChordWalk}, which cannot
 * fail. Every arc is carved, zero-quantized ones included: they are the paper's
 * input to the collapse operators, and leaving them out is what lets other arcs
 * colonize their channels and wall each other in.
 */
public final class TraceCarve {

    /** Corners (and edges) of a triangle. */
    private static final int CORNERS = 3;

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
        int head = nodeVertex(trace.arcNodeIds.get(0));
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
                int targetVertex = nodeVertex(trace.arcNodeIds.get(nodeIndex));
                int claimFrom = chain.size();
                head = chordWalk.walk(arcId, segment.activeFace, head,
                        nodeBarycentric(segment.activeFace, targetVertex), targetVertex, chain);
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
            head = chordWalk.walk(arcId, segment.activeFace, head, crossingBarycentric(segment),
                    EmbeddedMeshTopology.UNCLAIMED, chain);
            claimStretch(arcId, chain, claimFrom);
        }
        if (nodeIndex < trace.arcNodeIds.size()) {
            throw new IllegalStateException("trace " + trace.traceId + " ran out of segments with "
                    + (trace.arcNodeIds.size() - nodeIndex) + " chain nodes left to reach");
        }
    }

    /**
     * Barycentric coordinate, in the segment's face, of the mesh-edge crossing the
     * walker recorded when the chord left that face. Local edge {@code e} runs from
     * corner {@code e} to corner {@code e + 1}, so the crossing's coordinate is read
     * straight off the recorded parameter — exactly, with no projection.
     *
     * @param segment chord leaving its face through a mesh edge
     * @return the crossing's barycentric coordinate
     */
    private double[] crossingBarycentric(TraceSegment segment) {
        double[] barycentric = new double[CORNERS];
        int localEdge = segment.exitLocalEdgeIndex;
        double parameter = segment.exitEdgeParameter;
        barycentric[localEdge] = 1.0 - parameter;
        barycentric[(localEdge + 1) % CORNERS] = parameter;
        return barycentric;
    }

    /**
     * Barycentric coordinate of an already-placed node's vertex in a source face.
     *
     * @param sourceFace source active face the chord lies in
     * @param nodeVertex the node's copy vertex
     * @return its barycentric coordinate in that face
     */
    private double[] nodeBarycentric(int sourceFace, int nodeVertex) {
        double[] barycentric = topology.barycentricOf(sourceFace, nodeVertex);
        if (barycentric == null) {
            throw new IllegalStateException("node copy vertex " + nodeVertex
                    + " does not lie in source face " + sourceFace);
        }
        return barycentric;
    }

    /**
     * The copy vertex a node was placed on.
     *
     * @param nodeId T-mesh node id
     * @return its copy vertex
     */
    private int nodeVertex(int nodeId) {
        int vertexId = vertexIdByNode[nodeId];
        if (vertexId == EmbeddedMeshTopology.UNCLAIMED) {
            throw new IllegalStateException("T-mesh node " + nodeId + " was never placed");
        }
        return vertexId;
    }

    /**
     * Claim the stretch of chain an arc just carved, so the walk cannot snap back
     * onto its own lane and later arcs see it as taken. Node vertices keep their node
     * ownership.
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
}
