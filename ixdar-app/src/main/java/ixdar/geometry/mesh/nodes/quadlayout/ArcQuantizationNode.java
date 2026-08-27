package ixdar.geometry.mesh.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.quantization.LayoutExtraction;
import ixdar.geometry.mesh.quadlayout.quantization.QuantizedMeshGrid;

/**
 * Solves the quantization ILP over an arc network's arcs and applies it:
 * zero-quantized arcs collapse combinatorially, and the surviving
 * positive-length arcs form the layout's separatrix skeleton.
 *
 * <p>See also: Lyon 2021 Sections 4-6
 */
@MeshNodeAnnotation(id = "arc_quantization", desktopOnly = true)
public class ArcQuantizationNode implements MeshNode {

    public static final InputPort GRAPH = new InputPort("graph", PortType.ARC_NETWORK, null);
    public static final InputPort ALPHA_DEGREES = new InputPort("alpha_degrees", PortType.FLOAT,
            MotorcycleGraphNode.DEFAULT_ALPHA_DEGREES);
    public static final OutputPort SKELETON = new OutputPort("skeleton", PortType.ARC_NETWORK);

    @Override
    public List<InputPort> inputs() {
        return List.of(GRAPH, ALPHA_DEGREES);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(SKELETON);
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
                SKELETON.name, "The quantized skeleton: collapse clusters plus positive arcs."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        MotorcycleGraph graph = (MotorcycleGraph) ctx.getInput(GRAPH.name, Object.class);
        float alphaDegrees = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, ALPHA_DEGREES.name, ALPHA_DEGREES.defaultValue),
                MotorcycleGraphNode.DEFAULT_ALPHA_DEGREES);
        QuantizedMeshGrid quantization =
                new QuantizedMeshGrid(graph, (float) Math.toRadians(alphaDegrees)).build();
        ctx.setOutput(SKELETON.name, new LayoutExtraction(quantization).build());
    }
}
