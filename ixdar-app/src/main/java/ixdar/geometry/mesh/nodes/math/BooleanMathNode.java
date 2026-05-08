package ixdar.geometry.mesh.nodes.math;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.BoolField;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.ModeConstraint;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

/**
 * MeshNode that performs per-element boolean logic (AND, OR, NOT, XOR) on
 * scalar booleans or {@link BoolField} inputs, broadcasting as needed.
 */
@MeshNodeAnnotation(id = "boolean_math")
public class BooleanMathNode implements MeshNode {
    public static final String AND = "AND";
    public static final String A_2 = "a";
    public static final String B_2 = "b";
    public static final String OPERATION_2 = "operation";
    public static final String VALUE_2 = "value";

    public static final ModeConstraint MODE_CONSTRAINT = new ModeConstraint(
            AND,
            List.of(AND, "OR", "NOT", "XOR"),
            Map.of());

    private static final InputPort A = new InputPort(A_2, PortType.BOOLEAN, false);
    private static final InputPort B = new InputPort(B_2, PortType.BOOLEAN, false);
    private static final InputPort OPERATION = new InputPort(OPERATION_2, PortType.STRING, AND, MODE_CONSTRAINT);
    private static final OutputPort VALUE = new OutputPort(VALUE_2, PortType.BOOLEAN);

    /** {@inheritDoc}. */
    @Override
    public String description() {
        return "Per-element boolean logic with modes AND, OR, NOT, XOR.";
    }

    /** {@inheritDoc}. */
    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                A_2, "Left operand (scalar bool or per-element BoolField).",
                B_2, "Right operand. Ignored for operation=NOT.",
                OPERATION_2, "Operation: AND, OR, NOT (of a), XOR.",
                VALUE_2, "Boolean result with broadcast shape of a/b."
        );
    }

    /** {@inheritDoc}. */
    @Override
    public List<InputPort> inputs() {
        return List.of(A, B, OPERATION);
    }

    /** {@inheritDoc}. */
    @Override
    public List<OutputPort> outputs() {
        return List.of(VALUE);
    }

    /** {@inheritDoc}. */
    @Override
    public void evaluate(NodeContext ctx) {
        Object aObj = FieldBroadcast.getInputOrDefault(ctx, A_2, A.defaultValue());
        Object bObj = FieldBroadcast.getInputOrDefault(ctx, B_2, B.defaultValue());
        String opStr = ctx.getInput(OPERATION_2, String.class);
        Mode mode = Mode.parse(opStr);

        boolean hasField = aObj instanceof BoolField || bObj instanceof BoolField;
        if (hasField) {
            int n = resolveLength(aObj, bObj);
            boolean[] out = new boolean[n];
            for (int i = 0; i < n; i++) {
                boolean a = FieldBroadcast.boolAt(aObj, i, false);
                boolean b = FieldBroadcast.boolAt(bObj, i, false);
                out[i] = apply(mode, a, b);
            }
            ctx.setOutput(VALUE_2,new BoolField(out));
        } else {
            boolean a = aObj instanceof Boolean ab ? ab : false;
            boolean b = bObj instanceof Boolean bb ? bb : false;
            ctx.setOutput(VALUE_2,apply(mode, a, b));
        }
    }

    private static boolean apply(Mode mode, boolean a, boolean b) {
        return switch (mode) {
            case AND -> a && b;
            case OR -> a || b;
            case NOT -> !a;
            case XOR -> a ^ b;
        };
    }

    private static int resolveLength(Object a, Object b) {
        int la = a instanceof BoolField ba ? ba.length() : 0;
        int lb = b instanceof BoolField bb ? bb.length() : 0;
        if (la > 0 && lb > 0 && la != lb) {
            throw new IllegalArgumentException("BoolField length mismatch: " + la + " vs " + lb);
        }
        return Math.max(la, lb);
    }

    public enum Mode {
        AND,
        OR,
        NOT,
        XOR;

        /**
         * Parses the {@code operation} port string via the mode constraint
         * (case-insensitive, falls back to AND on null/unknown input).
         *
         * @param raw raw {@code operation} string from the node context
         * @return matching {@link Mode}
         */
        public static Mode parse(String raw) {
            return Mode.valueOf(MODE_CONSTRAINT.normalize(raw));
        }
    }
}
