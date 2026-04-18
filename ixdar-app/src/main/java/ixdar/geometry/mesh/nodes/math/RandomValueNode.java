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

@MeshNodeAnnotation(id = "random_value")
public class RandomValueNode implements MeshNode {

    public static final ModeConstraint MODE_CONSTRAINT = new ModeConstraint(
            "FLOAT",
            List.of("FLOAT", "INT", "VECTOR"),
            Map.of());

    public enum Mode {
        FLOAT,
        INT,
        VECTOR;

        public static Mode parse(String raw) {
            return Mode.valueOf(MODE_CONSTRAINT.normalize(raw));
        }
    }

    public static final Map<String, List<String>> OUTPUT_ACTIVATION_BY_MODE = Map.of(
            "FLOAT", List.of("float_out"),
            "INT", List.of("int_out"),
            "VECTOR", List.of("vector_out"));

    private static final InputPort SEED = new InputPort("seed", PortType.INT, 0, 0f, 1000000f);
    private static final InputPort MIN = new InputPort("min", PortType.FLOAT, 0.0f, -1000f, 1000f);
    private static final InputPort MAX = new InputPort("max", PortType.FLOAT, 1.0f, -1000f, 1000f);
    private static final InputPort MODE = new InputPort("mode", PortType.STRING, "FLOAT", MODE_CONSTRAINT);
    private static final OutputPort OUT_FLOAT = new OutputPort("float_out", PortType.FLOAT);
    private static final OutputPort OUT_INT = new OutputPort("int_out", PortType.INT);
    private static final OutputPort OUT_VECTOR = new OutputPort("vector_out", PortType.VECTOR3);

    @Override
    public String description() {
        return "Generates a deterministic random value from a seed. Modes FLOAT, INT, VECTOR select the output type within a min/max range.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "seed", "Deterministic PRNG seed. Same seed = same output.",
                "min", "Range minimum (per-component for VECTOR).",
                "max", "Range maximum.",
                "mode", "Output selector: FLOAT, INT, or VECTOR. Only the matching *_out port is active; the others are null.",
                "float_out", "Active when mode=FLOAT; null otherwise.",
                "int_out", "Active when mode=INT; null otherwise.",
                "vector_out", "Active when mode=VECTOR; null otherwise."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(SEED, MIN, MAX, MODE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(OUT_FLOAT, OUT_INT, OUT_VECTOR);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number seedNum = ctx.getInput("seed", Number.class);
        Number minNum = ctx.getInput("min", Number.class);
        Number maxNum = ctx.getInput("max", Number.class);
        String modeStr = ctx.getInput("mode", String.class);
        long seed = seedNum == null ? 0L : seedNum.longValue();
        float min = minNum == null ? 0f : minNum.floatValue();
        float max = maxNum == null ? 1f : maxNum.floatValue();
        Mode mode = Mode.parse(modeStr);

        Random rnd = new Random(seed);

        float lo = Math.min(min, max);
        float hi = Math.max(min, max);

        ctx.setOutput("float_out", null);
        ctx.setOutput("int_out", null);
        ctx.setOutput("vector_out", null);

        switch (mode) {
            case FLOAT -> {
                float f = lo + rnd.nextFloat() * (hi - lo);
                ctx.setOutput("float_out", f);
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
                ctx.setOutput("int_out", k);
            }
            case VECTOR -> {
                float x = lo + rnd.nextFloat() * (hi - lo);
                float y = lo + rnd.nextFloat() * (hi - lo);
                float z = lo + rnd.nextFloat() * (hi - lo);
                ctx.setOutput("vector_out", new Vector3Value(x, y, z));
            }
        }
    }
}
