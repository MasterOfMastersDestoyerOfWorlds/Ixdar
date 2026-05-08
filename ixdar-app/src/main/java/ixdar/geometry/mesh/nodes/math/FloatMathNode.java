package ixdar.geometry.mesh.nodes.math;

import java.util.List;

import ixdar.annotations.meshnode.FloatField;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

/**
 * Element-wise float math operations (subset used by geometry graphs).
 */
@MeshNodeAnnotation(id = "float_math")
public class FloatMathNode implements MeshNode {
    public static final String OPERATION_2 = "operation";
    public static final String ADD = "ADD";
    public static final String A_2 = "a";
    public static final String B_2 = "b";
    public static final String RESULT_2 = "result";

    private static final InputPort OPERATION = new InputPort(OPERATION_2, PortType.STRING, ADD);
    private static final InputPort A = new InputPort(A_2, PortType.FLOAT, 0.0f, -1000f, 1000f);
    private static final InputPort B = new InputPort(B_2, PortType.FLOAT, 0.0f, -1000f, 1000f);
    private static final OutputPort RESULT = new OutputPort(RESULT_2, PortType.FLOAT);

    @Override
    public String description() {
        return "Per-element float math with operations ADD, SUBTRACT, MULTIPLY, DIVIDE, POWER, MIN, MAX, ABS, FRACT, SIN, COS, SQRT, NEGATE.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                OPERATION_2, "Op name: ADD, SUBTRACT, MULTIPLY, DIVIDE, POWER, MINIMUM, MAXIMUM, ABSOLUTE, FRACT, SIN, COS, SQRT, NEGATE, MODULO, ATAN2.",
                A_2, "Left operand (scalar or per-vertex FloatField).",
                B_2, "Right operand. Ignored for single-operand ops (ABSOLUTE, FRACT, SIN, COS, SQRT, NEGATE).",
                RESULT_2, "Per-element float."
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
        String op = ctx.getInput(OPERATION_2, String.class);
        if (op == null) {
            op = ADD;
        } else {
            op = op.trim().toUpperCase();
        }
        Object ao = FieldBroadcast.getInputOrDefault(ctx, A_2, A.defaultValue());
        Object bo = FieldBroadcast.getInputOrDefault(ctx, B_2, B.defaultValue());

        if (ao instanceof FloatField || bo instanceof FloatField) {
            int n = FieldBroadcast.floatFieldLength(ao, bo);
            float[] out = new float[n];
            for (int i = 0; i < n; i++) {
                float a = FieldBroadcast.floatAt(ao, i, 0f);
                float b = FieldBroadcast.floatAt(bo, i, 0f);
                out[i] = evalOp(op, a, b);
            }
            ctx.setOutput(RESULT_2, new FloatField(out));
            return;
        }

        float a = FieldBroadcast.floatScalarOrDefault(ao, 0f);
        float b = FieldBroadcast.floatScalarOrDefault(bo, 0f);
        ctx.setOutput(RESULT_2, evalOp(op, a, b));
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
