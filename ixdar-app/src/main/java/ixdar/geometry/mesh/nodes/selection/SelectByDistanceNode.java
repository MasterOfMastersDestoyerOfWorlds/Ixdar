package ixdar.geometry.mesh.nodes.selection;

import java.util.List;

import org.joml.Vector3f;

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
 * Per-face selection: true when the face centroid lies within {@code radius}
 * of {@code point}. Replaces the 20-line axis-range + boolean_math boilerplate
 * an agent otherwise writes for every cage feature.
 * <p>
 * Compose with {@code boolean_math} or a modifier node's {@code generated}
 * output to refine the selection further.
 */
@MeshNodeAnnotation(id = "select_by_distance")
public class SelectByDistanceNode implements MeshNode {

    private static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort POINT = new InputPort("point", PortType.VECTOR3, new Vector3Value(0f, 0f, 0f));
    private static final InputPort RADIUS = new InputPort("radius", PortType.FLOAT, 0.1f, 0f, 100f);
    private static final OutputPort SELECTION = new OutputPort("selection", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, POINT, RADIUS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(SELECTION);
    }

    @Override
    public String description() {
        return "Produces a per-face boolean selection: true for faces whose centroid lies within radius of point.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "geometry", "Geometry bundle to test. Face centroids are computed from the current vertex positions.",
                "point", "World-space center of the test sphere.",
                "radius", "Selection radius. Faces with centroid distance ≤ radius from `point` are selected. Tune with care — too large overlaps adjacent features after topology modifications.",
                "selection", "Per-face BOOLEAN mask. Feed into inset_faces / extrude_mesh selection."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("geometry", Object.class));
        Vector3Value pt = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, "point", POINT.defaultValue()),
                new Vector3Value(0f, 0f, 0f));
        float radius = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, "radius", RADIUS.defaultValue()), 0.1f);

        MeshTopology mesh = base.mesh();
        if (mesh == null || mesh.faceCount() == 0) {
            ctx.setOutput("selection", new BoolField(new boolean[0]));
            return;
        }

        int fc = mesh.faceCount();
        boolean[] sel = new boolean[fc];
        float r2 = radius * radius;
        Vector3f vp = new Vector3f();
        Vector3f centroid = new Vector3f();
        for (int fi = 0; fi < fc; fi++) {
            int fid = mesh.faceIdAt(fi);
            int n = mesh.faceVertexCount(fid);
            if (n == 0) continue;
            centroid.set(0f, 0f, 0f);
            for (int k = 0; k < n; k++) {
                mesh.vertexPosition(mesh.faceVertexAt(fid, k), vp);
                centroid.add(vp);
            }
            centroid.mul(1f / n);
            float dx = centroid.x - pt.x();
            float dy = centroid.y - pt.y();
            float dz = centroid.z - pt.z();
            sel[fi] = (dx * dx + dy * dy + dz * dz) <= r2;
        }
        ctx.setOutput("selection", new BoolField(sel));
    }
}
