package ixdar.geometry.mesh.nodes.math;

import java.util.List;

import ixdar.geometry.mesh.nodes.api.FloatField;

import java.util.Map;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;

/**
 * Element-wise float math operations (subset used by geometry graphs).
 */
@MeshNodeAnnotation(id = "float_math")
public class FloatMathNode implements MeshNode {
    public static final String ADD = "ADD";
    public static final InputPort OPERATION = new InputPort("operation", PortType.STRING, ADD);
    public static final InputPort A = new InputPort("a", PortType.FLOAT, 0.0f, -1000f, 1000f);
    public static final InputPort B = new InputPort("b", PortType.FLOAT, 0.0f, -1000f, 1000f);
    public static final OutputPort RESULT = new OutputPort("result", PortType.FLOAT);

    @Override
    public String description() {
        return "Per-element float math with operations ADD, SUBTRACT, MULTIPLY, DIVIDE, POWER, MIN, MAX, ABS, FRACT, SIN, COS, SQRT, NEGATE.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                OPERATION.name, "Op name: ADD, SUBTRACT, MULTIPLY, DIVIDE, POWER, MINIMUM, MAXIMUM, ABSOLUTE, FRACT, SIN, COS, SQRT, NEGATE, MODULO, ATAN2.",
                A.name, "Left operand (scalar or per-vertex FloatField).",
                B.name, "Right operand. Ignored for single-operand ops (ABSOLUTE, FRACT, SIN, COS, SQRT, NEGATE).",
                RESULT.name, "Per-element float."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(OPERATION, A, B);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(RESULT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        String op = ctx.getInput(OPERATION.name, String.class);
        if (op == null) {
            op = ADD;
        } else {
            op = op.trim().toUpperCase();
        }
        Object ao = FieldBroadcast.getInputOrDefault(ctx, A.name, A.defaultValue);
        Object bo = FieldBroadcast.getInputOrDefault(ctx, B.name, B.defaultValue);

        if (ao instanceof FloatField || bo instanceof FloatField) {
            int n = FieldBroadcast.floatFieldLength(ao, bo);
            float[] out = new float[n];
            for (int i = 0; i < n; i++) {
                float a = FieldBroadcast.floatAt(ao, i, 0f);
                float b = FieldBroadcast.floatAt(bo, i, 0f);
                out[i] = evalOp(op, a, b);
            }
            ctx.setOutput(RESULT.name, new FloatField(out));
            return;
        }

        float a = FieldBroadcast.floatScalarOrDefault(ao, 0f);
        float b = FieldBroadcast.floatScalarOrDefault(bo, 0f);
        ctx.setOutput(RESULT.name, evalOp(op, a, b));
    }

    private static float evalOp(String op, float a, float b) {
        return switch (op) {
            case ADD -> a + b;
            case "SUBTRACT" -> a - b;
            case "MULTIPLY" -> a * b;
            case "DIVIDE" -> b == 0f ? 0f : a / b;
            case "POWER", "POW" -> (float) Math.pow(a, b);
            case "MINIMUM", "MIN" -> Math.min(a, b);
            case "MAXIMUM", "MAX" -> Math.max(a, b);
            case "ABSOLUTE", "ABS" -> Math.abs(a);
            case "FRACT" -> a - (float) Math.floor(a);
            case "SINE", "SIN" -> (float) Math.sin(a);
            case "COSINE", "COS" -> (float) Math.cos(a);
            case "SQRT" -> (float) Math.sqrt(Math.max(0.0, a));
            case "NEGATE" -> -a;
            default -> a + b;
        };
    }
}
