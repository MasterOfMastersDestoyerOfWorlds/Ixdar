package ixdar.geometry.mesh.nodes.transform;

import java.util.List;
import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.RotationField;
import ixdar.annotations.meshnode.RotationValue;
import ixdar.annotations.meshnode.Vec3Field;
import ixdar.annotations.meshnode.Vector3Value;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "align_rotation_to_vector")
public class AlignRotationToVectorNode implements MeshNode {
    public static final String VECTOR_2 = "vector";
    public static final String ROTATION_2 = "rotation";
    public static final int NUM_4 = 4;
    public static final float NUM_1e_20 = 1e-20f;
    public static final float NUM_0 = 0f;
    public static final float NUM_1 = 1f;
    public static final int NUM_3 = 3;

    private static final Vector3f UP = new Vector3f(0f, 1f, 0f);

    private static final InputPort VECTOR = new InputPort(VECTOR_2, PortType.VECTOR3, new Vector3Value(0f, 1f, 0f));
    private static final OutputPort ROTATION = new OutputPort(ROTATION_2, PortType.ROTATION);

    @Override
    public List<InputPort> inputs() {
        return List.of(VECTOR);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(ROTATION);
    }

    @Override
    public String description() {
        return "Computes a rotation quaternion that aligns the Y-up axis to the given direction vector.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                VECTOR_2, "Target direction. The resulting rotation maps +Y (<0,1,0>) onto this vector. Zero vectors are treated as +Y (identity rotation).",
                ROTATION_2, "Quaternion that rotates +Y onto the input vector."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Object vo = FieldBroadcast.getInputOrDefault(ctx, VECTOR_2, VECTOR.defaultValue());
        if (vo instanceof Vec3Field vf) {
            int n = vf.length();
            float[] d = new float[n * NUM_4];
            Vector3f dir = new Vector3f();
            Quaternionf q = new Quaternionf();
            for (int i = 0; i < n; i++) {
                dir.set(vf.getX(i), vf.getY(i), vf.getZ(i));
                if (dir.lengthSquared() < NUM_1e_20) {
                    dir.set(NUM_0, NUM_1, NUM_0);
                } else {
                    dir.normalize();
                }
                q.rotationTo(UP, dir);
                d[NUM_4 * i] = q.x;
                d[NUM_4 * i + 1] = q.y;
                d[NUM_4 * i + 2] = q.z;
                d[NUM_4 * i + NUM_3] = q.w;
            }
            ctx.setOutput(ROTATION_2, new RotationField(d));
            return;
        }
        Vector3Value vv = FieldBroadcast.vector3ValueOrDefault(vo, new Vector3Value(NUM_0, NUM_1, NUM_0));
        Vector3f dir = new Vector3f(vv.x(), vv.y(), vv.z());
        if (dir.lengthSquared() < NUM_1e_20) {
            dir.set(NUM_0, NUM_1, NUM_0);
        } else {
            dir.normalize();
        }
        Quaternionf q = new Quaternionf();
        q.rotationTo(UP, dir);
        ctx.setOutput(ROTATION_2, new RotationValue(q.x, q.y, q.z, q.w));
    }
}
