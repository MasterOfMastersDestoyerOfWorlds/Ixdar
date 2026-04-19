package ixdar.geometry.mesh.nodes.curve;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.ModeConstraint;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Value;
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Creates a Bézier curve segment as a polyline.
 * <p>
 * CUBIC mode: standard cubic Bézier from start, handle_start, handle_end, end (4 control points).
 * QUADRATIC mode: quadratic Bézier from start, handle_start (as single control point), end (3 control points).
 * <p>
 * The resolution parameter controls how many line segments approximate the curve.
 */
@MeshNodeAnnotation(id = "curve_bezier")
public class CurvePrimitiveBezierNode implements MeshNode {

    private static final Vector3Value ZERO = new Vector3Value(0f, 0f, 0f);
    private static final Vector3Value DEFAULT_END = new Vector3Value(1f, 0f, 0f);
    private static final Vector3Value DEFAULT_HANDLE_START = new Vector3Value(0.33f, 0.33f, 0f);
    private static final Vector3Value DEFAULT_HANDLE_END = new Vector3Value(0.66f, 0.33f, 0f);

    private static final InputPort RESOLUTION = new InputPort("resolution", PortType.INT, 16, 2f, 256f);
    private static final InputPort START = new InputPort("start", PortType.VECTOR3, ZERO);
    private static final InputPort HANDLE_START = new InputPort("handle_start", PortType.VECTOR3, DEFAULT_HANDLE_START);
    private static final InputPort HANDLE_END = new InputPort("handle_end", PortType.VECTOR3, DEFAULT_HANDLE_END);
    private static final InputPort END = new InputPort("end", PortType.VECTOR3, DEFAULT_END);
    private static final InputPort MODE = new InputPort("mode", PortType.STRING, "CUBIC",
            new ModeConstraint("CUBIC", List.of("CUBIC", "QUADRATIC"), Map.of()));
    private static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Creates a cubic or quadratic Bezier curve segment as a polyline from control points, with configurable resolution.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "resolution", "Number of polyline segments. Higher = smoother curve.",
                "start", "Curve start point (world-space).",
                "handle_start", "First control handle. For CUBIC, this is the start's outgoing tangent endpoint. For QUADRATIC, the single control point.",
                "handle_end", "Second control handle. For CUBIC, this is the end's incoming tangent endpoint. Ignored for QUADRATIC.",
                "end", "Curve end point.",
                "mode", "CUBIC (4 control points: start, handle_start, handle_end, end) or QUADRATIC (3 control points: start, handle_start, end).",
                "geometry", "Bezier polyline as a geometry bundle."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(RESOLUTION, START, HANDLE_START, HANDLE_END, END, MODE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        int resolution = FieldBroadcast.intAt(
                FieldBroadcast.getInputOrDefault(ctx, "resolution", RESOLUTION.defaultValue()), 0, 16);
        resolution = Math.max(1, Math.min(1000, resolution));

        Vector3Value p0 = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, "start", START.defaultValue()), ZERO);
        Vector3Value p1 = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, "handle_start", HANDLE_START.defaultValue()), DEFAULT_HANDLE_START);
        Vector3Value p2 = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, "handle_end", HANDLE_END.defaultValue()), DEFAULT_HANDLE_END);
        Vector3Value p3 = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, "end", END.defaultValue()), DEFAULT_END);

        Object modeObj = FieldBroadcast.getInputOrDefault(ctx, "mode", MODE.defaultValue());
        String mode = modeObj instanceof String s ? s : "CUBIC";
        boolean quadratic = "QUADRATIC".equalsIgnoreCase(mode);

        int numPoints = resolution + 1;
        float[] positions = new float[numPoints * 3];

        for (int i = 0; i <= resolution; i++) {
            float t = (float) i / resolution;
            float x, y, z;

            if (quadratic) {
                // Quadratic Bézier: P = (1-t)²·P0 + 2(1-t)t·P1 + t²·P3
                float u = 1f - t;
                float uu = u * u;
                float tt = t * t;
                float ut2 = 2f * u * t;
                x = uu * p0.x() + ut2 * p1.x() + tt * p3.x();
                y = uu * p0.y() + ut2 * p1.y() + tt * p3.y();
                z = uu * p0.z() + ut2 * p1.z() + tt * p3.z();
            } else {
                // Cubic Bézier: P = (1-t)³·P0 + 3(1-t)²t·P1 + 3(1-t)t²·P2 + t³·P3
                float u = 1f - t;
                float uu = u * u;
                float uuu = uu * u;
                float tt = t * t;
                float ttt = tt * t;
                x = uuu * p0.x() + 3f * uu * t * p1.x() + 3f * u * tt * p2.x() + ttt * p3.x();
                y = uuu * p0.y() + 3f * uu * t * p1.y() + 3f * u * tt * p2.y() + ttt * p3.y();
                z = uuu * p0.z() + 3f * uu * t * p1.z() + 3f * u * tt * p2.z() + ttt * p3.z();
            }

            int b = i * 3;
            positions[b] = x;
            positions[b + 1] = y;
            positions[b + 2] = z;
        }

        CurveGeometry curve = CurveGeometry.singlePolyline(positions);
        ctx.setOutput("geometry", GeometryBundle.empty().withSlot("_curve", curve));
    }
}
