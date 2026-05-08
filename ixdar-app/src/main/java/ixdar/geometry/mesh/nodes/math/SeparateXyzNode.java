package ixdar.geometry.mesh.nodes.math;

import java.util.List;

import ixdar.annotations.meshnode.FloatField;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vec3Field;
import ixdar.annotations.meshnode.Vector3Value;

@MeshNodeAnnotation(id = "separate_xyz")
public class SeparateXyzNode implements MeshNode {
    public static final String VECTOR_2 = "vector";
    public static final String X_2 = "x";
    public static final String Y_2 = "y";
    public static final String Z_2 = "z";

    private static final InputPort VECTOR = new InputPort(VECTOR_2, PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    private static final OutputPort X = new OutputPort(X_2, PortType.FLOAT);
    private static final OutputPort Y = new OutputPort(Y_2, PortType.FLOAT);
    private static final OutputPort Z = new OutputPort(Z_2, PortType.FLOAT);

    @Override
    public String description() {
        return "Splits a vector into its X, Y, Z float components.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                VECTOR_2, "Input Vector3 or Vec3Field.",
                X_2, "X component (per-element).",
                Y_2, "Y component.",
                Z_2, "Z component."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(VECTOR);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(X, Y, Z);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Object vo = FieldBroadcast.getInputOrDefault(ctx, VECTOR_2, VECTOR.defaultValue());
        if (vo instanceof Vec3Field v) {
            int n = v.length();
            float[] x = new float[n];
            float[] y = new float[n];
            float[] z = new float[n];
            for (int i = 0; i < n; i++) {
                x[i] = v.getX(i);
                y[i] = v.getY(i);
                z[i] = v.getZ(i);
            }
            ctx.setOutput(X_2, new FloatField(x));
            ctx.setOutput(Y_2, new FloatField(y));
            ctx.setOutput(Z_2, new FloatField(z));
            return;
        }
        Vector3Value vec = vo instanceof Vector3Value vv ? vv : new Vector3Value(0f, 0f, 0f);
        ctx.setOutput(X_2, vec.x());
        ctx.setOutput(Y_2, vec.y());
        ctx.setOutput(Z_2, vec.z());
    }
}
