package ixdar.geometry.mesh.nodes.math;

import java.util.List;

import ixdar.annotations.meshnode.FloatField;

import java.util.Map;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Field;
import ixdar.annotations.meshnode.Vector3Value;

@MeshNodeAnnotation(id = "separate_xyz")
public class SeparateXyzNode implements MeshNode {
    public static final InputPort VECTOR = new InputPort("vector", PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    public static final OutputPort X = new OutputPort("x", PortType.FLOAT);
    public static final OutputPort Y = new OutputPort("y", PortType.FLOAT);
    public static final OutputPort Z = new OutputPort("z", PortType.FLOAT);

    @Override
    public String description() {
        return "Splits a vector into its X, Y, Z float components.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                VECTOR.name, "Input Vector3 or Vector3field.",
                X.name, "X component (per-element).",
                Y.name, "Y component.",
                Z.name, "Z component."
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
        Object vo = FieldBroadcast.getInputOrDefault(ctx, VECTOR.name, VECTOR.defaultValue);
        if (vo instanceof Vector3Field v) {
            int n = v.length();
            float[] x = new float[n];
            float[] y = new float[n];
            float[] z = new float[n];
            for (int i = 0; i < n; i++) {
                x[i] = v.getX(i);
                y[i] = v.getY(i);
                z[i] = v.getZ(i);
            }
            ctx.setOutput(X.name, new FloatField(x));
            ctx.setOutput(Y.name, new FloatField(y));
            ctx.setOutput(Z.name, new FloatField(z));
            return;
        }
        Vector3Value vec = vo instanceof Vector3Value vv ? vv : new Vector3Value(0f, 0f, 0f);
        ctx.setOutput(X.name, vec.x());
        ctx.setOutput(Y.name, vec.y());
        ctx.setOutput(Z.name, vec.z());
    }
}
