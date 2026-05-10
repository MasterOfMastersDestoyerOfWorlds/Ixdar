package ixdar.geometry.mesh.nodes.math;

import java.util.List;
import java.util.Map;
import java.util.Random;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.ModeConstraint;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Value;

/**
 * MeshNode that produces a deterministic random scalar or vector from a seed
 * and {@code [min, max]} range. Mode (FLOAT, INT, VECTOR) selects which
 * output port carries the result; the other output ports are explicitly null.
 */
@MeshNodeAnnotation(id = "random_value")
public class RandomValueNode implements MeshNode {
    public static final String FLOAT = "FLOAT";
    public static final String INT = "INT";
    public static final String VECTOR = "VECTOR";
    public static final String FLOAT_OUT = "float_out";
    public static final String INT_OUT = "int_out";
    public static final String VECTOR_2 = "vector";
    public static final String SEED_2 = "seed";
    public static final String MIN_2 = "min";
    public static final String MAX_2 = "max";
    public static final String MODE_2 = "mode";

    public static final ModeConstraint MODE_CONSTRAINT = new ModeConstraint(
            FLOAT,
            List.of(FLOAT, INT, VECTOR),
            Map.of());

    public static final Map<String, List<String>> OUTPUT_ACTIVATION_BY_MODE = Map.of(
            FLOAT, List.of(FLOAT_OUT),
            INT, List.of(INT_OUT),
            VECTOR, List.of(VECTOR_2));

    private static final InputPort SEED = new InputPort(SEED_2, PortType.INT, 0, 0f, 1000000f);
    private static final InputPort MIN = new InputPort(MIN_2, PortType.FLOAT, 0.0f, -1000f, 1000f);
    private static final InputPort MAX = new InputPort(MAX_2, PortType.FLOAT, 1.0f, -1000f, 1000f);
    private static final InputPort MODE = new InputPort(MODE_2, PortType.STRING, FLOAT, MODE_CONSTRAINT);
    private static final OutputPort OUT_FLOAT = new OutputPort(FLOAT_OUT, PortType.FLOAT);
    private static final OutputPort OUT_INT = new OutputPort(INT_OUT, PortType.INT);
    private static final OutputPort OUT_VECTOR = new OutputPort(VECTOR_2, PortType.VECTOR3);

    /** {@inheritDoc}. */
    @Override
    public String description() {
        return "Generates a deterministic random value from a seed. Modes FLOAT, INT, VECTOR select the output type within a min/max range.";
    }

    /** {@inheritDoc}. */
    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                SEED_2, "Deterministic PRNG seed. Same seed = same output.",
                MIN_2, "Range minimum (per-component for VECTOR).",
                MAX_2, "Range maximum.",
                MODE_2, "Output selector: FLOAT, INT, or VECTOR. Only the matching *_out port is active; the others are null.",
                FLOAT_OUT, "Active when mode=FLOAT; null otherwise.",
                INT_OUT, "Active when mode=INT; null otherwise.",
                VECTOR_2, "Active when mode=VECTOR; null otherwise."
        );
    }

    /** {@inheritDoc}. */
    @Override
    public List<InputPort> inputs() {
        return List.of(SEED, MIN, MAX, MODE);
    }

    /** {@inheritDoc}. */
    @Override
    public List<OutputPort> outputs() {
        return List.of(OUT_FLOAT, OUT_INT, OUT_VECTOR);
    }

    /** {@inheritDoc}. */
    @Override
    public void evaluate(NodeContext ctx) {
        Number seedNum = ctx.getInput(SEED_2, Number.class);
        Number minNum = ctx.getInput(MIN_2, Number.class);
        Number maxNum = ctx.getInput(MAX_2, Number.class);
        String modeStr = ctx.getInput(MODE_2, String.class);
        long seed = seedNum == null ? 0L : seedNum.longValue();
        float min = minNum == null ? 0f : minNum.floatValue();
        float max = maxNum == null ? 1f : maxNum.floatValue();
        Mode mode = Mode.parse(modeStr);

        Random rnd = new Random(seed);

        float lo = Math.min(min, max);
        float hi = Math.max(min, max);

        ctx.setOutput(FLOAT_OUT, null);
        ctx.setOutput(INT_OUT, null);
        ctx.setOutput(VECTOR_2, null);

        switch (mode) {
            case FLOAT -> {
                float f = lo + rnd.nextFloat() * (hi - lo);
                ctx.setOutput(FLOAT_OUT, f);
            }
            case INT -> {
                int ilo = (int) Math.floor(lo);
                int ihi = (int) Math.floor(hi);
                if (ihi < ilo) {
                    int t = ilo;
                    ilo = ihi;
                    ihi = t;
                }
                int span = ihi - ilo + 1;
                int k = span > 0 ? ilo + rnd.nextInt(span) : ilo;
                ctx.setOutput(INT_OUT, k);
            }
            case VECTOR -> {
                float x = lo + rnd.nextFloat() * (hi - lo);
                float y = lo + rnd.nextFloat() * (hi - lo);
                float z = lo + rnd.nextFloat() * (hi - lo);
                ctx.setOutput(VECTOR_2, new Vector3Value(x, y, z));
            }
        }
    }

    public enum Mode {
        FLOAT,
        INT,
        VECTOR;

        /**
         * Parses the {@code mode} port string via the mode constraint
         * (case-insensitive, falls back to FLOAT on null/unknown input).
         *
         * @param raw raw {@code mode} string from the node context
         * @return matching {@link Mode}
         */
        public static Mode parse(String raw) {
            return Mode.valueOf(MODE_CONSTRAINT.normalize(raw));
        }
    }
}
