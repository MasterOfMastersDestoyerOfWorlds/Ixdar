package ixdar.geometry.mesh.nodes.transform;

import java.util.List;

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

    private static final Vector3f UP = new Vector3f(0f, 1f, 0f);

    private static final InputPort VECTOR = new InputPort("vector", PortType.VECTOR3, new Vector3Value(0f, 1f, 0f));
    private static final OutputPort ROTATION = new OutputPort("rotation", PortType.ROTATION);

    @Override
    public List<InputPort> inputs() {
        return List.of(VECTOR);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(ROTATION);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Object vo = FieldBroadcast.getInputOrDefault(ctx, "vector", VECTOR.defaultValue());
        if (vo instanceof Vec3Field vf) {
            int n = vf.length();
            float[] d = new float[n * 4];
            Vector3f dir = new Vector3f();
            Quaternionf q = new Quaternionf();
            for (int i = 0; i < n; i++) {
                dir.set(vf.getX(i), vf.getY(i), vf.getZ(i));
                if (dir.lengthSquared() < 1e-20f) {
                    dir.set(0f, 1f, 0f);
                } else {
                    dir.normalize();
                }
                q.rotationTo(UP, dir);
                d[4 * i] = q.x;
                d[4 * i + 1] = q.y;
                d[4 * i + 2] = q.z;
                d[4 * i + 3] = q.w;
            }
            ctx.setOutput("rotation", new RotationField(d));
            return;
        }
        Vector3Value vv = FieldBroadcast.vector3ValueOrDefault(vo, new Vector3Value(0f, 1f, 0f));
        Vector3f dir = new Vector3f(vv.x(), vv.y(), vv.z());
        if (dir.lengthSquared() < 1e-20f) {
            dir.set(0f, 1f, 0f);
        } else {
            dir.normalize();
        }
        Quaternionf q = new Quaternionf();
        q.rotationTo(UP, dir);
        ctx.setOutput("rotation", new RotationValue(q.x, q.y, q.z, q.w));
    }
}
