package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.quadlayout.embedding.records.ArcEdgePath;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedNode;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.geometry.mesh.nodes.api.UvField;
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
@MeshNodeAnnotation(id = "layout_embedding", desktopOnly = true)
public final class LayoutEmbedding implements MeshNode {

    public static final InputPort SKELETON = new InputPort("skeleton", PortType.ARC_NETWORK, null);
    public static final InputPort UV = new InputPort("uv", PortType.UV_FIELD, null);
    public static final OutputPort TMESH = new OutputPort("tmesh", PortType.ARC_NETWORK);

    /** Nanoseconds per second, for the timing log. */
    private static final double NANOS_PER_SECOND = 1.0e9;

    /** The arrangement being embedded. */
    public EmbeddedTMesh network;

    /** The parametrization the carve reads chart coordinates from. */
    public UvField uv;

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

    @Override
    public List<InputPort> inputs() {
        return List.of(SKELETON, UV);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(TMESH);
    }

    @Override
    public String description() {
        return "Carves a quantized skeleton onto a working copy of the mesh and assembles the"
                + " embedded T-mesh, validated as a cell decomposition of the surface.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                SKELETON.name, "Quantized skeleton to embed, from an arc_quantization node.",
                UV.name, "Seamless UV field the carve reads chart coordinates from.",
                TMESH.name, "The embedded T-mesh, uncontracted; zero arcs and patches remain."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        EmbeddedTMesh skeleton = (EmbeddedTMesh) ctx.getInput(SKELETON.name, Object.class);
        UvField field = (UvField) ctx.getInput(UV.name, Object.class);
        ctx.setOutput(TMESH.name, new LayoutEmbedding().build(skeleton, field));
    }

    /**
     * Copy the mesh, place nodes, carve every trace, check the result really is a
     * subcomplex, and assemble the T-mesh in place; logs the {@code [embed]}
     * summary.
     *
     * @param builtNetwork quantized arrangement to embed
     * @param seamlessUv   parametrization the carve reads chart coordinates from
     * @return the network, embedded and assembled
     */
    public EmbeddedTMesh build(EmbeddedTMesh builtNetwork, UvField seamlessUv) {
        this.network = builtNetwork;
        this.uv = seamlessUv;
        long startNanos = System.nanoTime();
        markCriticality();
        snapping = new SnappingCarve(network, uv).placeNodes().sliceArcs()
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
                network.arcs.size(), topology.copy.vertexCount(),
                topology.copy.edgeCount(), topology.copy.faceCount(),
                (checkDoneNanos - startNanos) / NANOS_PER_SECOND,
                (carveDoneNanos - startNanos) / NANOS_PER_SECOND,
                (checkDoneNanos - carveDoneNanos) / NANOS_PER_SECOND);
        network.assemble(this);
        return network;
    }

    /**
     * Mark LCBK19 Def 6.2 criticality: singularity nodes are critical (their
     * integer positions are prescribed by the quantization), and feature-trace
     * arcs are critical curves. Trace-crossing intersection nodes stay
     * non-critical until a contraction collapse lands them on a critical point.
     */
    private void markCriticality() {
        criticalByNode = new boolean[network.nodes.size()];
        for (EmbeddedNode node : network.nodes) {
            criticalByNode[node.nodeId] = node.critical;
        }
        featureByArc = new boolean[network.arcs.size()];
        Map<Integer, Trace> traceById = new HashMap<>();
        for (Trace trace : network.traces) {
            traceById.put(trace.traceId, trace);
        }
        for (EmbeddedArc arc : network.arcs) {
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
        for (EmbeddedArc arc : network.arcs) {
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
