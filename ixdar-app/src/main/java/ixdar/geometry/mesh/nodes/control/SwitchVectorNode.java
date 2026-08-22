package ixdar.geometry.mesh.nodes.control;

import java.util.List;

import ixdar.annotations.meshnode.BoolField;

import java.util.Map;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Field;
import ixdar.annotations.meshnode.Vector3Value;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

import org.joml.Vector3f;

@MeshNodeAnnotation(id = "switch_vector")
public class SwitchVectorNode implements MeshNode {
    public static final int NUM_3 = 3;

    public static final Vector3Value ZERO = new Vector3Value(0f, 0f, 0f);

    public static final InputPort SWITCH = new InputPort("switch", PortType.BOOLEAN, false);
    public static final InputPort FALSE_VAL = new InputPort("false", PortType.VECTOR3, ZERO);
    public static final InputPort TRUE_VAL = new InputPort("true", PortType.VECTOR3, ZERO);
    public static final OutputPort VECTOR = new OutputPort("vector", PortType.VECTOR3);

    @Override
    public List<InputPort> inputs() {
        return List.of(SWITCH, FALSE_VAL, TRUE_VAL);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(VECTOR);
    }

    @Override
    public String description() {
        return "Outputs one of two vector values based on a boolean switch. Supports per-element field evaluation.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                SWITCH.name, "Per-element BOOLEAN selector.",
                FALSE_VAL.name, "Vector used where switch is false.",
                TRUE_VAL.name, "Vector used where switch is true.",
                VECTOR.name, "Per-element Vector3: switch ? true : false."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Object so = FieldBroadcast.getInputOrDefault(ctx, SWITCH.name, SWITCH.defaultValue);
        Object fa = FieldBroadcast.getInputOrDefault(ctx, FALSE_VAL.name, FALSE_VAL.defaultValue);
        Object tr = FieldBroadcast.getInputOrDefault(ctx, TRUE_VAL.name, TRUE_VAL.defaultValue);

        if (so instanceof BoolField || fa instanceof Vector3Field || tr instanceof Vector3Field) {
            int n = 0;
            if (so instanceof BoolField bf) {
                n = Math.max(n, bf.length());
            }
            if (fa instanceof Vector3Field vf) {
                n = Math.max(n, vf.length());
            }
            if (tr instanceof Vector3Field vt) {
                n = Math.max(n, vt.length());
            }
            float[] out = new float[n * NUM_3];
            Vector3f a = new Vector3f();
            Vector3f b = new Vector3f();
            for (int i = 0; i < n; i++) {
                boolean on = FieldBroadcast.boolAt(so, i, false);
                FieldBroadcast.vec3At(fa, i, ZERO, a);
                FieldBroadcast.vec3At(tr, i, ZERO, b);
                Vector3f pick = on ? b : a;
                out[NUM_3 * i] = pick.x;
                out[NUM_3 * i + 1] = pick.y;
                out[NUM_3 * i + 2] = pick.z;
            }
            ctx.setOutput(VECTOR.name,new Vector3Field(out));
            return;
        }

        Vector3Value fvv = FieldBroadcast.vector3ValueOrDefault(fa, ZERO);
        Vector3Value tvv = FieldBroadcast.vector3ValueOrDefault(tr, ZERO);
        boolean on = so instanceof Boolean bb && bb;
        ctx.setOutput(VECTOR.name,on ? tvv : fvv);
    }
}
