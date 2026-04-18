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

@MeshNodeAnnotation(id = "integer_math")
public class IntegerMathNode implements MeshNode {

    public static final ModeConstraint MODE_CONSTRAINT = new ModeConstraint(
            "ADD",
            List.of("ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "MODULO", "POWER", "MIN", "MAX"),
            Map.of(
                    "SUB", "SUBTRACT",
                    "MUL", "MULTIPLY",
                    "DIV", "DIVIDE",
                    "MOD", "MODULO",
                    "POW", "POWER"));

    public enum Mode {
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE,
        MODULO,
        POWER,
        MIN,
        MAX;

        public static Mode parse(String raw) {
            return Mode.valueOf(MODE_CONSTRAINT.normalize(raw));
        }
    }

    private static final InputPort A = new InputPort("a", PortType.INT, 0, -1000f, 1000f);
    private static final InputPort B = new InputPort("b", PortType.INT, 0, -1000f, 1000f);
    private static final InputPort MODE = new InputPort("mode", PortType.STRING, "ADD", MODE_CONSTRAINT);
    private static final OutputPort RESULT = new OutputPort("result", PortType.INT);

    @Override
    public String description() {
        return "Integer arithmetic with modes ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO, POWER, MIN, MAX.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "a", "Left integer operand.",
                "b", "Right integer operand.",
                "mode", "Operation: ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO, POWER, MIN, MAX.",
                "result", "Integer result."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(A, B, MODE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(RESULT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number aNum = ctx.getInput("a", Number.class);
        Number bNum = ctx.getInput("b", Number.class);
        String modeStr = ctx.getInput("mode", String.class);
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
        ctx.setOutput("result", out);
    }
}
