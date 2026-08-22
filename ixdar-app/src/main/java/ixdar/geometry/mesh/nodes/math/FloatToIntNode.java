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
 * MeshNode that converts a float input to an integer using one of four
 * rounding modes (ROUND, FLOOR, CEIL, TRUNCATE).
 */
@MeshNodeAnnotation(id = "float_to_int")
public class FloatToIntNode implements MeshNode {
    public static final String ROUND = "ROUND";
    public static final String TRUNCATE = "TRUNCATE";
    public static final ModeConstraint MODE_CONSTRAINT = new ModeConstraint(
            ROUND,
            List.of(ROUND, "FLOOR", "CEIL", TRUNCATE),
            Map.of("TRUNC", TRUNCATE));

    public static final InputPort VALUE = new InputPort("value", PortType.FLOAT, 0.0f, -1000f, 1000f);
    public static final InputPort MODE = new InputPort("mode", PortType.STRING, ROUND, MODE_CONSTRAINT);
    public static final OutputPort RESULT = new OutputPort("result", PortType.INT);

    /** {@inheritDoc}. */
    @Override
    public String description() {
        return "Converts a float to an integer using modes ROUND, FLOOR, CEIL, TRUNCATE.";
    }

    /** {@inheritDoc}. */
    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                VALUE.name, "Float input to convert.",
                MODE.name, "Rounding rule: ROUND (nearest, half away from zero), FLOOR (toward -∞), CEIL (toward +∞), TRUNCATE (toward 0).",
                RESULT.name, "Integer output."
        );
    }

    /** {@inheritDoc}. */
    @Override
    public List<InputPort> inputs() {
        return List.of(VALUE, MODE);
    }

    /** {@inheritDoc}. */
    @Override
    public List<OutputPort> outputs() {
        return List.of(RESULT);
    }

    /** {@inheritDoc}. */
    @Override
    public void evaluate(NodeContext ctx) {
        Number valueNum = ctx.getInput(VALUE.name, Number.class);
        String modeStr = ctx.getInput(MODE.name, String.class);
        float v = valueNum == null ? 0f : valueNum.floatValue();
        Mode mode = Mode.parse(modeStr);

        int out = switch (mode) {
            case ROUND -> Math.round(v);
            case FLOOR -> (int) Math.floor(v);
            case CEIL -> (int) Math.ceil(v);
            case TRUNCATE -> (int) v;
        };
        ctx.setOutput(RESULT.name, out);
    }

    public enum Mode {
        ROUND,
        FLOOR,
        CEIL,
        TRUNCATE;

        /**
         * Parses the {@code mode} port string, applying constraint normalization (e.g.
         * the {@code TRUNC} alias maps to {@code TRUNCATE}). Falls back to the
         * constraint's default for null/unknown input.
         *
         * @param raw raw {@code mode} string from the node context
         * @return matching {@link Mode}
         */
        public static Mode parse(String raw) {
            return Mode.valueOf(MODE_CONSTRAINT.normalize(raw));
        }
    }
}
