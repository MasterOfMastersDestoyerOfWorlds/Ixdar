package ixdar.geometry.mesh.nodes.selection;

import java.util.List;

import org.joml.Vector3f;

import java.util.Map;

import ixdar.annotations.meshnode.BoolField;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Value;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Per-face selection: true when the face normal dotted with {@code direction} exceeds
 * {@code threshold}. {@code direction} is normalized before the test, and {@code threshold}
 * defaults to 0.7, roughly 45 degrees of alignment.
 */
@MeshNodeAnnotation(id = "select_by_normal")
public class SelectByNormalNode implements MeshNode {
    public static final float NUM_0 = 0f;
    public static final float NUM_1 = 1f;
    public static final float NUM_0_7 = 0.7f;
    public static final float NUM_1e_8 = 1e-8f;
    public static final int NUM_3 = 3;

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort DIRECTION = new InputPort("direction", PortType.VECTOR3, new Vector3Value(0f, 1f, 0f));
    public static final InputPort THRESHOLD = new InputPort("threshold", PortType.FLOAT, 0.7f, -1f, 1f);
    public static final OutputPort SELECTION = new OutputPort("selection", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, DIRECTION, THRESHOLD);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(SELECTION);
    }

    @Override
    public String description() {
        return "Produces a per-face boolean selection: true for faces whose normal dotted with a direction exceeds threshold (default 0.7).";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Geometry bundle to test.",
                DIRECTION.name, "Reference direction (need not be unit; normalized internally).",
                THRESHOLD.name, "Minimum dot product. 1 = exact alignment; 0.7 ≈ within 45°; 0 ≈ same hemisphere; -1 = always true.",
                SELECTION.name, "Per-face BOOLEAN mask."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput(GEOMETRY.name, Object.class));
        Vector3Value dir = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, DIRECTION.name, DIRECTION.defaultValue),
                new Vector3Value(NUM_0, NUM_1, NUM_0));
        float threshold = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, THRESHOLD.name, THRESHOLD.defaultValue), NUM_0_7);

        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.faceCount() == 0) {
            ctx.setOutput(SELECTION.name, new BoolField(new boolean[0]));
            return;
        }

        Vector3f d = new Vector3f(dir.x(), dir.y(), dir.z());
        float dLen = d.length();
        if (dLen < NUM_1e_8) {
            ctx.setOutput(SELECTION.name, new BoolField(new boolean[mesh.faceCount()]));
            return;
        }
        d.mul(NUM_1 / dLen);

        int fc = mesh.faceCount();
        boolean[] sel = new boolean[fc];
        Vector3f p0 = new Vector3f(), p1 = new Vector3f(), p2 = new Vector3f();
        Vector3f e1 = new Vector3f(), e2 = new Vector3f(), n = new Vector3f();
        for (int fi = 0; fi < fc; fi++) {
            int fid = mesh.faceIdAt(fi);
            if (mesh.faceVertexCount(fid) < NUM_3) continue;
            mesh.vertexPosition(mesh.faceVertexAt(fid, 0), p0);
            mesh.vertexPosition(mesh.faceVertexAt(fid, 1), p1);
            mesh.vertexPosition(mesh.faceVertexAt(fid, 2), p2);
            e1.set(p1).sub(p0);
            e2.set(p2).sub(p0);
            e1.cross(e2, n);
            float nLen = n.length();
            if (nLen < NUM_1e_8) continue;
            n.mul(NUM_1 / nLen);
            sel[fi] = n.dot(d) >= threshold;
        }
        ctx.setOutput(SELECTION.name, new BoolField(sel));
    }
}
