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

@MeshNodeAnnotation(id = "boolean_math")
public class BooleanMathNode implements MeshNode {

    public static final ModeConstraint MODE_CONSTRAINT = new ModeConstraint(
            "AND",
            List.of("AND", "OR", "NOT", "XOR"),
            Map.of());

    public enum Mode {
        AND,
        OR,
        NOT,
        XOR;

        public static Mode parse(String raw) {
            return Mode.valueOf(MODE_CONSTRAINT.normalize(raw));
        }
    }

    private static final InputPort A = new InputPort("a", PortType.BOOLEAN, false);
    private static final InputPort B = new InputPort("b", PortType.BOOLEAN, false);
    private static final InputPort MODE = new InputPort("mode", PortType.STRING, "AND", MODE_CONSTRAINT);
    private static final OutputPort VALUE = new OutputPort("value", PortType.BOOLEAN);

    @Override
    public String description() {
        return "Per-element boolean logic with modes AND, OR, NOT, XOR.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "a", "Left operand (scalar bool or per-element BoolField).",
                "b", "Right operand. Ignored for mode=NOT.",
                "mode", "Operation: AND, OR, NOT (of a), XOR.",
                "value", "Boolean result with broadcast shape of a/b."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(A, B, MODE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(VALUE);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Object aObj = FieldBroadcast.getInputOrDefault(ctx, "a", A.defaultValue());
        Object bObj = FieldBroadcast.getInputOrDefault(ctx, "b", B.defaultValue());
        String modeStr = ctx.getInput("mode", String.class);
        Mode mode = Mode.parse(modeStr);

        boolean hasField = aObj instanceof BoolField || bObj instanceof BoolField;
        if (hasField) {
            int n = resolveLength(aObj, bObj);
            boolean[] out = new boolean[n];
            for (int i = 0; i < n; i++) {
                boolean a = FieldBroadcast.boolAt(aObj, i, false);
                boolean b = FieldBroadcast.boolAt(bObj, i, false);
                out[i] = apply(mode, a, b);
            }
            ctx.setOutput("value",new BoolField(out));
        } else {
            boolean a = aObj instanceof Boolean ab ? ab : false;
            boolean b = bObj instanceof Boolean bb ? bb : false;
            ctx.setOutput("value",apply(mode, a, b));
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
}
