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

@MeshNodeAnnotation(id = "combine_xyz")
public class CombineXyzNode implements MeshNode {

    private static final InputPort X = new InputPort("x", PortType.FLOAT, 0.0f);
    private static final InputPort Y = new InputPort("y", PortType.FLOAT, 0.0f);
    private static final InputPort Z = new InputPort("z", PortType.FLOAT, 0.0f);
    private static final OutputPort VECTOR = new OutputPort("vector", PortType.VECTOR3);

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
        Object xo = FieldBroadcast.getInputOrDefault(ctx, "x", X.defaultValue());
        Object yo = FieldBroadcast.getInputOrDefault(ctx, "y", Y.defaultValue());
        Object zo = FieldBroadcast.getInputOrDefault(ctx, "z", Z.defaultValue());

        if (xo instanceof FloatField || yo instanceof FloatField || zo instanceof FloatField) {
            int n = FieldBroadcast.floatFieldLength3(xo, yo, zo);
            float[] d = new float[n * 3];
            for (int i = 0; i < n; i++) {
                d[3 * i] = FieldBroadcast.floatAt(xo, i, 0f);
                d[3 * i + 1] = FieldBroadcast.floatAt(yo, i, 0f);
                d[3 * i + 2] = FieldBroadcast.floatAt(zo, i, 0f);
            }
            ctx.setOutput("vector", new Vec3Field(d));
            return;
        }

        float x = FieldBroadcast.floatScalarOrDefault(xo, 0f);
        float y = FieldBroadcast.floatScalarOrDefault(yo, 0f);
        float z = FieldBroadcast.floatScalarOrDefault(zo, 0f);
        ctx.setOutput("vector", new Vector3Value(x, y, z));
    }
}
