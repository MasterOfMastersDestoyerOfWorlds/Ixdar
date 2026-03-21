package ixdar.geometry.mesh.nodes.math;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

/**
 * Blender ShaderNodeMath-style float ops (subset used by geometry graphs).
 */
@MeshNodeAnnotation(id = "float_math")
public class FloatMathNode implements MeshNode {

    private static final InputPort OPERATION = new InputPort("operation", PortType.STRING, "ADD");
    private static final InputPort A = new InputPort("a", PortType.FLOAT, 0.0f);
    private static final InputPort B = new InputPort("b", PortType.FLOAT, 0.0f);
    private static final OutputPort RESULT = new OutputPort("result", PortType.FLOAT);

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
        String op = ctx.getInput("operation", String.class);
        if (op == null) {
            op = "ADD";
        } else {
            op = op.trim().toUpperCase();
        }
        Number aNum = ctx.getInput("a", Number.class);
        Number bNum = ctx.getInput("b", Number.class);
        float a = aNum == null ? 0f : aNum.floatValue();
        float b = bNum == null ? 0f : bNum.floatValue();

        float out = switch (op) {
            case "ADD" -> a + b;
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
        ctx.setOutput("result", out);
    }
}
