package ixdar.geometry.mesh.nodes.math;

import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.ModeConstraint;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;

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
    public static final ModeConstraint MODE_CONSTRAINT = new ModeConstraint(
            ADD,
            List.of(ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO, POWER, "MIN", "MAX"),
            Map.of(
                    "SUB", SUBTRACT,
                    "MUL", MULTIPLY,
                    "DIV", DIVIDE,
                    "MOD", MODULO,
                    "POW", POWER));

    public static final InputPort A = new InputPort("a", PortType.INT, 0, -1000f, 1000f);
    public static final InputPort B = new InputPort("b", PortType.INT, 0, -1000f, 1000f);
    public static final InputPort OPERATION = new InputPort("operation", PortType.STRING, ADD, MODE_CONSTRAINT);
    public static final OutputPort RESULT = new OutputPort("result", PortType.INT);

    /** {@inheritDoc}. */
    @Override
    public String description() {
        return "Integer arithmetic with modes ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO, POWER, MIN, MAX.";
    }

    /** {@inheritDoc}. */
    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                A.name, "Left integer operand.",
                B.name, "Right integer operand.",
                OPERATION.name, "Operation: ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO, POWER, MIN, MAX.",
                RESULT.name, "Integer result."
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
        Number aNum = ctx.getInput(A.name, Number.class);
        Number bNum = ctx.getInput(B.name, Number.class);
        String modeStr = ctx.getInput(OPERATION.name, String.class);
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
        ctx.setOutput(RESULT.name, out);
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
