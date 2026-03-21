package ixdar.geometry.mesh.nodes.math;

import java.util.List;
import java.util.Locale;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

@MeshNodeAnnotation(id = "integer_math")
public class IntegerMathNode implements MeshNode {
    private static final InputPort A = new InputPort("a", PortType.INT, 0);
    private static final InputPort B = new InputPort("b", PortType.INT, 0);
    private static final InputPort MODE = new InputPort("mode", PortType.STRING, "ADD");
    private static final OutputPort RESULT = new OutputPort("result", PortType.INT);

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
        String modeIn = ctx.getInput("mode", String.class);
        int a = aNum == null ? 0 : aNum.intValue();
        int b = bNum == null ? 0 : bNum.intValue();
        String mode = modeIn == null ? "ADD" : modeIn.trim().toUpperCase(Locale.ROOT);

        int out = switch (mode) {
            case "ADD" -> a + b;
            case "SUBTRACT", "SUB" -> a - b;
            case "MULTIPLY", "MUL" -> a * b;
            case "DIVIDE", "DIV" -> b == 0 ? 0 : a / b;
            case "MODULO", "MOD" -> b == 0 ? 0 : a % b;
            case "POWER", "POW" -> (int) Math.pow(a, b);
            case "MIN" -> Math.min(a, b);
            case "MAX" -> Math.max(a, b);
            default -> throw new IllegalArgumentException("integer_math: unknown mode '" + modeIn + "'");
        };
        ctx.setOutput("result", out);
    }
}
