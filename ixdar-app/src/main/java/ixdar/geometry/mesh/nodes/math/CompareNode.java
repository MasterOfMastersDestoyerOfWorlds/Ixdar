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

/**
 * MeshNode that compares two scalars or {@link FloatField}s with an epsilon
 * tolerance, producing a scalar boolean or per-element {@link BoolField}
 * depending on whether any input broadcasts as a field.
 */
@MeshNodeAnnotation(id = "compare")
public class CompareNode implements MeshNode {
    public static final String EQUAL = "EQUAL";
    public static final String LESS = "LESS";
    public static final String GREATER = "GREATER";
    public static final String A_2 = "a";
    public static final String B_2 = "b";
    public static final String EPSILON_2 = "epsilon";
    public static final String MODE_2 = "mode";
    public static final String VALUE_2 = "value";
    public static final float NUM_0 = 0f;
    public static final float NUM_1e_6 = 1e-6f;

    public static final ModeConstraint MODE_CONSTRAINT = new ModeConstraint(
            EQUAL,
            List.of(EQUAL, LESS, GREATER),
            Map.of(
                    "EQ", EQUAL,
                    "LT", LESS,
                    "GT", GREATER,
                    "LESS_THAN", LESS,
                    "GREATER_THAN", GREATER));

    private static final InputPort A = new InputPort(A_2, PortType.FLOAT, 0.0f, -1000f, 1000f);
    private static final InputPort B = new InputPort(B_2, PortType.FLOAT, 0.0f, -1000f, 1000f);
    private static final InputPort EPSILON = new InputPort(EPSILON_2, PortType.FLOAT, 1e-6f, 1e-8f, 1f);
    private static final InputPort MODE = new InputPort(MODE_2, PortType.STRING, EQUAL, MODE_CONSTRAINT);
    private static final OutputPort VALUE = new OutputPort(VALUE_2, PortType.BOOLEAN);

    /** {@inheritDoc}. */
    @Override
    public String description() {
        return "Compares two float values with an epsilon tolerance using modes EQUAL, LESS, GREATER.";
    }

    /** {@inheritDoc}. */
    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                A_2, "Left operand (scalar or per-vertex FloatField).",
                B_2, "Right operand.",
                EPSILON_2, "Tolerance for EQUAL mode. EQUAL is |a - b| < epsilon; LESS/GREATER are strict when epsilon=0, otherwise include a tolerance band.",
                MODE_2, "Comparison: EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL, NOT_EQUAL.",
                VALUE_2, "Per-element BOOLEAN."
        );
    }

    /** {@inheritDoc}. */
    @Override
    public List<InputPort> inputs() {
        return List.of(A, B, EPSILON, MODE);
    }

    /** {@inheritDoc}. */
    @Override
    public List<OutputPort> outputs() {
        return List.of(VALUE);
    }

    /** {@inheritDoc}. */
    @Override
    public void evaluate(NodeContext ctx) {
        Object ao = FieldBroadcast.getInputOrDefault(ctx, A_2, A.defaultValue());
        Object bo = FieldBroadcast.getInputOrDefault(ctx, B_2, B.defaultValue());
        Object eo = FieldBroadcast.getInputOrDefault(ctx, EPSILON_2, EPSILON.defaultValue());
        String modeStr = ctx.getInput(MODE_2, String.class);
        Mode mode = Mode.parse(modeStr);

        if (ao instanceof FloatField || bo instanceof FloatField || eo instanceof FloatField) {
            int n = FieldBroadcast.floatFieldLength(ao, bo);
            if (eo instanceof FloatField ef) {
                n = Math.max(n, ef.length());
            }
            boolean[] out = new boolean[n];
            for (int i = 0; i < n; i++) {
                float a = FieldBroadcast.floatAt(ao, i, NUM_0);
                float b = FieldBroadcast.floatAt(bo, i, NUM_0);
                float epsilon = Math.abs(FieldBroadcast.floatAt(eo, i, NUM_1e_6));
                out[i] = evalMode(mode, a, b, epsilon);
            }
            ctx.setOutput(VALUE_2,new BoolField(out));
            return;
        }

        float a = FieldBroadcast.floatScalarOrDefault(ao, NUM_0);
        float b = FieldBroadcast.floatScalarOrDefault(bo, NUM_0);
        float epsilon = Math.abs(FieldBroadcast.floatScalarOrDefault(eo, NUM_1e_6));
        ctx.setOutput(VALUE_2,evalMode(mode, a, b, epsilon));
    }

    private static boolean evalMode(Mode mode, float a, float b, float epsilon) {
        return switch (mode) {
            case LESS -> a < b - epsilon;
            case GREATER -> a > b + epsilon;
            case EQUAL -> Math.abs(a - b) <= epsilon;
        };
    }

    public enum Mode {
        EQUAL,
        LESS,
        GREATER;

        /**
         * Parses the {@code mode} port string via the mode constraint (handles aliases
         * {@code EQ}, {@code LT}, {@code GT}, {@code LESS_THAN}, {@code GREATER_THAN};
         * falls back to EQUAL on null/unknown input).
         *
         * @param raw raw {@code mode} string from the node context
         * @return matching {@link Mode}
         */
        public static Mode parse(String raw) {
            return Mode.valueOf(MODE_CONSTRAINT.normalize(raw));
        }
    }
}
