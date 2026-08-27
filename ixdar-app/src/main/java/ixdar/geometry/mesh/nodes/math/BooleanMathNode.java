package ixdar.geometry.mesh.nodes.math;

import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.BoolField;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.ModeConstraint;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;

/**
 * MeshNode that performs per-element boolean logic (AND, OR, NOT, XOR) on
 * scalar booleans or {@link BoolField} inputs, broadcasting as needed.
 */
@MeshNodeAnnotation(id = "boolean_math")
public class BooleanMathNode implements MeshNode {
    public static final String AND = "AND";
    public static final ModeConstraint MODE_CONSTRAINT = new ModeConstraint(
            AND,
            List.of(AND, "OR", "NOT", "XOR"),
            Map.of());

    public static final InputPort A = new InputPort("a", PortType.BOOLEAN, false);
    public static final InputPort B = new InputPort("b", PortType.BOOLEAN, false);
    public static final InputPort OPERATION = new InputPort("operation", PortType.STRING, AND, MODE_CONSTRAINT);
    public static final OutputPort VALUE = new OutputPort("value", PortType.BOOLEAN);

    /** {@inheritDoc}. */
    @Override
    public String description() {
        return "Per-element boolean logic with modes AND, OR, NOT, XOR.";
    }

    /** {@inheritDoc}. */
    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                A.name, "Left operand (scalar bool or per-element BoolField).",
                B.name, "Right operand. Ignored for operation=NOT.",
                OPERATION.name, "Operation: AND, OR, NOT (of a), XOR.",
                VALUE.name, "Boolean result with broadcast shape of a/b."
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
        Object aObj = FieldBroadcast.getInputOrDefault(ctx, A.name, A.defaultValue);
        Object bObj = FieldBroadcast.getInputOrDefault(ctx, B.name, B.defaultValue);
        String opStr = ctx.getInput(OPERATION.name, String.class);
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
            ctx.setOutput(VALUE.name,new BoolField(out));
        } else {
            boolean a = aObj instanceof Boolean ab ? ab : false;
            boolean b = bObj instanceof Boolean bb ? bb : false;
            ctx.setOutput(VALUE.name,apply(mode, a, b));
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
