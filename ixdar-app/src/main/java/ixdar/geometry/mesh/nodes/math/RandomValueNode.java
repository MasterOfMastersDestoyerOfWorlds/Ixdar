package ixdar.geometry.mesh.nodes.math;

import java.util.List;
import java.util.Locale;
import java.util.Random;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Value;

@MeshNodeAnnotation(id = "random_value")
public class RandomValueNode implements MeshNode {
    private static final InputPort SEED = new InputPort("seed", PortType.INT, 0);
    private static final InputPort MIN = new InputPort("min", PortType.FLOAT, 0.0f);
    private static final InputPort MAX = new InputPort("max", PortType.FLOAT, 1.0f);
    private static final InputPort MODE = new InputPort("mode", PortType.STRING, "FLOAT");
    private static final OutputPort OUT_FLOAT = new OutputPort("float_out", PortType.FLOAT);
    private static final OutputPort OUT_INT = new OutputPort("int_out", PortType.INT);
    private static final OutputPort OUT_VECTOR = new OutputPort("vector_out", PortType.VECTOR3);

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
        String modeIn = ctx.getInput("mode", String.class);
        long seed = seedNum == null ? 0L : seedNum.longValue();
        float min = minNum == null ? 0f : minNum.floatValue();
        float max = maxNum == null ? 1f : maxNum.floatValue();
        String mode = modeIn == null ? "FLOAT" : modeIn.trim().toUpperCase(Locale.ROOT);

        Random rnd = new Random(seed);

        float lo = Math.min(min, max);
        float hi = Math.max(min, max);

        ctx.setOutput("float_out", null);
        ctx.setOutput("int_out", null);
        ctx.setOutput("vector_out", null);

        switch (mode) {
            case "FLOAT" -> {
                float f = lo + rnd.nextFloat() * (hi - lo);
                ctx.setOutput("float_out", f);
            }
            case "INT" -> {
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
            case "VECTOR" -> {
                float x = lo + rnd.nextFloat() * (hi - lo);
                float y = lo + rnd.nextFloat() * (hi - lo);
                float z = lo + rnd.nextFloat() * (hi - lo);
                ctx.setOutput("vector_out", new Vector3Value(x, y, z));
            }
            default -> throw new IllegalArgumentException("random_value: unknown mode '" + modeIn + "'");
        }
    }
}
