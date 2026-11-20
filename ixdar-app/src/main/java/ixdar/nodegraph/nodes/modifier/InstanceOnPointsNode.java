package ixdar.nodegraph.nodes.modifier;

import java.util.List;

import org.joml.Matrix4f;

import java.util.Map;
import org.joml.Quaternionf;

import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.RotationField;
import ixdar.nodegraph.field.RotationValue;
import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.bundle.GeometryBundles;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.ops.MeshAppend;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.nodegraph.field.FieldBroadcast;

@MeshNodeAnnotation(id = "instance_on_points", category = NodeCategory.MODIFIERS)
public class InstanceOnPointsNode implements MeshNode {
    public static final String INSTANCE_MESH = "_instance_mesh";
    public static final InputPort POINTS = new InputPort("points", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort INSTANCE = new InputPort("instance", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort ROTATION = new InputPort("rotation", PortType.ROTATION, new RotationValue(0f, 0f, 0f, 1f));
    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

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
                POINTS.name, "Geometry bundle or curve whose vertex positions act as placement locations for the instances.",
                INSTANCE.name, "Source mesh to be copied at each point.",
                ROTATION.name, "Per-point Euler rotation (radians) applied to each instance. Accepts a single Vector3 or a rotation field.",
                GEOMETRY.name, "Output bundle containing all instances. Call realize_instances to flatten to a single mesh."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle pts = ctx.getInput(POINTS.name, GeometryBundle.class);
        MeshTopology inst = GeometryBundles.meshPart(ctx.getInput(INSTANCE.name, GeometryBundle.class));
        Object rotObj = FieldBroadcast.getInputOrDefault(ctx, ROTATION.name, ROTATION.defaultValue);
        if (pts == null || inst == null || inst.vertexCount() == 0) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }

        Object curve = pts.slots().get(CurveGeometry.SLOT);
        MeshTopology pointMesh = pts.mesh();
        PackedPoints points = curve instanceof CurveGeometry curveGeometry ? curveGeometry.positions
                : pointMesh == null ? new PackedPoints() : pointMesh.packedPositions(null);
        if (points.size() == 0) {
            ctx.setOutput(GEOMETRY.name, pts.withSlot(INSTANCE_MESH, inst));
            return;
        }

        HalfEdgeMesh out = new HalfEdgeMesh();
        Matrix4f mat = new Matrix4f();
        Quaternionf q = new Quaternionf();
        for (int i = 0; i < points.size(); i++) {
            RotationValue rv = rotationAt(rotObj, i);
            q.set(rv.x(), rv.y(), rv.z(), rv.w());
            mat.identity();
            mat.translation(points.at(i));
            mat.rotate(q);
            MeshAppend.append(out, inst, mat);
        }
        out.computeNormals();
        ctx.setOutput(GEOMETRY.name, pts.withMesh(out).withSlot(INSTANCE_MESH, inst));
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
