package ixdar.nodegraph.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.quantization.LayoutExtraction;
import ixdar.geometry.mesh.quadlayout.quantization.QuantizedMeshGrid;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.FieldBroadcast;

/**
 * The {@code arc_quantization} node: quantizes an arc network with {@link QuantizedMeshGrid} and
 * collapses its zero arcs into the layout's skeleton.
 */
@MeshNodeAnnotation(id = "arc_quantization", desktopOnly = true, category = NodeCategory.QUAD_LAYOUT)
public final class QuantizedMeshGridNode implements MeshNode {

    public static final InputPort GRAPH = new InputPort("graph", PortType.ARC_NETWORK, null);
    public static final InputPort ALPHA_DEGREES = new InputPort("alpha_degrees", PortType.FLOAT,
            MotorcycleGraph.DEFAULT_ALPHA_DEGREES);
    public static final OutputPort SKELETON = new OutputPort("skeleton", PortType.ARC_NETWORK);
    public static final OutputPort SEPARATION_CUTS = new OutputPort("separation_cuts", PortType.INT);
    public static final OutputPort SEPARATION_VIOLATED = new OutputPort("separation_violated",
            PortType.BOOLEAN);
    public static final OutputPort VARIABLES = new OutputPort("variables", PortType.INT);

    @Override
    public List<InputPort> inputs() {
        return List.of(GRAPH, ALPHA_DEGREES);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(SKELETON, SEPARATION_CUTS, SEPARATION_VIOLATED, VARIABLES);
    }

    @Override
    public String description() {
        return "Solves the quantization ILP over an arc network (one integer length per arc) and"
                + " collapses zero-quantized arcs into the layout's separatrix skeleton.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GRAPH.name, "Arc network to quantize, from a motorcycle_graph node.",
                ALPHA_DEGREES.name, "Maximum separatrix deviation in degrees, bounding the ILP.",
                SKELETON.name, "The quantized skeleton: collapse clusters plus positive arcs.",
                SEPARATION_CUTS.name, "Separation cuts added beyond Lemma 1 (0 expected).",
                SEPARATION_VIOLATED.name, "Whether the solve merged two distinct singularities.",
                VARIABLES.name, "Variables in the quantization ILP after the class merge."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        ArcNetwork graph = (ArcNetwork) ctx.getInput(GRAPH.name, Object.class);
        float alphaDegrees = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, ALPHA_DEGREES.name, ALPHA_DEGREES.defaultValue),
                MotorcycleGraph.DEFAULT_ALPHA_DEGREES);
        QuantizedMeshGrid quantization =
                new QuantizedMeshGrid(graph, (float) Math.toRadians(alphaDegrees)).build();
        quantization.layout = new LayoutExtraction(quantization).build();
        ctx.setOutput(SKELETON.name, graph);
        ctx.setOutput(SEPARATION_CUTS.name, quantization.separationCutCount);
        ctx.setOutput(SEPARATION_VIOLATED.name, quantization.singularitySeparationViolated);
        ctx.setOutput(VARIABLES.name, quantization.variableCount);
    }
}
