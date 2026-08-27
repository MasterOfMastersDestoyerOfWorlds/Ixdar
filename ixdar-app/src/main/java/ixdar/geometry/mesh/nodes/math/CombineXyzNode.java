package ixdar.geometry.mesh.nodes.math;

import java.util.List;

import ixdar.geometry.mesh.nodes.api.FloatField;

import java.util.Map;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.api.Vector3Field;
import ixdar.geometry.mesh.nodes.api.Vector3Value;

@MeshNodeAnnotation(id = "combine_xyz")
public class CombineXyzNode implements MeshNode {
    public static final int NUM_3 = 3;
    public static final float NUM_0 = 0f;

    public static final InputPort X = new InputPort("x", PortType.FLOAT, 0.0f, -1000f, 1000f);
    public static final InputPort Y = new InputPort("y", PortType.FLOAT, 0.0f, -1000f, 1000f);
    public static final InputPort Z = new InputPort("z", PortType.FLOAT, 0.0f, -1000f, 1000f);
    public static final OutputPort VECTOR = new OutputPort("vector", PortType.VECTOR3);

    @Override
    public String description() {
        return "Combines X, Y, Z float values (scalars or per-vertex fields) into a single vector or Vector3field.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                X.name, "X component (scalar float or per-vertex FloatField).",
                Y.name, "Y component.",
                Z.name, "Z component.",
                VECTOR.name, "Combined Vector3 or Vector3field (if any input is a field)."
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
        Object xo = FieldBroadcast.getInputOrDefault(ctx, X.name, X.defaultValue);
        Object yo = FieldBroadcast.getInputOrDefault(ctx, Y.name, Y.defaultValue);
        Object zo = FieldBroadcast.getInputOrDefault(ctx, Z.name, Z.defaultValue);

        if (xo instanceof FloatField || yo instanceof FloatField || zo instanceof FloatField) {
            int n = FieldBroadcast.floatFieldLength3(xo, yo, zo);
            float[] d = new float[n * NUM_3];
            for (int i = 0; i < n; i++) {
                d[NUM_3 * i] = FieldBroadcast.floatAt(xo, i, NUM_0);
                d[NUM_3 * i + 1] = FieldBroadcast.floatAt(yo, i, NUM_0);
                d[NUM_3 * i + 2] = FieldBroadcast.floatAt(zo, i, NUM_0);
            }
            ctx.setOutput(VECTOR.name, new Vector3Field(d));
            return;
        }

        float x = FieldBroadcast.floatScalarOrDefault(xo, NUM_0);
        float y = FieldBroadcast.floatScalarOrDefault(yo, NUM_0);
        float z = FieldBroadcast.floatScalarOrDefault(zo, NUM_0);
        ctx.setOutput(VECTOR.name, new Vector3Value(x, y, z));
    }
}
