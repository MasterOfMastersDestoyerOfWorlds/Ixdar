package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.HashMap;
import java.util.Map;

import ixdar.geometry.mesh.quadlayout.embedding.records.ArcEdgePath;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TMeshNode;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TraceArc;
import ixdar.geometry.mesh.quadlayout.quantization.LayoutExtraction;
import ixdar.geometry.mesh.quadlayout.quantization.QuantizedMeshGrid;
import ixdar.platform.Platforms;

/**
 * T-mesh re-embedding, construction half: builds a working copy of the input
 * mesh, gives every T-mesh node a copy vertex, and carves every traced arc into
 * the copy as an edge path. Zero-quantized arcs are carved too;
 * {@link EmbeddedTMesh#contract} consumes them.
 *
 * <p>
 * See also: LCBK19 Section 6.1
 */
public final class LayoutEmbedding {

    /** Nanoseconds per second, for the timing log. */
    private static final double NANOS_PER_SECOND = 1.0e9;

    public final LayoutExtraction layout;
    public final MotorcycleGraph motorcycleGraph;
    public final QuantizedMeshGrid quantization;

    /** Working copy with provenance and claims. */
    public EmbeddedMeshTopology topology;

    /** The snapping carve, for its counters. */
    public SnappingCarve snapping;

    /** Copy vertex per T-mesh node id, or -1 for nodes no arc references. */
    public int[] vertexIdByNode;

    /** Embedded path per arc id; never {@code null} after {@link #build}. */
    public ArcEdgePath[] pathByArc;

    /**
     * LCBK19 Def 6.2 criticality per node id: singularity and feature nodes hold
     * prescribed integer positions and must never be moved by the contraction
     * operators. A non-critical node collapsed onto a critical point becomes
     * critical (the contraction stage updates this in place).
     */
    public boolean[] criticalByNode;

    /**
     * Whether each arc rides a feature trace — the closed-surface analog of
     * LCBK19's border arcs: nodes on a feature curve may only move along it, so a
     * zero feature arc is collapsible while a zero regular arc into a feature-bound
     * node is not.
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
     * Copy the mesh, place nodes, carve every trace, and check the result really is
     * a subcomplex; logs the {@code [embed]} summary.
     *
     * @return this, with all public products populated
     */
    public LayoutEmbedding build() {
        long startNanos = System.nanoTime();
        markCriticality();
        snapping = new SnappingCarve(motorcycleGraph).placeNodes().sliceArcs()
                .tagSourceEdges().carve();
        topology = snapping.topology;
        pathByArc = snapping.pathByArc;
        vertexIdByNode = snapping.vertexIdByNode;
        nodesOnMeshVertexCount = snapping.nodesOnVertexCount;
        long carveDoneNanos = System.nanoTime();
        topology.copy.computeNormals();
        snapping.report();
        assertSubcomplex();
        long checkDoneNanos = System.nanoTime();
        Platforms.log("[embed] arcs=%d copy V=%d E=%d F=%d %.2fs (carve %.2f check %.2f)%n",
                motorcycleGraph.arcs.size(), topology.copy.vertexCount(),
                topology.copy.edgeCount(), topology.copy.faceCount(),
                (checkDoneNanos - startNanos) / NANOS_PER_SECOND,
                (carveDoneNanos - startNanos) / NANOS_PER_SECOND,
                (checkDoneNanos - carveDoneNanos) / NANOS_PER_SECOND);
        return this;
    }

    /**
     * Mark LCBK19 Def 6.2 criticality: singularity and feature nodes are critical
     * (their integer positions are prescribed by the quantization), and
     * feature-trace arcs are critical curves. Trace-crossing intersection nodes
     * stay non-critical until a contraction collapse lands them on a critical
     * point.
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
     * Check that the carve really produced a subcomplex of the working copy: every
     * arc embedded, every hop a real edge, and no two T-mesh elements sharing a
     * mesh element. A violation here is an upstream invariant break, so it throws
     * rather than being papered over.
     */
    private void assertSubcomplex() {
        for (TraceArc arc : motorcycleGraph.arcs) {
            ArcEdgePath path = pathByArc[arc.arcId];
            if (path == null) {
                throw new IllegalStateException("arc " + arc.arcId + " was never carved");
            }
            if (path.copyVertexPath.get(0) != vertexIdByNode[arc.startNodeId]
                    || path.copyVertexPath.get(path.copyVertexPath.size() - 1) != vertexIdByNode[arc.endNodeId]) {
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
