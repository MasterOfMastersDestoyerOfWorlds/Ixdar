package ixdar.geometry.mesh.nodes.transform;

import java.util.List;
import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.api.RotationField;
import ixdar.geometry.mesh.nodes.api.RotationValue;
import ixdar.geometry.mesh.nodes.api.Vector3Field;
import ixdar.geometry.mesh.nodes.api.Vector3Value;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "align_rotation_to_vector")
public class AlignRotationToVectorNode implements MeshNode {
    public static final int QUATERNION_SIZE = 4;
    public static final float EPSILON = 1e-20f;

    public static final Vector3f UP = new Vector3f(0f, 1f, 0f);

    public static final InputPort VECTOR = new InputPort("vector", PortType.VECTOR3, new Vector3Value(0f, 1f, 0f));
    public static final OutputPort ROTATION = new OutputPort("rotation", PortType.ROTATION);

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
                VECTOR.name, "Target direction. The resulting rotation maps +Y (<0,1,0>) onto this vector. Zero vectors are treated as +Y (identity rotation).",
                ROTATION.name, "Quaternion that rotates +Y onto the input vector."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Object vo = FieldBroadcast.getInputOrDefault(ctx, VECTOR.name, VECTOR.defaultValue);
        if (vo instanceof Vector3Field vf) {
            int n = vf.length();
            float[] d = new float[n * QUATERNION_SIZE];
            Vector3f dir = new Vector3f();
            Quaternionf q = new Quaternionf();
            for (int i = 0; i < n; i++) {
                dir.set(vf.getX(i), vf.getY(i), vf.getZ(i));
                if (dir.lengthSquared() < EPSILON) {
                    dir.set(0f, 1f, 0f);
                } else {
                    dir.normalize();
                }
                q.rotationTo(UP, dir);
                d[QUATERNION_SIZE * i] = q.x;
                d[QUATERNION_SIZE * i + 1] = q.y;
                d[QUATERNION_SIZE * i + 2] = q.z;
                d[QUATERNION_SIZE * i + 3] = q.w;
            }
            ctx.setOutput(ROTATION.name, new RotationField(d));
            return;
        }
        Vector3Value vv = FieldBroadcast.vector3ValueOrDefault(vo, new Vector3Value(0f, 1f, 0f));
        Vector3f dir = new Vector3f(vv.x(), vv.y(), vv.z());
        if (dir.lengthSquared() < EPSILON) {
            dir.set(0f, 1f, 0f);
        } else {
            dir.normalize();
        }
        Quaternionf q = new Quaternionf();
        q.rotationTo(UP, dir);
        ctx.setOutput(ROTATION.name, new RotationValue(q.x, q.y, q.z, q.w));
    }
}
