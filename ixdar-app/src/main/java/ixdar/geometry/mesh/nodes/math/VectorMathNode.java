package ixdar.geometry.mesh.nodes.math;

import java.util.List;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Value;

/**
 * Blender ShaderNodeVectorMath-style ops (subset).
 */
@MeshNodeAnnotation(id = "vector_math")
public class VectorMathNode implements MeshNode {

    private static final InputPort OPERATION = new InputPort("operation", PortType.STRING, "ADD");
    private static final InputPort A = new InputPort("a", PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    private static final InputPort B = new InputPort("b", PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    private static final InputPort SCALE = new InputPort("scale", PortType.FLOAT, 1.0f);
    private static final OutputPort VECTOR = new OutputPort("vector", PortType.VECTOR3);
    private static final OutputPort VALUE = new OutputPort("value", PortType.FLOAT);

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
        Vector3Value av = ctx.getInput("a", Vector3Value.class);
        Vector3Value bv = ctx.getInput("b", Vector3Value.class);
        Number scaleNum = ctx.getInput("scale", Number.class);
        float s = scaleNum == null ? 1f : scaleNum.floatValue();

        Vector3f a = toVec(av);
        Vector3f b = toVec(bv);

        Vector3f outVec = new Vector3f();

        if ("DOT_PRODUCT".equals(op) || "DOT".equals(op)) {
            float dot = a.dot(b);
            ctx.setOutput("vector", new Vector3Value(0f, 0f, 0f));
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
