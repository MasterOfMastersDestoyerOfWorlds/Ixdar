package ixdar.nodegraph.nodes.closure;

import java.util.List;

import ixdar.nodegraph.field.FloatField;

import java.util.Map;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.FloatCurveKernel;
import ixdar.nodegraph.field.FieldBroadcast;

/** Evaluates a {@link FloatCurveKernel} closure at a scalar coordinate. */
@MeshNodeAnnotation(id = "evaluate_closure", category = NodeCategory.CLOSURE)
public class EvaluateClosureNode implements MeshNode {
    public static final InputPort CLOSURE = new InputPort("closure", PortType.CLOSURE, null);
    public static final InputPort VALUE = new InputPort("value", PortType.FLOAT, 0.0f, -1000f, 1000f);
    public static final OutputPort RESULT = new OutputPort("result", PortType.FLOAT);

    @Override
    public String description() {
        return "Evaluates a float curve closure at a scalar or per-vertex field value, returning the mapped result.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                CLOSURE.name, "Float closure to sample (from float_curve or function_curve).",
                VALUE.name, "Input X (scalar or per-vertex FloatField). Typically in [0, 1] for float_curve; arbitrary for function_curve.",
                RESULT.name, "Sampled Y value(s)."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(CLOSURE, VALUE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(RESULT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        FloatCurveKernel kernel = ctx.getInput(CLOSURE.name, FloatCurveKernel.class);
        Object vo = FieldBroadcast.getInputOrDefault(ctx, VALUE.name, VALUE.defaultValue);

        if (vo instanceof FloatField field) {
            float[] out = new float[field.length()];
            for (int i = 0; i < field.length(); i++) {
                float t = field.get(i);
                out[i] = kernel == null ? t : kernel.evaluate(t);
            }
            ctx.setOutput(RESULT.name, new FloatField(out));
            return;
        }

        float t = FieldBroadcast.floatScalarOrDefault(vo, 0f);
        if (kernel == null) {
            ctx.setOutput(RESULT.name, t);
            return;
        }
        ctx.setOutput(RESULT.name, kernel.evaluate(t));
    }
}
