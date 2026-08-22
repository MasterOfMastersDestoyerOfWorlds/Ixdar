package ixdar.geometry.mesh.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * Traces the motorcycle-graph T-mesh over a seamless parametrization:
 * separatrices leave every singularity, stop where they cross another trace,
 * and the arrangement's cells are the T-mesh patches. Tracing a quad mesh's
 * separatrices directly (the QuadMixer use) is a planned second input mode.
 *
 * <p>See also: Lyon 2021 Section 3, Eppstein 2008
 */
@MeshNodeAnnotation(id = "motorcycle_graph", desktopOnly = true)
public class MotorcycleGraphNode implements MeshNode {

    public static final float DEFAULT_ALPHA_DEGREES = 15.0f;

    public static final InputPort UV = new InputPort("uv", PortType.UV_FIELD, null);
    public static final InputPort ALPHA_DEGREES = new InputPort("alpha_degrees", PortType.FLOAT,
            DEFAULT_ALPHA_DEGREES);
    public static final OutputPort GRAPH = new OutputPort("graph", PortType.ARC_NETWORK);
    public static final OutputPort NODE_COUNT = new OutputPort("node_count", PortType.INT);
    public static final OutputPort ARC_COUNT = new OutputPort("arc_count", PortType.INT);
    public static final OutputPort PATCH_COUNT = new OutputPort("patch_count", PortType.INT);

    @Override
    public List<InputPort> inputs() {
        return List.of(UV, ALPHA_DEGREES);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GRAPH, NODE_COUNT, ARC_COUNT, PATCH_COUNT);
    }

    @Override
    public String description() {
        return "Traces the motorcycle-graph T-mesh over a seamless parametrization: the"
                + " separatrix arrangement whose cells are the layout's patches.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                UV.name, "Seamless parametrization to trace, from a seamless_uv node.",
                ALPHA_DEGREES.name, "Maximum separatrix deviation in degrees, the quality knob.",
                GRAPH.name, "The T-mesh arrangement: nodes, arcs, traces, and patches.",
                NODE_COUNT.name, "Number of T-mesh nodes in the arrangement.",
                ARC_COUNT.name, "Number of T-mesh arcs in the arrangement.",
                PATCH_COUNT.name, "Number of T-mesh patches (arrangement cells)."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        SeamlessParameterization seamless = (SeamlessParameterization) ctx.getInput(UV.name, Object.class);
        float alphaDegrees = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, ALPHA_DEGREES.name, ALPHA_DEGREES.defaultValue),
                DEFAULT_ALPHA_DEGREES);
        MotorcycleGraph graph = new MotorcycleGraph(seamless, (float) Math.toRadians(alphaDegrees));
        graph.build();
        ctx.setOutput(GRAPH.name, graph);
        ctx.setOutput(NODE_COUNT.name, graph.nodes.size());
        ctx.setOutput(ARC_COUNT.name, graph.arcs.size());
        ctx.setOutput(PATCH_COUNT.name, graph.patches.size());
    }
}
