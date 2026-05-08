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
    public static final String OPERATION_2 = "operation";
    public static final String ADD = "ADD";
    public static final String A_2 = "a";
    public static final String B_2 = "b";
    public static final String SCALE_2 = "scale";
    public static final String VECTOR_2 = "vector";
    public static final String RESULT_2 = "result";
    public static final String DOT_PRODUCT = "DOT_PRODUCT";
    public static final String DOT = "DOT";
    public static final String SUBTRACT = "SUBTRACT";
    public static final String MULTIPLY = "MULTIPLY";
    public static final String SCALE_3 = "SCALE";
    public static final String NORMALIZE = "NORMALIZE";
    public static final String CROSS_PRODUCT = "CROSS_PRODUCT";
    public static final String CROSS = "CROSS";
    public static final int NUM_3 = 3;
    public static final float NUM_1 = 1f;
    public static final float NUM_1e_20 = 1e-20f;
    public static final float NUM_0 = 0f;

    private static final Vector3Value ZERO = new Vector3Value(0f, 0f, 0f);

    private static final InputPort OPERATION = new InputPort(OPERATION_2, PortType.STRING, ADD);
    private static final InputPort A = new InputPort(A_2, PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    private static final InputPort B = new InputPort(B_2, PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    private static final InputPort SCALE = new InputPort(SCALE_2, PortType.FLOAT, 1.0f, -1000f, 1000f);
    private static final OutputPort VECTOR = new OutputPort(VECTOR_2, PortType.VECTOR3);
    private static final OutputPort RESULT = new OutputPort(RESULT_2, PortType.FLOAT);

    @Override
    public String description() {
        return "Per-element vector math with operations ADD, SUBTRACT, MULTIPLY, SCALE, NORMALIZE, CROSS, DOT. Outputs both the result vector and its length.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                OPERATION_2, "Op: ADD, SUBTRACT, MULTIPLY, SCALE (by `scale`), NORMALIZE (of a), CROSS, DOT (scalar result).",
                A_2, "Left vector operand.",
                B_2, "Right vector operand. Ignored for NORMALIZE and SCALE.",
                SCALE_2, "Scalar multiplier for SCALE mode. Ignored otherwise.",
                VECTOR_2, "Vector result (for vector-valued ops). For DOT mode, this is (0, 0, 0).",
                RESULT_2, "Scalar result: length of `vector` for vector-valued ops, dot product for DOT."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(OPERATION, A, B, SCALE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(VECTOR, RESULT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        String op = ctx.getInput(OPERATION_2, String.class);
        if (op == null) {
            op = ADD;
        } else {
            op = op.trim().toUpperCase();
        }
        Object av = FieldBroadcast.getInputOrDefault(ctx, A_2, A.defaultValue());
        Object bv = FieldBroadcast.getInputOrDefault(ctx, B_2, B.defaultValue());
        Object sv = FieldBroadcast.getInputOrDefault(ctx, SCALE_2, SCALE.defaultValue());

        int n = FieldBroadcast.vec3Length(av, bv);
        if (sv instanceof FloatField sf) {
            n = Math.max(n, sf.length());
        }
        boolean fieldVec = av instanceof Vec3Field || bv instanceof Vec3Field;

        if (fieldVec || n > 0) {
            Vector3f a = new Vector3f();
            Vector3f b = new Vector3f();
            Vector3f outVec = new Vector3f();

            if (DOT_PRODUCT.equals(op) || DOT.equals(op)) {
                float[] dots = new float[n];
                for (int i = 0; i < n; i++) {
                    FieldBroadcast.vec3At(av, i, (Vector3Value) A.defaultValue(), a);
                    FieldBroadcast.vec3At(bv, i, (Vector3Value) B.defaultValue(), b);
                    dots[i] = a.dot(b);
                }
                ctx.setOutput(VECTOR_2, ZERO);
                ctx.setOutput(RESULT_2,new FloatField(dots));
                return;
            }

            float[] out = new float[n * NUM_3];
            float[] lengths = new float[n];
            for (int i = 0; i < n; i++) {
                FieldBroadcast.vec3At(av, i, (Vector3Value) A.defaultValue(), a);
                FieldBroadcast.vec3At(bv, i, (Vector3Value) B.defaultValue(), b);
                float s = FieldBroadcast.floatAt(sv, i, NUM_1);

                switch (op) {
                    case ADD -> outVec.set(a).add(b);
                    case SUBTRACT -> outVec.set(a).sub(b);
                    case MULTIPLY -> outVec.set(a.x * b.x, a.y * b.y, a.z * b.z);
                    case SCALE_3 -> outVec.set(a).mul(s);
                    case NORMALIZE -> {
                        if (a.lengthSquared() > NUM_1e_20) {
                            outVec.set(a).normalize();
                        } else {
                            outVec.set(NUM_0, NUM_1, NUM_0);
                        }
                    }
                    case CROSS_PRODUCT, CROSS -> outVec.set(a).cross(b);
                    default -> outVec.set(a).add(b);
                }
                out[NUM_3 * i] = outVec.x;
                out[NUM_3 * i + 1] = outVec.y;
                out[NUM_3 * i + 2] = outVec.z;
                lengths[i] = outVec.length();
            }
            ctx.setOutput(VECTOR_2, new Vec3Field(out));
            ctx.setOutput(RESULT_2,new FloatField(lengths));
            return;
        }

        Vector3f a = toVec(FieldBroadcast.vector3ValueOrDefault(av, (Vector3Value) A.defaultValue()));
        Vector3f b = toVec(FieldBroadcast.vector3ValueOrDefault(bv, (Vector3Value) B.defaultValue()));
        float s = FieldBroadcast.floatScalarOrDefault(sv, NUM_1);

        Vector3f outVec = new Vector3f();

        if (DOT_PRODUCT.equals(op) || DOT.equals(op)) {
            float dot = a.dot(b);
            ctx.setOutput(VECTOR_2, ZERO);
            ctx.setOutput(RESULT_2,dot);
            return;
        }

        switch (op) {
            case ADD -> outVec.set(a).add(b);
            case SUBTRACT -> outVec.set(a).sub(b);
            case MULTIPLY -> outVec.set(a.x * b.x, a.y * b.y, a.z * b.z);
            case SCALE_3 -> outVec.set(a).mul(s);
            case NORMALIZE -> {
                if (a.lengthSquared() > NUM_1e_20) {
                    outVec.set(a).normalize();
                } else {
                    outVec.set(NUM_0, NUM_1, NUM_0);
                }
            }
            case CROSS_PRODUCT, CROSS -> outVec.set(a).cross(b);
            default -> outVec.set(a).add(b);
        }

        ctx.setOutput(VECTOR_2, new Vector3Value(outVec.x, outVec.y, outVec.z));
        ctx.setOutput(RESULT_2,outVec.length());
    }

    private static Vector3f toVec(Vector3Value v) {
        if (v == null) {
            return new Vector3f();
        }
        return new Vector3f(v.x(), v.y(), v.z());
    }
}
