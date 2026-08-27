package ixdar.geometry.mesh.nodes.math;

import java.util.List;
import java.util.Map;
import java.util.Random;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.ModeConstraint;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.api.Vector3Value;

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
    public static final ModeConstraint MODE_CONSTRAINT = new ModeConstraint(
            FLOAT,
            List.of(FLOAT, INT, VECTOR),
            Map.of());

    public static final InputPort SEED = new InputPort("seed", PortType.INT, 0, 0f, 1000000f);
    public static final InputPort MIN = new InputPort("min", PortType.FLOAT, 0.0f, -1000f, 1000f);
    public static final InputPort MAX = new InputPort("max", PortType.FLOAT, 1.0f, -1000f, 1000f);
    public static final InputPort MODE = new InputPort("mode", PortType.STRING, FLOAT, MODE_CONSTRAINT);
    public static final OutputPort OUT_FLOAT = new OutputPort("float_out", PortType.FLOAT);
    public static final OutputPort OUT_INT = new OutputPort("int_out", PortType.INT);
    public static final OutputPort OUT_VECTOR = new OutputPort("vector", PortType.VECTOR3);

    public static final Map<String, List<String>> OUTPUT_ACTIVATION_BY_MODE = Map.of(
            FLOAT, List.of(OUT_FLOAT.name),
            INT, List.of(OUT_INT.name),
            VECTOR, List.of(OUT_VECTOR.name));

    /** {@inheritDoc}. */
    @Override
    public String description() {
        return "Generates a deterministic random value from a seed. Modes FLOAT, INT, VECTOR select the output type within a min/max range.";
    }

    /** {@inheritDoc}. */
    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                SEED.name, "Deterministic PRNG seed. Same seed = same output.",
                MIN.name, "Range minimum (per-component for VECTOR).",
                MAX.name, "Range maximum.",
                MODE.name, "Output selector: FLOAT, INT, or VECTOR. Only the matching *_out port is active; the others are null.",
                OUT_FLOAT.name, "Active when mode=FLOAT; null otherwise.",
                OUT_INT.name, "Active when mode=INT; null otherwise.",
                OUT_VECTOR.name, "Active when mode=VECTOR; null otherwise."
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
        Number seedNum = ctx.getInput(SEED.name, Number.class);
        Number minNum = ctx.getInput(MIN.name, Number.class);
        Number maxNum = ctx.getInput(MAX.name, Number.class);
        String modeStr = ctx.getInput(MODE.name, String.class);
        long seed = seedNum == null ? 0L : seedNum.longValue();
        float min = minNum == null ? 0f : minNum.floatValue();
        float max = maxNum == null ? 1f : maxNum.floatValue();
        Mode mode = Mode.parse(modeStr);

        Random rnd = new Random(seed);

        float lo = Math.min(min, max);
        float hi = Math.max(min, max);

        ctx.setOutput(OUT_FLOAT.name, null);
        ctx.setOutput(OUT_INT.name, null);
        ctx.setOutput(OUT_VECTOR.name, null);

        switch (mode) {
            case FLOAT -> {
                float f = lo + rnd.nextFloat() * (hi - lo);
                ctx.setOutput(OUT_FLOAT.name, f);
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
                ctx.setOutput(OUT_INT.name, k);
            }
            case VECTOR -> {
                float x = lo + rnd.nextFloat() * (hi - lo);
                float y = lo + rnd.nextFloat() * (hi - lo);
                float z = lo + rnd.nextFloat() * (hi - lo);
                ctx.setOutput(OUT_VECTOR.name, new Vector3Value(x, y, z));
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
