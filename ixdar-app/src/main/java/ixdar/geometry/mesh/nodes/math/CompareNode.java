package ixdar.geometry.mesh.nodes.math;

import java.util.List;
import java.util.Locale;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

@MeshNodeAnnotation(id = "compare")
public class CompareNode implements MeshNode {
    private static final InputPort A = new InputPort("a", PortType.FLOAT, 0.0f);
    private static final InputPort B = new InputPort("b", PortType.FLOAT, 0.0f);
    private static final InputPort EPSILON = new InputPort("epsilon", PortType.FLOAT, 1e-6f);
    private static final InputPort MODE = new InputPort("mode", PortType.STRING, "EQUAL");
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
        Number aNum = ctx.getInput("a", Number.class);
        Number bNum = ctx.getInput("b", Number.class);
        Number epsNum = ctx.getInput("epsilon", Number.class);
        String modeIn = ctx.getInput("mode", String.class);
        float a = aNum == null ? 0f : aNum.floatValue();
        float b = bNum == null ? 0f : bNum.floatValue();
        float epsilon = epsNum == null ? 1e-6f : Math.abs(epsNum.floatValue());
        String mode = modeIn == null ? "EQUAL" : modeIn.trim().toUpperCase(Locale.ROOT);

        boolean out;
        switch (mode) {
            case "LESS":
            case "LT":
                out = a < b - epsilon;
                break;
            case "GREATER":
            case "GT":
                out = a > b + epsilon;
                break;
            case "EQUAL":
            case "EQ":
                out = Math.abs(a - b) <= epsilon;
                break;
            default:
                throw new IllegalArgumentException("compare: unknown mode '" + modeIn + "'");
        }

        ctx.setOutput("result", out);
    }
}
