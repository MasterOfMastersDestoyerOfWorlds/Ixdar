package ixdar.geometry.mesh.nodes.math;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.BoolField;
import ixdar.annotations.meshnode.FloatField;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.ModeConstraint;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

@MeshNodeAnnotation(id = "compare")
public class CompareNode implements MeshNode {

    public static final ModeConstraint MODE_CONSTRAINT = new ModeConstraint(
            "EQUAL",
            List.of("EQUAL", "LESS", "GREATER"),
            Map.of(
                    "EQ", "EQUAL",
                    "LT", "LESS",
                    "GT", "GREATER",
                    "LESS_THAN", "LESS",
                    "GREATER_THAN", "GREATER"));

    public enum Mode {
        EQUAL,
        LESS,
        GREATER;

        public static Mode parse(String raw) {
            return Mode.valueOf(MODE_CONSTRAINT.normalize(raw));
        }
    }

    private static final InputPort A = new InputPort("a", PortType.FLOAT, 0.0f, -1000f, 1000f);
    private static final InputPort B = new InputPort("b", PortType.FLOAT, 0.0f, -1000f, 1000f);
    private static final InputPort EPSILON = new InputPort("epsilon", PortType.FLOAT, 1e-6f, 1e-8f, 1f);
    private static final InputPort MODE = new InputPort("mode", PortType.STRING, "EQUAL", MODE_CONSTRAINT);
    private static final OutputPort RESULT = new OutputPort("result", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(A, B, EPSILON, MODE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(RESULT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Object ao = FieldBroadcast.getInputOrDefault(ctx, "a", A.defaultValue());
        Object bo = FieldBroadcast.getInputOrDefault(ctx, "b", B.defaultValue());
        Object eo = FieldBroadcast.getInputOrDefault(ctx, "epsilon", EPSILON.defaultValue());
        String modeStr = ctx.getInput("mode", String.class);
        Mode mode = Mode.parse(modeStr);

        if (ao instanceof FloatField || bo instanceof FloatField || eo instanceof FloatField) {
            int n = FieldBroadcast.floatFieldLength(ao, bo);
            if (eo instanceof FloatField ef) {
                n = Math.max(n, ef.length());
            }
            boolean[] out = new boolean[n];
            for (int i = 0; i < n; i++) {
                float a = FieldBroadcast.floatAt(ao, i, 0f);
                float b = FieldBroadcast.floatAt(bo, i, 0f);
                float epsilon = Math.abs(FieldBroadcast.floatAt(eo, i, 1e-6f));
                out[i] = evalMode(mode, a, b, epsilon);
            }
            ctx.setOutput("result", new BoolField(out));
            return;
        }

        float a = FieldBroadcast.floatScalarOrDefault(ao, 0f);
        float b = FieldBroadcast.floatScalarOrDefault(bo, 0f);
        float epsilon = Math.abs(FieldBroadcast.floatScalarOrDefault(eo, 1e-6f));
        ctx.setOutput("result", evalMode(mode, a, b, epsilon));
    }

    private static boolean evalMode(Mode mode, float a, float b, float epsilon) {
        return switch (mode) {
            case LESS -> a < b - epsilon;
            case GREATER -> a > b + epsilon;
            case EQUAL -> Math.abs(a - b) <= epsilon;
        };
    }
}
