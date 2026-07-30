package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TMeshNode;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceArc;
import ixdar.geometry.mesh.quadlayout.quantization.LayoutExtraction;
import ixdar.geometry.mesh.quadlayout.quantization.QuantizedMeshGrid;

/**
 * T-mesh re-embedding, construction half: builds a working copy of the input mesh, gives
 * every T-mesh node a copy vertex, and carves every traced arc into the copy as an edge
 * path. Zero-quantized arcs are carved too; {@link ZeroElementContraction} consumes them.
 *
 * <p>See also: LCBK19 Section 6.1
 */
public final class LayoutEmbedding {

    /** Nanoseconds per second, for the timing log. */
    private static final double NANOS_PER_SECOND = 1.0e9;

    /**
     * How far below zero a chart inversion's barycentric may round before its point counts
     * as genuinely outside the face. A node on a face edge inverts to a coordinate a couple
     * of ULP negative, because that coordinate is derived by subtraction.
     */
    private static final double CHART_INVERSION_SLACK = 8.0 * Math.ulp(1.0);

    public final LayoutExtraction layout;
    public final MotorcycleGraph motorcycleGraph;
    public final QuantizedMeshGrid quantization;

    /** Working copy with provenance and claims. */
    public EmbeddedMeshTopology topology;

    /** Combinatorial node placement onto the working copy. */
    public NodePlacement nodePlacement;

    /** The carve, for its counters. */
    public TraceCarve carve;

    /** The post-carve snap pass, for its counters. */
    public ArrangementDecimation decimation;

    /** Copy vertex per T-mesh node id, or -1 for nodes no arc references. */
    public int[] vertexIdByNode;

    /** Embedded path per arc id; never {@code null} after {@link #build}. */
    public ArcEdgePath[] pathByArc;

    /**
     * LCBK19 Def 6.2 criticality per node id: singularity and feature nodes hold
     * prescribed integer positions and must never be moved by the contraction
     * operators. A non-critical node collapsed onto a critical point becomes critical
     * (the contraction stage updates this in place).
     */
    public boolean[] criticalByNode;

    /**
     * Whether each arc rides a feature trace — the closed-surface analog of LCBK19's
     * border arcs: nodes on a feature curve may only move along it, so a zero feature
     * arc is collapsible while a zero regular arc into a feature-bound node is not.
     */
    public boolean[] featureByArc;

    /** T-mesh nodes that claimed an existing mesh vertex outright. */
    public int nodesOnMeshVertexCount;

    /**
     * Stores inputs for the re-embedding construction.
     *
     * @param layout quantized layout whose T-mesh is embedded
     */
    public LayoutEmbedding(LayoutExtraction layout) {
        this.layout = layout;
        this.motorcycleGraph = layout.motorcycleGraph;
        this.quantization = layout.quantization;
    }

    /**
     * Copy the mesh, place nodes, carve every trace, and check the result really is a
     * subcomplex; logs the {@code [embed]} summary.
     *
     * @return this, with all public products populated
     */
    public LayoutEmbedding build() {
        long startNanos = System.nanoTime();
        topology = new EmbeddedMeshTopology(motorcycleGraph.seamless.mesh);
        long copyDoneNanos = System.nanoTime();
        nodePlacement = new NodePlacement(topology);
        tagSourceEdges();
        markCriticality();
        insertNodes();
        pathByArc = new ArcEdgePath[motorcycleGraph.arcs.size()];
        long nodesDoneNanos = System.nanoTime();
        carve = new TraceCarve(topology, motorcycleGraph, vertexIdByNode, pathByArc).build();
        if (carve.strandedNodeEventCount > 0) {
            System.out.printf("[carve-diag] strandedNodeEvents=%d%n", carve.strandedNodeEventCount);
        }
        decimation = new ArrangementDecimation(topology, pathByArc,
                motorcycleGraph.seamless.mesh.vertexCount()).build();
        long carveDoneNanos = System.nanoTime();
        topology.copy.computeNormals();
        long normalsDoneNanos = System.nanoTime();
        assertSubcomplex();
        long checkDoneNanos = System.nanoTime();
        int carvePoints = carve.snappedCrossingCount + carve.splitCrossingCount;
        System.out.printf(
                "[embed] nodes onVertex=%d faceSplit=%d |"
                        + " arcs carved=%d/%d traces=%d |"
                        + " carve points=%d (snapped=%d split=%d) chordSplits=%d flips=%d"
                        + " snapBack=%d kept=%d |"
                        + " copy V=%d E=%d F=%d (+%d faceSplits +%d edgeSplits) %.2fs"
                        + " (copy %.2f nodes %.2f carve %.2f normals %.2f check %.2f)%n",
                nodesOnMeshVertexCount, nodePlacement.chordWalk.placedByFaceSplitCount,
                carve.carvedArcCount, motorcycleGraph.arcs.size(), carve.carvedTraceCount,
                carvePoints, carve.snappedCrossingCount, carve.splitCrossingCount,
                carve.chordWalk.interiorSplitCount, carve.chordWalk.flipInsertCount,
                decimation.snappedVertexCount, decimation.keptVertexCount,
                topology.copy.vertexCount(), topology.copy.edgeCount(), topology.copy.faceCount(),
                topology.faceSplitCount, topology.edgeSplitCount,
                (checkDoneNanos - startNanos) / NANOS_PER_SECOND,
                (copyDoneNanos - startNanos) / NANOS_PER_SECOND,
                (nodesDoneNanos - copyDoneNanos) / NANOS_PER_SECOND,
                (carveDoneNanos - nodesDoneNanos) / NANOS_PER_SECOND,
                (normalsDoneNanos - carveDoneNanos) / NANOS_PER_SECOND,
                (checkDoneNanos - normalsDoneNanos) / NANOS_PER_SECOND);
        System.out.printf(
                "[decimate] passes=%d capHit=%b snapBack=%d kept=%d"
                        + " (nodeOwned=%d laneEdgeClaimed=%d structureMissing=%d"
                        + " targetClaimed=%d noOriginalNeighbor=%d)%n",
                decimation.passCount, decimation.passCapHit,
                decimation.snappedVertexCount, decimation.keptVertexCount,
                decimation.keptNodeOwnedCount, decimation.keptLaneEdgeClaimedCount,
                decimation.keptStructureMissingCount, decimation.keptTargetClaimedCount,
                decimation.keptNoOriginalNeighborCount);
        return this;
    }

    /**
     * Tag every original copy edge with its source active edge index so later stages
     * can relate copy edges back to the source mesh across refinement splits.
     */
    private void tagSourceEdges() {
        for (Map.Entry<Integer, Integer> entry
                : motorcycleGraph.seamless.crossField.edgeIdToActive.entrySet()) {
            int halfEdge = motorcycleGraph.seamless.mesh.edgeHalfEdge(entry.getKey());
            int copyA = topology.copyVertexForSourceVertexId(
                    motorcycleGraph.seamless.mesh.halfEdgeVertex(halfEdge));
            int copyB = topology.copyVertexForSourceVertexId(
                    motorcycleGraph.seamless.mesh.halfEdgeEndVertex(halfEdge));
            if (copyA == EmbeddedMeshTopology.UNCLAIMED || copyB == EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            int copyEdge = topology.edgeBetween(copyA, copyB);
            if (copyEdge != EmbeddedMeshTopology.UNCLAIMED) {
                topology.sourceEdgeByCopyEdge[copyEdge] = entry.getValue();
            }
        }
    }

    /**
     * Mark LCBK19 Def 6.2 criticality: singularity and feature nodes are critical
     * (their integer positions are prescribed by the quantization), and feature-trace
     * arcs are critical curves. Trace-crossing intersection nodes stay non-critical
     * until a contraction collapse lands them on a critical point.
     */
    private void markCriticality() {
        criticalByNode = new boolean[motorcycleGraph.nodes.size()];
        for (TMeshNode node : motorcycleGraph.nodes) {
            criticalByNode[node.nodeId] = node.type == TMeshNode.Type.SINGULARITY
                    || node.type == TMeshNode.Type.FEATURE;
        }
        featureByArc = new boolean[motorcycleGraph.arcs.size()];
        Map<Integer, Trace> traceById = new HashMap<>();
        for (Trace trace : motorcycleGraph.traces) {
            traceById.put(trace.traceId, trace);
        }
        for (TraceArc arc : motorcycleGraph.arcs) {
            featureByArc[arc.arcId] = traceById.get(arc.traceId).featureTrace;
        }
    }

    /**
     * Give every arc-referenced T-mesh node a dedicated copy vertex: vertex-bound
     * nodes claim their mapped copy vertex, and face-interior nodes mint a fresh
     * vertex via {@link NodePlacement#placeVertex}. One mesh vertex never owns
     * two T-mesh nodes, so a claim conflict throws rather than dropping a node.
     */
    private void insertNodes() {
        int nodeCount = motorcycleGraph.nodes.size();
        vertexIdByNode = new int[nodeCount];
        Arrays.fill(vertexIdByNode, EmbeddedMeshTopology.UNCLAIMED);
        boolean[] nodeUsed = new boolean[nodeCount];
        for (TraceArc arc : motorcycleGraph.arcs) {
            nodeUsed[arc.startNodeId] = true;
            nodeUsed[arc.endNodeId] = true;
        }
        for (TMeshNode node : motorcycleGraph.nodes) {
            if (!nodeUsed[node.nodeId]) {
                continue;
            }
            int copyVertex;
            if (node.vertexId >= 0) {
                copyVertex = topology.copyVertexForSourceVertexId(node.vertexId);
                if (copyVertex == EmbeddedMeshTopology.UNCLAIMED) {
                    throw new IllegalStateException("T-mesh node " + node.nodeId
                            + " references source vertex " + node.vertexId
                            + " which has no copy vertex");
                }
                int owner = topology.ownerNodeByCopyVertex[copyVertex];
                if (owner != EmbeddedMeshTopology.UNCLAIMED) {
                    throw new IllegalStateException("T-mesh nodes " + owner + " and "
                            + node.nodeId + " both sit on mesh vertex " + node.vertexId
                            + "; one mesh vertex never owns two T-mesh nodes");
                }
                nodesOnMeshVertexCount++;
            } else {
                copyVertex = nodePlacement.placeVertex(node.activeFace,
                        chartToBarycentric(node.activeFace, node.u, node.v));
            }
            topology.ownerNodeByCopyVertex[copyVertex] = node.nodeId;
            vertexIdByNode[node.nodeId] = copyVertex;
        }
    }

    /**
     * Barycentric coordinate of a chart point within its source face, with a coordinate
     * that rounded below zero snapped onto the face edge it belongs to.
     *
     * <p>The parametrization is affine per triangle, so the inversion is a 2x2 solve. Only
     * rounding is absorbed; a point truly outside its face throws.
     *
     * @param activeFace source active face the point lies in
     * @param u          chart u of the point
     * @param v          chart v of the point
     * @throws IllegalStateException when the chart is degenerate, or the point lies outside
     *                               the face by more than the inversion could have rounded
     * @return the point's barycentric coordinate in that face
     */
    private double[] chartToBarycentric(int activeFace, double u, double v) {
        double[] cornerUv = new double[2 * 3];
        motorcycleGraph.seamless.faceCornerUv(activeFace, cornerUv);
        double firstU = cornerUv[2] - cornerUv[0];
        double firstV = cornerUv[3] - cornerUv[1];
        double secondU = cornerUv[4] - cornerUv[0];
        double secondV = cornerUv[5] - cornerUv[1];
        double determinant = firstU * secondV - firstV * secondU;
        if (determinant == 0.0) {
            throw new IllegalStateException(
                    "source active face " + activeFace + " has a degenerate chart");
        }
        double offsetU = u - cornerUv[0];
        double offsetV = v - cornerUv[1];
        double second = (offsetU * secondV - offsetV * secondU) / determinant;
        double third = (firstU * offsetV - firstV * offsetU) / determinant;
        double[] barycentric = { 1.0 - second - third, second, third };
        for (int corner = 0; corner < barycentric.length; corner++) {
            if (barycentric[corner] >= 0.0) {
                continue;
            }
            if (barycentric[corner] < -CHART_INVERSION_SLACK) {
                throw new IllegalStateException("T-mesh node at chart (" + u + ", " + v
                        + ") inverts to barycentric " + Arrays.toString(barycentric)
                        + ", outside source active face " + activeFace + " by "
                        + -barycentric[corner] + "; its chart position and its face disagree");
            }
            barycentric[corner] = 0.0;
        }
        return barycentric;
    }

    /**
     * Check that the carve really produced a subcomplex of the working copy: every arc
     * embedded, every hop a real edge, and no two T-mesh elements sharing a mesh
     * element. A violation here is an upstream invariant break, so it throws rather
     * than being papered over.
     */
    private void assertSubcomplex() {
        for (TraceArc arc : motorcycleGraph.arcs) {
            ArcEdgePath path = pathByArc[arc.arcId];
            if (path == null) {
                throw new IllegalStateException("arc " + arc.arcId + " was never carved");
            }
            if (path.copyVertexPath.get(0) != vertexIdByNode[arc.startNodeId]
                    || path.copyVertexPath.get(path.copyVertexPath.size() - 1)
                            != vertexIdByNode[arc.endNodeId]) {
                throw new IllegalStateException("arc " + arc.arcId
                        + " does not run between its endpoint nodes' vertices");
            }
            for (int index = 1; index < path.copyVertexPath.size(); index++) {
                if (topology.edgeBetween(path.copyVertexPath.get(index - 1),
                        path.copyVertexPath.get(index)) == EmbeddedMeshTopology.UNCLAIMED) {
                    throw new IllegalStateException("arc " + arc.arcId
                            + " has a hop with no copy edge behind it");
                }
            }
        }
        if (topology.claimConflictCount != 0) {
            throw new IllegalStateException(topology.claimConflictCount
                    + " copy elements are claimed by two T-mesh elements at once");
        }
    }
}
