package ixdar.geometry.mesh.nodes.modifier;

import java.util.List;

import org.joml.Matrix4f;
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
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshAppend;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "instance_on_points")
public class InstanceOnPointsNode implements MeshNode {

    private static final InputPort POINTS = new InputPort("points", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort INSTANCE = new InputPort("instance", PortType.MESH, null);
    private static final InputPort ROTATION = new InputPort("rotation", PortType.ROTATION, new RotationValue(0f, 0f, 0f, 1f));
    private static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(POINTS, INSTANCE, ROTATION);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle pts = GeometryBundles.bundlePart(ctx.getInput("points", Object.class));
        MeshTopology inst = ctx.getInput("instance", MeshTopology.class);
        Object rotObj = FieldBroadcast.getInputOrDefault(ctx, "rotation", ROTATION.defaultValue());
        if (pts == null || inst == null || inst.vertexCount() == 0) {
            ctx.setOutput("geometry", GeometryBundle.empty());
            return;
        }

        float[] positions = positionsFromBundle(pts);
        if (positions.length < 3) {
            ctx.setOutput("geometry", pts.withSlot("instance_mesh", inst));
            return;
        }

        int n = positions.length / 3;
        HalfEdgeMesh out = new HalfEdgeMesh();
        Matrix4f mat = new Matrix4f();
        Quaternionf q = new Quaternionf();
        for (int i = 0; i < n; i++) {
            float x = positions[3 * i];
            float y = positions[3 * i + 1];
            float z = positions[3 * i + 2];
            RotationValue rv = rotationAt(rotObj, i);
            q.set(rv.x(), rv.y(), rv.z(), rv.w());
            mat.identity();
            mat.translation(x, y, z);
            mat.rotate(q);
            MeshAppend.append(out, inst, mat);
        }
        out.computeNormals();
        ctx.setOutput("geometry", pts.withMesh(out).withSlot("instance_mesh", inst));
    }

    private static float[] positionsFromBundle(GeometryBundle gb) {
        Object c = gb.slots().get("_curve");
        if (c instanceof CurveGeometry cg) {
            return cg.positions();
        }
        MeshTopology m = gb.mesh();
        if (m == null || m.vertexCount() == 0) {
            return new float[0];
        }
        float[] p = new float[m.vertexCount() * 3];
        Vector3f tmp = new Vector3f();
        for (int i = 0; i < m.vertexCount(); i++) {
            m.vertexPosition(m.vertexIdAt(i), tmp);
            p[3 * i] = tmp.x;
            p[3 * i + 1] = tmp.y;
            p[3 * i + 2] = tmp.z;
        }
        return p;
    }

    private static RotationValue rotationAt(Object rot, int i) {
        if (rot instanceof RotationField rf) {
            if (i < rf.length()) {
                return rf.rotationAt(i);
            }
            return new RotationValue(0f, 0f, 0f, 1f);
        }
        if (rot instanceof RotationValue rv) {
            return rv;
        }
        return new RotationValue(0f, 0f, 0f, 1f);
    }
}
