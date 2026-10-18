package ixdar.geometry.mesh.nodes.curve;

import java.util.List;

import ixdar.nodegraph.api.InputPort;

import java.util.Map;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.FloatCurveKernel;

/**
 * Builds a float curve closure from comma-separated control points: {@code "x0,y0,x1,y1,..."}.
 * <p>
 * Same “user-editable input” family as {@code input_float} / {@code input_int} / {@code input_boolean}:
 * {@link ixdar.nodegraph.optimize.InputParameterDescriptor} lists {@code float_curve} nodes as
 * {@link ixdar.nodegraph.optimize.InputParameterDescriptor.InputParameterKind#CURVE} parameters for UI panels.
 */
@MeshNodeAnnotation(id = "float_curve", category = NodeCategory.CURVE_OPERATIONS)
public class FloatCurveNode implements MeshNode {
    public static final String STR_0_0_1_1 = "0,0,1,1";
    public static final InputPort POINTS = new InputPort("points", PortType.STRING, STR_0_0_1_1);
    public static final OutputPort CLOSURE = new OutputPort("closure", PortType.CLOSURE);

    @Override
    public String description() {
        return "Builds a float curve closure from comma-separated x,y control point pairs for use as an editable input parameter.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                POINTS.name, "Comma-separated x,y pairs: 'x0,y0,x1,y1,...'. Linear interpolation between points; clamped outside.",
                CLOSURE.name, "Float closure that can be sampled by evaluate_closure at any input x."
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
        String raw = ctx.getInput(POINTS.name, String.class);
        if (raw == null || raw.isBlank()) {
            raw = STR_0_0_1_1;
        }
        FloatCurveKernel kernel = FloatCurveKernel.fromCommaSeparatedPairs(raw);
        ctx.setOutput(CLOSURE.name, kernel);
    }
}
