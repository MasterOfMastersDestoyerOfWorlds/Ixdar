package ixdar.geometry.mesh.nodes.math;

import java.util.List;

import ixdar.annotations.meshnode.FloatField;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Field;
import ixdar.annotations.meshnode.Vector3Value;

@MeshNodeAnnotation(id = "combine_xyz")
public class CombineXyzNode implements MeshNode {
    public static final String X_2 = "x";
    public static final String Y_2 = "y";
    public static final String Z_2 = "z";
    public static final String VECTOR_2 = "vector";
    public static final int NUM_3 = 3;
    public static final float NUM_0 = 0f;

    private static final InputPort X = new InputPort(X_2, PortType.FLOAT, 0.0f, -1000f, 1000f);
    private static final InputPort Y = new InputPort(Y_2, PortType.FLOAT, 0.0f, -1000f, 1000f);
    private static final InputPort Z = new InputPort(Z_2, PortType.FLOAT, 0.0f, -1000f, 1000f);
    private static final OutputPort VECTOR = new OutputPort(VECTOR_2, PortType.VECTOR3);

    @Override
    public String description() {
        return "Combines X, Y, Z float values (scalars or per-vertex fields) into a single vector or Vector3field.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                X_2, "X component (scalar float or per-vertex FloatField).",
                Y_2, "Y component.",
                Z_2, "Z component.",
                VECTOR_2, "Combined Vector3 or Vector3field (if any input is a field)."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(X, Y, Z);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(VECTOR);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Object xo = FieldBroadcast.getInputOrDefault(ctx, X_2, X.defaultValue());
        Object yo = FieldBroadcast.getInputOrDefault(ctx, Y_2, Y.defaultValue());
        Object zo = FieldBroadcast.getInputOrDefault(ctx, Z_2, Z.defaultValue());

        if (xo instanceof FloatField || yo instanceof FloatField || zo instanceof FloatField) {
            int n = FieldBroadcast.floatFieldLength3(xo, yo, zo);
            float[] d = new float[n * NUM_3];
            for (int i = 0; i < n; i++) {
                d[NUM_3 * i] = FieldBroadcast.floatAt(xo, i, NUM_0);
                d[NUM_3 * i + 1] = FieldBroadcast.floatAt(yo, i, NUM_0);
                d[NUM_3 * i + 2] = FieldBroadcast.floatAt(zo, i, NUM_0);
            }
            ctx.setOutput(VECTOR_2, new Vector3Field(d));
            return;
        }

        float x = FieldBroadcast.floatScalarOrDefault(xo, NUM_0);
        float y = FieldBroadcast.floatScalarOrDefault(yo, NUM_0);
        float z = FieldBroadcast.floatScalarOrDefault(zo, NUM_0);
        ctx.setOutput(VECTOR_2, new Vector3Value(x, y, z));
    }
}
