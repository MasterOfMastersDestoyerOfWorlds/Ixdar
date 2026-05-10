package ixdar.geometry.mesh.nodes.modifier;

import java.util.List;

import org.joml.Matrix4f;

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
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.ops.MeshAppend;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "instance_on_points")
public class InstanceOnPointsNode implements MeshNode {
    public static final String POINTS_2 = "points";
    public static final String INSTANCE_2 = "instance";
    public static final String ROTATION_2 = "rotation";
    public static final String GEOMETRY_2 = "geometry";
    public static final String INSTANCE_MESH = "instance_mesh";
    public static final int NUM_3 = 3;
    public static final float NUM_0 = 0f;
    public static final float NUM_1 = 1f;

    private static final InputPort POINTS = new InputPort(POINTS_2, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort INSTANCE = new InputPort(INSTANCE_2, PortType.MESH, null);
    private static final InputPort ROTATION = new InputPort(ROTATION_2, PortType.ROTATION, new RotationValue(0f, 0f, 0f, 1f));
    private static final OutputPort GEOMETRY = new OutputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(POINTS, INSTANCE, ROTATION);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public String description() {
        return "Places a copy of an instance mesh at each point in a geometry bundle or curve, with optional per-point rotation for scatter or array patterns.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                POINTS_2, "Geometry bundle or curve whose vertex positions act as placement locations for the instances.",
                INSTANCE_2, "Source mesh to be copied at each point.",
                ROTATION_2, "Per-point Euler rotation (radians) applied to each instance. Accepts a single Vector3 or a rotation field.",
                GEOMETRY_2, "Output bundle containing all instances. Call realize_instances to flatten to a single mesh."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle pts = GeometryBundles.bundlePart(ctx.getInput(POINTS_2, Object.class));
        MeshTopology inst = ctx.getInput(INSTANCE_2, MeshTopology.class);
        Object rotObj = FieldBroadcast.getInputOrDefault(ctx, ROTATION_2, ROTATION.defaultValue());
        if (pts == null || inst == null || inst.vertexCount() == 0) {
            ctx.setOutput(GEOMETRY_2, GeometryBundle.empty());
            return;
        }

        float[] positions = positionsFromBundle(pts);
        if (positions.length < NUM_3) {
            ctx.setOutput(GEOMETRY_2, pts.withSlot(INSTANCE_MESH, inst));
            return;
        }

        int n = positions.length / NUM_3;
        HalfEdgeMesh out = new HalfEdgeMesh();
        Matrix4f mat = new Matrix4f();
        Quaternionf q = new Quaternionf();
        for (int i = 0; i < n; i++) {
            float x = positions[NUM_3 * i];
            float y = positions[NUM_3 * i + 1];
            float z = positions[NUM_3 * i + 2];
            RotationValue rv = rotationAt(rotObj, i);
            q.set(rv.x(), rv.y(), rv.z(), rv.w());
            mat.identity();
            mat.translation(x, y, z);
            mat.rotate(q);
            MeshAppend.append(out, inst, mat);
        }
        out.computeNormals();
        ctx.setOutput(GEOMETRY_2, pts.withMesh(out).withSlot(INSTANCE_MESH, inst));
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
        float[] p = new float[m.vertexCount() * NUM_3];
        Vector3f tmp = new Vector3f();
        for (int i = 0; i < m.vertexCount(); i++) {
            m.vertexPosition(m.vertexIdAt(i), tmp);
            p[NUM_3 * i] = tmp.x;
            p[NUM_3 * i + 1] = tmp.y;
            p[NUM_3 * i + 2] = tmp.z;
        }
        return p;
    }

    private static RotationValue rotationAt(Object rot, int i) {
        if (rot instanceof RotationField rf) {
            if (i < rf.length()) {
                return rf.rotationAt(i);
            }
            return new RotationValue(NUM_0, NUM_0, NUM_0, NUM_1);
        }
        if (rot instanceof RotationValue rv) {
            return rv;
        }
        return new RotationValue(NUM_0, NUM_0, NUM_0, NUM_1);
    }
}
