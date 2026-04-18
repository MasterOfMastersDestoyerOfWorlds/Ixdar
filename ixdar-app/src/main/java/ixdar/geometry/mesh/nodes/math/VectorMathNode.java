package ixdar.geometry.mesh.nodes.math;

import java.util.List;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.FloatField;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vec3Field;
import ixdar.annotations.meshnode.Vector3Value;

/**
 * Element-wise vector math operations (subset).
 */
@MeshNodeAnnotation(id = "vector_math")
public class VectorMathNode implements MeshNode {

    private static final Vector3Value ZERO = new Vector3Value(0f, 0f, 0f);

    private static final InputPort OPERATION = new InputPort("operation", PortType.STRING, "ADD");
    private static final InputPort A = new InputPort("a", PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    private static final InputPort B = new InputPort("b", PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    private static final InputPort SCALE = new InputPort("scale", PortType.FLOAT, 1.0f, -1000f, 1000f);
    private static final OutputPort VECTOR = new OutputPort("vector", PortType.VECTOR3);
    private static final OutputPort VALUE = new OutputPort("value", PortType.FLOAT);

    @Override
    public String description() {
        return "Per-element vector math with operations ADD, SUBTRACT, MULTIPLY, SCALE, NORMALIZE, CROSS, DOT. Outputs both the result vector and its length.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "operation", "Op: ADD, SUBTRACT, MULTIPLY, SCALE (by `scale`), NORMALIZE (of a), CROSS, DOT (scalar result).",
                "a", "Left vector operand.",
                "b", "Right vector operand. Ignored for NORMALIZE and SCALE.",
                "scale", "Scalar multiplier for SCALE mode. Ignored otherwise.",
                "vector", "Vector result (for vector-valued ops). For DOT mode, this is (0, 0, 0).",
                "value", "Scalar result: length of `vector` for vector-valued ops, dot product for DOT."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(OPERATION, A, B, SCALE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(VECTOR, VALUE);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        String op = ctx.getInput("operation", String.class);
        if (op == null) {
            op = "ADD";
        } else {
            op = op.trim().toUpperCase();
        }
        Object av = FieldBroadcast.getInputOrDefault(ctx, "a", A.defaultValue());
        Object bv = FieldBroadcast.getInputOrDefault(ctx, "b", B.defaultValue());
        Object sv = FieldBroadcast.getInputOrDefault(ctx, "scale", SCALE.defaultValue());

        int n = FieldBroadcast.vec3Length(av, bv);
        if (sv instanceof FloatField sf) {
            n = Math.max(n, sf.length());
        }
        boolean fieldVec = av instanceof Vec3Field || bv instanceof Vec3Field;

        if (fieldVec || n > 0) {
            Vector3f a = new Vector3f();
            Vector3f b = new Vector3f();
            Vector3f outVec = new Vector3f();

            if ("DOT_PRODUCT".equals(op) || "DOT".equals(op)) {
                float[] dots = new float[n];
                for (int i = 0; i < n; i++) {
                    FieldBroadcast.vec3At(av, i, (Vector3Value) A.defaultValue(), a);
                    FieldBroadcast.vec3At(bv, i, (Vector3Value) B.defaultValue(), b);
                    dots[i] = a.dot(b);
                }
                ctx.setOutput("vector", ZERO);
                ctx.setOutput("value", new FloatField(dots));
                return;
            }

            float[] out = new float[n * 3];
            float[] lengths = new float[n];
            for (int i = 0; i < n; i++) {
                FieldBroadcast.vec3At(av, i, (Vector3Value) A.defaultValue(), a);
                FieldBroadcast.vec3At(bv, i, (Vector3Value) B.defaultValue(), b);
                float s = FieldBroadcast.floatAt(sv, i, 1f);

                switch (op) {
                    case "ADD" -> outVec.set(a).add(b);
                    case "SUBTRACT" -> outVec.set(a).sub(b);
                    case "MULTIPLY" -> outVec.set(a.x * b.x, a.y * b.y, a.z * b.z);
                    case "SCALE" -> outVec.set(a).mul(s);
                    case "NORMALIZE" -> {
                        if (a.lengthSquared() > 1e-20f) {
                            outVec.set(a).normalize();
                        } else {
                            outVec.set(0f, 1f, 0f);
                        }
                    }
                    case "CROSS_PRODUCT", "CROSS" -> outVec.set(a).cross(b);
                    default -> outVec.set(a).add(b);
                }
                out[3 * i] = outVec.x;
                out[3 * i + 1] = outVec.y;
                out[3 * i + 2] = outVec.z;
                lengths[i] = outVec.length();
            }
            ctx.setOutput("vector", new Vec3Field(out));
            ctx.setOutput("value", new FloatField(lengths));
            return;
        }

        Vector3f a = toVec(FieldBroadcast.vector3ValueOrDefault(av, (Vector3Value) A.defaultValue()));
        Vector3f b = toVec(FieldBroadcast.vector3ValueOrDefault(bv, (Vector3Value) B.defaultValue()));
        float s = FieldBroadcast.floatScalarOrDefault(sv, 1f);

        Vector3f outVec = new Vector3f();

        if ("DOT_PRODUCT".equals(op) || "DOT".equals(op)) {
            float dot = a.dot(b);
            ctx.setOutput("vector", ZERO);
            ctx.setOutput("value", dot);
            return;
        }

        switch (op) {
            case "ADD" -> outVec.set(a).add(b);
            case "SUBTRACT" -> outVec.set(a).sub(b);
            case "MULTIPLY" -> outVec.set(a.x * b.x, a.y * b.y, a.z * b.z);
            case "SCALE" -> outVec.set(a).mul(s);
            case "NORMALIZE" -> {
                if (a.lengthSquared() > 1e-20f) {
                    outVec.set(a).normalize();
                } else {
                    outVec.set(0f, 1f, 0f);
                }
            }
            case "CROSS_PRODUCT", "CROSS" -> outVec.set(a).cross(b);
            default -> outVec.set(a).add(b);
        }

        ctx.setOutput("vector", new Vector3Value(outVec.x, outVec.y, outVec.z));
        ctx.setOutput("value", outVec.length());
    }

    private static Vector3f toVec(Vector3Value v) {
        if (v == null) {
            return new Vector3f();
        }
        return new Vector3f(v.x(), v.y(), v.z());
    }
}
