package ixdar.geometry.mesh.nodes.curve;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.curve.FloatCurveKernel;

/**
 * Builds a float curve closure from comma-separated control points: {@code "x0,y0,x1,y1,..."}.
 * <p>
 * Same “user-editable input” family as {@code input_float} / {@code input_int} / {@code input_boolean}:
 * {@link ixdar.geometry.mesh.graph.InputParameterDescriptor} lists {@code float_curve} nodes as
 * {@link ixdar.geometry.mesh.graph.InputParameterDescriptor.InputParameterKind#CURVE} parameters for UI panels.
 */
@MeshNodeAnnotation(id = "float_curve")
public class FloatCurveNode implements MeshNode {

    private static final InputPort POINTS = new InputPort("points", PortType.STRING, "0,0,1,1");
    private static final OutputPort CLOSURE = new OutputPort("closure", PortType.CLOSURE);

    @Override
    public String description() {
        return "Builds a float curve closure from comma-separated x,y control point pairs for use as an editable input parameter.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "points", "Comma-separated x,y pairs: 'x0,y0,x1,y1,...'. Linear interpolation between points; clamped outside.",
                "closure", "Float closure that can be sampled by evaluate_closure at any input x."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(POINTS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(CLOSURE);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        String raw = ctx.getInput("points", String.class);
        if (raw == null || raw.isBlank()) {
            raw = "0,0,1,1";
        }
        FloatCurveKernel kernel = FloatCurveKernel.fromCommaSeparatedPairs(raw);
        ctx.setOutput("closure", kernel);
    }
}
