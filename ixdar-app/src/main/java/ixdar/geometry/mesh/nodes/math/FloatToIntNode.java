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
    public static final String ROUND = "ROUND";
    public static final String TRUNCATE = "TRUNCATE";
    public static final String VALUE_2 = "value";
    public static final String MODE_2 = "mode";
    public static final String RESULT_2 = "result";

    public static final ModeConstraint MODE_CONSTRAINT = new ModeConstraint(
            ROUND,
            List.of(ROUND, "FLOOR", "CEIL", TRUNCATE),
            Map.of("TRUNC", TRUNCATE));

    private static final InputPort VALUE = new InputPort(VALUE_2, PortType.FLOAT, 0.0f, -1000f, 1000f);
    private static final InputPort MODE = new InputPort(MODE_2, PortType.STRING, ROUND, MODE_CONSTRAINT);
    private static final OutputPort RESULT = new OutputPort(RESULT_2, PortType.INT);

    /**
     * TODO: document {@code description}.
     *
     * @return TODO: describe
     */
    @Override
    public String description() {
        return "Converts a float to an integer using modes ROUND, FLOOR, CEIL, TRUNCATE.";
    }

    /**
     * TODO: document {@code socketDocs}.
     *
     * @return TODO: describe
     */
    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                VALUE_2, "Float input to convert.",
                MODE_2, "Rounding rule: ROUND (nearest, half away from zero), FLOOR (toward -∞), CEIL (toward +∞), TRUNCATE (toward 0).",
                RESULT_2, "Integer output."
        );
    }

    /**
     * TODO: document {@code inputs}.
     *
     * @return TODO: describe
     */
    @Override
    public List<InputPort> inputs() {
        return List.of(VALUE, MODE);
    }

    /**
     * TODO: document {@code outputs}.
     *
     * @return TODO: describe
     */
    @Override
    public List<OutputPort> outputs() {
        return List.of(RESULT);
    }

    /**
     * TODO: document {@code evaluate}.
     *
     * @param ctx TODO: describe
     */
    @Override
    public void evaluate(NodeContext ctx) {
        Number valueNum = ctx.getInput(VALUE_2, Number.class);
        String modeStr = ctx.getInput(MODE_2, String.class);
        float v = valueNum == null ? 0f : valueNum.floatValue();
        Mode mode = Mode.parse(modeStr);

        int out = switch (mode) {
            case ROUND -> Math.round(v);
            case FLOOR -> (int) Math.floor(v);
            case CEIL -> (int) Math.ceil(v);
            case TRUNCATE -> (int) v;
        };
        ctx.setOutput(RESULT_2, out);
    }

    public enum Mode {
        ROUND,
        FLOOR,
        CEIL,
        TRUNCATE;

        /**
         * TODO: document {@code parse}.
         *
         * @param raw TODO: describe
         * @return TODO: describe
         */
        public static Mode parse(String raw) {
            return Mode.valueOf(MODE_CONSTRAINT.normalize(raw));
        }
    }
}
