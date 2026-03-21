package ixdar.geometry.mesh.nodes.math;

import java.util.List;
import java.util.Locale;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

@MeshNodeAnnotation(id = "boolean_math")
public class BooleanMathNode implements MeshNode {
    private static final InputPort A = new InputPort("a", PortType.BOOLEAN, false);
    private static final InputPort B = new InputPort("b", PortType.BOOLEAN, false);
    private static final InputPort MODE = new InputPort("mode", PortType.STRING, "AND");
    private static final OutputPort RESULT = new OutputPort("result", PortType.BOOLEAN);

    
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
        Boolean aIn = ctx.getInput("a", Boolean.class);
        Boolean bIn = ctx.getInput("b", Boolean.class);
        String modeIn = ctx.getInput("mode", String.class);
        boolean a = aIn != null && aIn;
        boolean b = bIn != null && bIn;
        String mode = modeIn == null ? "AND" : modeIn.trim().toUpperCase(Locale.ROOT);

        boolean out = switch (mode) {
        case "AND" -> a && b;
        case "OR" -> a || b;
        case "NOT" -> !a;
        case "XOR" -> a ^ b;
        default -> throw new IllegalArgumentException("boolean_math: unknown mode '" + modeIn + "'");
        };
        ctx.setOutput("result", out);
    }
}
