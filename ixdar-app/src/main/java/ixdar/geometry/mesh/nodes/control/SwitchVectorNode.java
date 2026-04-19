package ixdar.geometry.mesh.nodes.control;

import java.util.List;

import ixdar.annotations.meshnode.BoolField;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vec3Field;
import ixdar.annotations.meshnode.Vector3Value;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "switch_vector")
public class SwitchVectorNode implements MeshNode {

    private static final Vector3Value ZERO = new Vector3Value(0f, 0f, 0f);

    private static final InputPort SWITCH = new InputPort("switch", PortType.BOOLEAN, false);
    private static final InputPort FALSE_VAL = new InputPort("false", PortType.VECTOR3, ZERO);
    private static final InputPort TRUE_VAL = new InputPort("true", PortType.VECTOR3, ZERO);
    private static final OutputPort VECTOR = new OutputPort("vector", PortType.VECTOR3);

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
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "switch", "Per-element BOOLEAN selector.",
                "false", "Vector used where switch is false.",
                "true", "Vector used where switch is true.",
                "vector", "Per-element Vector3: switch ? true : false."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Object so = FieldBroadcast.getInputOrDefault(ctx, "switch", SWITCH.defaultValue());
        Object fa = FieldBroadcast.getInputOrDefault(ctx, "false", FALSE_VAL.defaultValue());
        Object tr = FieldBroadcast.getInputOrDefault(ctx, "true", TRUE_VAL.defaultValue());

        if (so instanceof BoolField || fa instanceof Vec3Field || tr instanceof Vec3Field) {
            int n = 0;
            if (so instanceof BoolField bf) {
                n = Math.max(n, bf.length());
            }
            if (fa instanceof Vec3Field vf) {
                n = Math.max(n, vf.length());
            }
            if (tr instanceof Vec3Field vt) {
                n = Math.max(n, vt.length());
            }
            float[] out = new float[n * 3];
            org.joml.Vector3f a = new org.joml.Vector3f();
            org.joml.Vector3f b = new org.joml.Vector3f();
            for (int i = 0; i < n; i++) {
                boolean on = FieldBroadcast.boolAt(so, i, false);
                FieldBroadcast.vec3At(fa, i, ZERO, a);
                FieldBroadcast.vec3At(tr, i, ZERO, b);
                org.joml.Vector3f pick = on ? b : a;
                out[3 * i] = pick.x;
                out[3 * i + 1] = pick.y;
                out[3 * i + 2] = pick.z;
            }
            ctx.setOutput("vector",new Vec3Field(out));
            return;
        }

        Vector3Value fvv = FieldBroadcast.vector3ValueOrDefault(fa, ZERO);
        Vector3Value tvv = FieldBroadcast.vector3ValueOrDefault(tr, ZERO);
        boolean on = so instanceof Boolean bb && bb;
        ctx.setOutput("vector",on ? tvv : fvv);
    }
}
