package ixdar.nodegraph.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.nodegraph.runtime.GroupBuilder;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.runtime.NodeGroup;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.nodes.data.SceneModelNode;
import ixdar.geometry.mesh.quadlayout.QuadLayout;
import ixdar.geometry.mesh.data.fields.crossfield.CrossField;
import ixdar.geometry.mesh.data.fields.crossfield.NDirectionField;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.LayoutEmbedding;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.gridmap.GlobalGridMap;
import ixdar.geometry.mesh.quadlayout.gridmap.GridMapAssembly;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.quantization.QuantizedMeshGrid;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessUv;
import ixdar.nodegraph.syntax.PythonParser;

/**
 * The quad-layout pipeline (Lyon 2021, LCBK19, LCK21a) as a compiled group of its stage nodes:
 * each stage a named output, {@link #LAYOUT} all of them as a {@link QuadLayout}.
 *
 * <p>
 * Pulling one output runs only its stages. Stages work in place, so {@link #MOTORCYCLE} ends
 * quantized and {@link #EMBEDDED} contracted.
 */
@MeshNodeAnnotation(id = QuadLayoutNode.TYPE, desktopOnly = true, category = NodeCategory.QUAD_LAYOUT)
public class QuadLayoutNode extends NodeGroup {

    public static final String TYPE = "quad_layout";

    public static final float DEFAULT_TARGET_EDGE_LENGTH = 1.0f;

    public static final float DEFAULT_CURVATURE_BIAS = -1.0f;

    public static final String MODEL_CALL = "model";

    public static final String LAYOUT_CALL = "layout";

    public static final String QUANTIZE_NODE = "quantize";

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort ALPHA_DEGREES = new InputPort("alpha_degrees", PortType.FLOAT,
            MotorcycleGraph.DEFAULT_ALPHA_DEGREES);
    public static final InputPort TARGET_EDGE_LENGTH = new InputPort("target_edge_length",
            PortType.FLOAT, DEFAULT_TARGET_EDGE_LENGTH);
    public static final InputPort CURVATURE_BIAS = new InputPort("curvature_bias", PortType.FLOAT,
            DEFAULT_CURVATURE_BIAS);

    public static final OutputPort QUADS = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);
    public static final OutputPort UV = new OutputPort("uv", PortType.UV_FIELD);
    public static final OutputPort TMESH = new OutputPort("tmesh", PortType.ARC_NETWORK);
    public static final OutputPort FIELD = new OutputPort("field", PortType.CROSS_FIELD);
    public static final OutputPort SINGULARITIES = new OutputPort("singularities", PortType.INT);
    public static final OutputPort SEAMLESS = new OutputPort("seamless", PortType.UV_FIELD);
    public static final OutputPort MOTORCYCLE = new OutputPort("motorcycle", PortType.ARC_NETWORK);
    public static final OutputPort SKELETON = new OutputPort("skeleton", PortType.ARC_NETWORK);
    public static final OutputPort EMBEDDED = new OutputPort("embedded", PortType.ARC_NETWORK);
    public static final OutputPort SURFACES = new OutputPort("surfaces", PortType.GEOMETRY_BUNDLE);
    public static final OutputPort COONS = new OutputPort("coons", PortType.GEOMETRY_BUNDLE);
    public static final OutputPort LAYOUT = new OutputPort(LAYOUT_CALL, PortType.QUAD_LAYOUT);

    public static final String ALPHA_DEGREES_OVERRIDE = LAYOUT_CALL
            + NodeGraphRuntime.PORT_SEPARATOR + ALPHA_DEGREES.name;

    /**
     * The reference a scene pulls one output of the layout call in {@link #overSceneModel} by.
     *
     * @param port one of this group's output ports
     * @return {@code layout.<port>}
     */
    public static String sceneOutput(OutputPort port) {
        return LAYOUT_CALL + NodeGraphRuntime.PORT_SEPARATOR + port.name;
    }

    /**
     * The graph every quad-layout scene runs: the scene's model through one layout call, as
     * {@code model = scene_model()} then {@code layout = quad_layout(geometry=model.geometry)}.
     *
     * @return the graph's statements
     * @throws IllegalStateException in a build without the desktop-only nodes
     */
    public static List<PythonParser.ParsedNode> overSceneModel() {
        GroupBuilder graph = new GroupBuilder();
        PythonParser.ParsedNode model = graph.node(MODEL_CALL, SceneModelNode::new);
        // By registered id, not QuadLayoutNode::new: the scenes calling this are reachable from
        // the web build, which must never instantiate a desktop-only node.
        graph.node(LAYOUT_CALL, TYPE)
                .input(GEOMETRY.name, model.port(SceneModelNode.GEOMETRY.name));
        return graph.statements;
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, ALPHA_DEGREES, TARGET_EDGE_LENGTH, CURVATURE_BIAS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(QUADS, UV, TMESH, FIELD, SINGULARITIES, SEAMLESS, MOTORCYCLE, SKELETON,
                EMBEDDED, SURFACES, COONS, LAYOUT);
    }

    @Override
    public String description() {
        return "Runs the quad-layout pipeline over a triangle mesh as a group of its stage nodes:"
                + " cross field, seamless parametrization, motorcycle graph, quantization,"
                + " embedding, contraction, integer grid map, relaxation, quad extraction and patch"
                + " surfaces.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.ofEntries(
                Map.entry(GEOMETRY.name, "Triangle mesh in; the extracted pure quad mesh out."),
                Map.entry(ALPHA_DEGREES.name, "Maximum separatrix deviation in degrees, the quality"
                        + " knob."),
                Map.entry(TARGET_EDGE_LENGTH.name, "Parametric length one quad edge spans."),
                Map.entry(CURVATURE_BIAS.name, "Cross-field curvature alignment bias."),
                Map.entry(UV.name, "The relaxed integer grid map the quads were extracted from."),
                Map.entry(TMESH.name, "The contracted embedded T-mesh, one patch per quad grid."),
                Map.entry(FIELD.name, "The cross field the layout follows."),
                Map.entry(SINGULARITIES.name, "Per-vertex index4 of the field's cone points"
                        + " (0 = not singular)."),
                Map.entry(SEAMLESS.name, "The seamless parametrization."),
                Map.entry(MOTORCYCLE.name, "The motorcycle-graph arrangement, quantized in place"
                        + " once the quantization ran."),
                Map.entry(SKELETON.name, "The quantized separatrix skeleton."),
                Map.entry(EMBEDDED.name, "The embedded T-mesh, contracted in place once the"
                        + " contraction ran."),
                Map.entry(SURFACES.name, "Every live patch's quad grid on the surface."),
                Map.entry(COONS.name, "The patch grids as Coons blends of their boundaries."),
                Map.entry(LAYOUT.name, "Every stage product as one quad layout.")
        );
    }

    @Override
    public void build(GroupBuilder group) {
        PythonParser.NodeReference geometry = group.parameter(GEOMETRY.name);
        PythonParser.NodeReference alphaDegrees = group.parameter(ALPHA_DEGREES.name);
        PythonParser.ParsedNode field = group.node(FIELD.name, NDirectionField::new)
                .input(NDirectionField.GEOMETRY.name, geometry)
                .input(NDirectionField.CURVATURE_BIAS.name, group.parameter(CURVATURE_BIAS.name));
        PythonParser.ParsedNode seamless = group.node(SEAMLESS.name, SeamlessParameterization::new)
                .input(SeamlessParameterization.FIELD.name, field.port(NDirectionField.FIELD.name));
        PythonParser.NodeReference seamlessUv = seamless.port(SeamlessParameterization.UV.name);
        PythonParser.ParsedNode motorcycle = group.node(MOTORCYCLE.name, MotorcycleGraph::new)
                .input(MotorcycleGraph.GEOMETRY.name, geometry)
                .input(MotorcycleGraph.UV.name, seamlessUv)
                .input(MotorcycleGraph.CHARTS.name, seamless.port(SeamlessParameterization.CHARTS.name))
                .input(MotorcycleGraph.SINGULARITIES.name,
                        field.port(NDirectionField.SINGULARITIES.name))
                .input(MotorcycleGraph.FEATURE_EDGES.name,
                        field.port(NDirectionField.FEATURE_EDGES.name))
                .input(MotorcycleGraph.ALPHA_DEGREES.name, alphaDegrees);
        PythonParser.ParsedNode quantize = group.node(QUANTIZE_NODE, QuantizedMeshGrid::new)
                .input(QuantizedMeshGrid.GRAPH.name, motorcycle.port(MotorcycleGraph.GRAPH.name))
                .input(QuantizedMeshGrid.ALPHA_DEGREES.name, alphaDegrees);
        PythonParser.ParsedNode embed = group.node("embed", LayoutEmbedding::new)
                .input(LayoutEmbedding.SKELETON.name, quantize.port(QuantizedMeshGrid.SKELETON.name))
                .input(LayoutEmbedding.UV.name, seamlessUv);
        PythonParser.ParsedNode contract = group.node("contract", NetworkContraction::new)
                .input(NetworkContraction.TMESH.name, embed.port(LayoutEmbedding.TMESH.name))
                .input(NetworkContraction.CONFORM.name, Boolean.FALSE);
        PythonParser.ParsedNode assembly = group.node("assembly", GridMapAssembly::new)
                .input(GridMapAssembly.TMESH.name, contract.port(NetworkContraction.TMESH_OUT.name))
                .input(GridMapAssembly.UV.name, seamlessUv)
                .input(GridMapAssembly.TARGET_EDGE_LENGTH.name,
                        group.parameter(TARGET_EDGE_LENGTH.name));
        PythonParser.ParsedNode relax = group.node("relax", NewtonSolverNode::new)
                .input(NewtonSolverNode.UV.name, assembly.port(GridMapAssembly.UV_OUT.name))
                .input(NewtonSolverNode.DOFS.name, assembly.port(GridMapAssembly.DOFS.name));
        PythonParser.ParsedNode quads = group.node("quads", GridMapQuadsNode::new)
                .input(GridMapQuadsNode.UV.name, relax.port(NewtonSolverNode.UV_OUT.name));
        PythonParser.ParsedNode surfaces = group.node(SURFACES.name, PatchSurfacesNode::new)
                .input(PatchSurfacesNode.UV.name, quads.port(GridMapQuadsNode.UV_OUT.name));

        group.output(QUADS.name, quads.port(GridMapQuadsNode.GEOMETRY.name));
        group.output(UV.name, quads.port(GridMapQuadsNode.UV_OUT.name));
        group.output(TMESH.name, contract.port(NetworkContraction.TMESH_OUT.name));
        group.output(FIELD.name, field.port(NDirectionField.FIELD.name));
        group.output(SINGULARITIES.name, field.port(NDirectionField.SINGULARITIES.name));
        group.output(SEAMLESS.name, seamlessUv);
        group.output(MOTORCYCLE.name, motorcycle.port(MotorcycleGraph.GRAPH.name));
        group.output(SKELETON.name, quantize.port(QuantizedMeshGrid.SKELETON.name));
        group.output(EMBEDDED.name, embed.port(LayoutEmbedding.TMESH.name));
        group.output(SURFACES.name, surfaces.port(PatchSurfacesNode.GEOMETRY.name));
        group.output(COONS.name, surfaces.port(PatchSurfacesNode.COONS.name));
    }

    /**
     * Gathers the wired stage outputs into the {@link #LAYOUT} output.
     *
     * @param ctx the call's record, every stage output set
     */
    @Override
    public void evaluate(NodeContext ctx) {
        QuadLayout layout = new QuadLayout();
        layout.crossField = ctx.getOutput(FIELD.name, CrossField.class);
        layout.seamless = ctx.getOutput(SEAMLESS.name, SeamlessUv.class);
        layout.motorcycle = ctx.getOutput(MOTORCYCLE.name, ArcNetwork.class);
        layout.skeleton = ctx.getOutput(SKELETON.name, ArcNetwork.class);
        layout.tmesh = ctx.getOutput(TMESH.name, ArcNetwork.class);
        layout.globalGrid = ctx.getOutput(UV.name, GlobalGridMap.class);
        layout.quads = ctx.getOutput(QUADS.name, GeometryBundle.class);
        layout.patchSurfaces = ctx.getOutput(SURFACES.name, GeometryBundle.class);
        layout.patchCoons = ctx.getOutput(COONS.name, GeometryBundle.class);
        ctx.setOutput(LAYOUT.name, layout);
    }
}
