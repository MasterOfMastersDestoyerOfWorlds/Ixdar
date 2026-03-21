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

@MeshNodeAnnotation(id = "float_to_int")
public class FloatToIntNode implements MeshNode {

    public static final ModeConstraint MODE_CONSTRAINT = new ModeConstraint(
            "ROUND",
            List.of("ROUND", "FLOOR", "CEIL", "TRUNCATE"),
            Map.of("TRUNC", "TRUNCATE"));

    public enum Mode {
        ROUND,
        FLOOR,
        CEIL,
        TRUNCATE;

        public static Mode parse(String raw) {
            return Mode.valueOf(MODE_CONSTRAINT.normalize(raw));
        }
    }

    private static final InputPort VALUE = new InputPort("value", PortType.FLOAT, 0.0f);
    private static final InputPort MODE = new InputPort("mode", PortType.STRING, "ROUND", MODE_CONSTRAINT);
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
        String modeStr = ctx.getInput("mode", String.class);
        float v = valueNum == null ? 0f : valueNum.floatValue();
        Mode mode = Mode.parse(modeStr);

        int out = switch (mode) {
            case ROUND -> Math.round(v);
            case FLOOR -> (int) Math.floor(v);
            case CEIL -> (int) Math.ceil(v);
            case TRUNCATE -> (int) v;
        };
        ctx.setOutput("result", out);
    }
}
