package ixdar.geometry.mesh.nodes.closure;

import java.util.List;

import ixdar.annotations.meshnode.FloatField;

import java.util.Map;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.curve.FloatCurveKernel;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/** Evaluates a {@link FloatCurveKernel} closure at a scalar coordinate. */
@MeshNodeAnnotation(id = "evaluate_closure")
public class EvaluateClosureNode implements MeshNode {
    public static final String CLOSURE_2 = "closure";
    public static final String VALUE_2 = "value";
    public static final String RESULT_2 = "result";

    private static final InputPort CLOSURE = new InputPort(CLOSURE_2, PortType.CLOSURE, null);
    private static final InputPort VALUE = new InputPort(VALUE_2, PortType.FLOAT, 0.0f, -1000f, 1000f);
    private static final OutputPort RESULT = new OutputPort(RESULT_2, PortType.FLOAT);

    @Override
    public String description() {
        return "Evaluates a float curve closure at a scalar or per-vertex field value, returning the mapped result.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                CLOSURE_2, "Float closure to sample (from float_curve or function_curve).",
                VALUE_2, "Input X (scalar or per-vertex FloatField). Typically in [0, 1] for float_curve; arbitrary for function_curve.",
                RESULT_2, "Sampled Y value(s)."
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
        FloatCurveKernel kernel = ctx.getInput(CLOSURE_2, FloatCurveKernel.class);
        Object vo = FieldBroadcast.getInputOrDefault(ctx, VALUE_2, VALUE.defaultValue());

        if (vo instanceof FloatField field) {
            float[] out = new float[field.length()];
            for (int i = 0; i < field.length(); i++) {
                float t = field.get(i);
                out[i] = kernel == null ? t : kernel.evaluate(t);
            }
            ctx.setOutput(RESULT_2, new FloatField(out));
            return;
        }

        float t = FieldBroadcast.floatScalarOrDefault(vo, 0f);
        if (kernel == null) {
            ctx.setOutput(RESULT_2, t);
            return;
        }
        ctx.setOutput(RESULT_2, kernel.evaluate(t));
    }
}
