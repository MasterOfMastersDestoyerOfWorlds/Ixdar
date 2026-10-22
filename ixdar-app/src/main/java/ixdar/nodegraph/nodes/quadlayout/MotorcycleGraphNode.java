package ixdar.nodegraph.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.ChartAtlas;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.BoolField;
import ixdar.nodegraph.field.FieldBroadcast;
import ixdar.nodegraph.field.IntField;
import ixdar.nodegraph.field.UvField;

/**
 * The {@code motorcycle_graph} node: traces the motorcycle-graph T-mesh of a seamless
 * parametrization with {@link MotorcycleGraph}.
 */
@MeshNodeAnnotation(id = "motorcycle_graph", desktopOnly = true, category = NodeCategory.QUAD_LAYOUT)
public final class MotorcycleGraphNode implements MeshNode {

    public static final InputPort GEOMETRY = InputPort.manifoldGeometry("geometry");
    public static final InputPort UV = new InputPort("uv", PortType.UV_FIELD, null);
    public static final InputPort SINGULARITIES = new InputPort("singularities",
            PortType.INT, null);
    public static final InputPort FEATURE_EDGES = new InputPort("feature_edges",
            PortType.BOOLEAN, null);
    public static final InputPort ALPHA_DEGREES = new InputPort("alpha_degrees", PortType.FLOAT,
            MotorcycleGraph.DEFAULT_ALPHA_DEGREES);
    public static final InputPort CHARTS = new InputPort("charts", PortType.CHART_ATLAS, null);
    public static final OutputPort GRAPH = new OutputPort("graph", PortType.ARC_NETWORK);
    public static final OutputPort NODE_COUNT = new OutputPort("node_count", PortType.INT);
    public static final OutputPort ARC_COUNT = new OutputPort("arc_count", PortType.INT);
    public static final OutputPort PATCH_COUNT = new OutputPort("patch_count", PortType.INT);
    public static final OutputPort ORPHANED_TRACES = new OutputPort("orphaned_traces", PortType.INT);
    public static final OutputPort REPEATED_CHAIN_NODES = new OutputPort("repeated_chain_nodes",
            PortType.INT);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, UV, CHARTS, SINGULARITIES, FEATURE_EDGES, ALPHA_DEGREES);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GRAPH, NODE_COUNT, ARC_COUNT, PATCH_COUNT, ORPHANED_TRACES,
                REPEATED_CHAIN_NODES);
    }

    @Override
    public String description() {
        return "Traces the motorcycle-graph T-mesh over a seamless parametrization: the"
                + " separatrix arrangement whose cells are the layout's patches.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.ofEntries(
                Map.entry(GEOMETRY.name, "Geometry carrying the parametrized triangle mesh."),
                Map.entry(UV.name, "Seamless per-corner UV field to trace, from a seamless_uv node."),
                Map.entry(SINGULARITIES.name,
                        "Per-vertex index4 of the cone points the separatrices spawn from"
                                + " (0 = not singular), from a cross_field node."),
                Map.entry(FEATURE_EDGES.name,
                        "Per-edge selection of feature and boundary curves, from a"
                                + " cross_field node."),
                Map.entry(ALPHA_DEGREES.name,
                        "Maximum separatrix deviation in degrees, the quality knob."),
                Map.entry(CHARTS.name,
                        "The parametrization's charts and cut transitions, from a seamless_uv"
                                + " node."),
                Map.entry(GRAPH.name, "The T-mesh arrangement: nodes, arcs, traces, and patches."),
                Map.entry(NODE_COUNT.name, "Number of T-mesh nodes in the arrangement."),
                Map.entry(ARC_COUNT.name, "Number of T-mesh arcs in the arrangement."),
                Map.entry(PATCH_COUNT.name, "Number of T-mesh patches (arrangement cells)."),
                Map.entry(ORPHANED_TRACES.name,
                        "Traces still alive when the event queue drained (0 expected)."),
                Map.entry(REPEATED_CHAIN_NODES.name,
                        "Trace chains containing one node twice (0 expected)."));
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = ctx.getInput(GEOMETRY.name, GeometryBundle.class);
        UvField inputUv = (UvField) ctx.getInput(UV.name, Object.class);
        IntField cones = (IntField) ctx.getInput(SINGULARITIES.name, Object.class);
        BoolField features = (BoolField) ctx.getInput(FEATURE_EDGES.name, Object.class);
        float alphaDegrees = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, ALPHA_DEGREES.name, ALPHA_DEGREES.defaultValue),
                MotorcycleGraph.DEFAULT_ALPHA_DEGREES);
        ChartAtlas chartsInput = (ChartAtlas) ctx.getInput(CHARTS.name, Object.class);
        HalfEdgeMesh inputMesh = (HalfEdgeMesh) bundle.mesh();
        MotorcycleGraph graph = new MotorcycleGraph(inputMesh, inputUv, chartsInput, cones,
                features, (float) Math.toRadians(alphaDegrees));
        graph.build();
        ctx.setOutput(GRAPH.name, graph.network);
        ctx.setOutput(NODE_COUNT.name, graph.network.nodes.size());
        ctx.setOutput(ARC_COUNT.name, graph.network.arcs.size());
        ctx.setOutput(PATCH_COUNT.name, graph.network.patches.size());
        ctx.setOutput(ORPHANED_TRACES.name, graph.aliveAtQueueEndCount);
        ctx.setOutput(REPEATED_CHAIN_NODES.name, graph.repeatedChainNodeCount);
    }
}
