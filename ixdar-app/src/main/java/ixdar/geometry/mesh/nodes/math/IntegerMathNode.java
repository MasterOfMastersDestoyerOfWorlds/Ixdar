package ixdar.geometry.mesh.nodes.math;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.ModeConstraint;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

/**
 * MeshNode that performs scalar integer arithmetic: ADD, SUBTRACT, MULTIPLY,
 * DIVIDE (returns 0 on divide-by-zero), MODULO (same), POWER, MIN, MAX.
 */
@MeshNodeAnnotation(id = "integer_math")
public class IntegerMathNode implements MeshNode {
    public static final String ADD = "ADD";
    public static final String SUBTRACT = "SUBTRACT";
    public static final String MULTIPLY = "MULTIPLY";
    public static final String DIVIDE = "DIVIDE";
    public static final String MODULO = "MODULO";
    public static final String POWER = "POWER";
    public static final String A_2 = "a";
    public static final String B_2 = "b";
    public static final String OPERATION_2 = "operation";
    public static final String RESULT_2 = "result";

    public static final ModeConstraint MODE_CONSTRAINT = new ModeConstraint(
            ADD,
            List.of(ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO, POWER, "MIN", "MAX"),
            Map.of(
                    "SUB", SUBTRACT,
                    "MUL", MULTIPLY,
                    "DIV", DIVIDE,
                    "MOD", MODULO,
                    "POW", POWER));

    private static final InputPort A = new InputPort(A_2, PortType.INT, 0, -1000f, 1000f);
    private static final InputPort B = new InputPort(B_2, PortType.INT, 0, -1000f, 1000f);
    private static final InputPort OPERATION = new InputPort(OPERATION_2, PortType.STRING, ADD, MODE_CONSTRAINT);
    private static final OutputPort RESULT = new OutputPort(RESULT_2, PortType.INT);

    /** {@inheritDoc}. */
    @Override
    public String description() {
        return "Integer arithmetic with modes ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO, POWER, MIN, MAX.";
    }

    /** {@inheritDoc}. */
    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                A_2, "Left integer operand.",
                B_2, "Right integer operand.",
                OPERATION_2, "Operation: ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO, POWER, MIN, MAX.",
                RESULT_2, "Integer result."
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
        return List.of(RESULT);
    }

    /** {@inheritDoc}. */
    @Override
    public void evaluate(NodeContext ctx) {
        Number aNum = ctx.getInput(A_2, Number.class);
        Number bNum = ctx.getInput(B_2, Number.class);
        String modeStr = ctx.getInput(OPERATION_2, String.class);
        int a = aNum == null ? 0 : aNum.intValue();
        int b = bNum == null ? 0 : bNum.intValue();
        Mode mode = Mode.parse(modeStr);

        int out = switch (mode) {
            case ADD -> a + b;
            case SUBTRACT -> a - b;
            case MULTIPLY -> a * b;
            case DIVIDE -> b == 0 ? 0 : a / b;
            case MODULO -> b == 0 ? 0 : a % b;
            case POWER -> (int) Math.pow(a, b);
            case MIN -> Math.min(a, b);
            case MAX -> Math.max(a, b);
        };
        ctx.setOutput(RESULT_2, out);
    }

    public enum Mode {
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE,
        MODULO,
        POWER,
        MIN,
        MAX;

        /**
         * Parses the {@code operation} port string via the mode constraint
         * (handles aliases like {@code SUB}, {@code MUL}, {@code DIV}, {@code MOD},
         * {@code POW}; falls back to ADD on null/unknown input).
         *
         * @param raw raw {@code operation} string from the node context
         * @return matching {@link Mode}
         */
        public static Mode parse(String raw) {
            return Mode.valueOf(MODE_CONSTRAINT.normalize(raw));
        }
    }
}
