package ixdar.geometry.mesh.nodes.math;

import java.util.List;
import java.util.Locale;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

@MeshNodeAnnotation(id = "float_to_int")
public class FloatToIntNode implements MeshNode {
    private static final InputPort VALUE = new InputPort("value", PortType.FLOAT, 0.0f);
    private static final InputPort MODE = new InputPort("mode", PortType.STRING, "ROUND");
    private static final OutputPort RESULT = new OutputPort("result", PortType.INT);

    @Override
    public List<InputPort> inputs() {
        return List.of(VALUE, MODE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(RESULT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number valueNum = ctx.getInput("value", Number.class);
        String modeIn = ctx.getInput("mode", String.class);
        float v = valueNum == null ? 0f : valueNum.floatValue();
        String mode = modeIn == null ? "ROUND" : modeIn.trim().toUpperCase(Locale.ROOT);

        int out = switch (mode) {
            case "ROUND" -> Math.round(v);
            case "FLOOR" -> (int) Math.floor(v);
            case "CEIL" -> (int) Math.ceil(v);
            case "TRUNCATE", "TRUNC" -> (int) v;
            default -> throw new IllegalArgumentException("float_to_int: unknown mode '" + modeIn + "'");
        };
        ctx.setOutput("result", out);
    }
}
