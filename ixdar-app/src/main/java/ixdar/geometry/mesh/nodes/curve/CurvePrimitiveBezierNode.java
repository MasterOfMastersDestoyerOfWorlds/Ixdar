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
    public static final String RESOLUTION_2 = "resolution";
    public static final String START_2 = "start";
    public static final String HANDLE_START_2 = "handle_start";
    public static final String HANDLE_END_2 = "handle_end";
    public static final String END_2 = "end";
    public static final String MODE_2 = "mode";
    public static final String CUBIC = "CUBIC";
    public static final String QUADRATIC = "QUADRATIC";
    public static final String GEOMETRY_2 = "geometry";
    public static final int NUM_16 = 16;
    public static final int NUM_1000 = 1000;
    public static final int NUM_3 = 3;
    public static final float NUM_1 = 1f;
    public static final float NUM_2 = 2f;
    public static final float NUM_3_2 = 3f;

    private static final Vector3Value ZERO = new Vector3Value(0f, 0f, 0f);
    private static final Vector3Value DEFAULT_END = new Vector3Value(1f, 0f, 0f);
    private static final Vector3Value DEFAULT_HANDLE_START = new Vector3Value(0.33f, 0.33f, 0f);
    private static final Vector3Value DEFAULT_HANDLE_END = new Vector3Value(0.66f, 0.33f, 0f);

    private static final InputPort RESOLUTION = new InputPort(RESOLUTION_2, PortType.INT, 16, 2f, 256f);
    private static final InputPort START = new InputPort(START_2, PortType.VECTOR3, ZERO);
    private static final InputPort HANDLE_START = new InputPort(HANDLE_START_2, PortType.VECTOR3, DEFAULT_HANDLE_START);
    private static final InputPort HANDLE_END = new InputPort(HANDLE_END_2, PortType.VECTOR3, DEFAULT_HANDLE_END);
    private static final InputPort END = new InputPort(END_2, PortType.VECTOR3, DEFAULT_END);
    private static final InputPort MODE = new InputPort(MODE_2, PortType.STRING, CUBIC,
            new ModeConstraint(CUBIC, List.of(CUBIC, QUADRATIC), Map.of()));
    private static final OutputPort GEOMETRY = new OutputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Creates a cubic or quadratic Bezier curve segment as a polyline from control points, with configurable resolution.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                RESOLUTION_2, "Number of polyline segments. Higher = smoother curve.",
                START_2, "Curve start point (world-space).",
                HANDLE_START_2, "First control handle. For CUBIC, this is the start's outgoing tangent endpoint. For QUADRATIC, the single control point.",
                HANDLE_END_2, "Second control handle. For CUBIC, this is the end's incoming tangent endpoint. Ignored for QUADRATIC.",
                END_2, "Curve end point.",
                MODE_2, "CUBIC (4 control points: start, handle_start, handle_end, end) or QUADRATIC (3 control points: start, handle_start, end).",
                GEOMETRY_2, "Bezier polyline as a geometry bundle."
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
                FieldBroadcast.getInputOrDefault(ctx, RESOLUTION_2, RESOLUTION.defaultValue()), 0, NUM_16);
        resolution = Math.max(1, Math.min(NUM_1000, resolution));

        Vector3Value p0 = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, START_2, START.defaultValue()), ZERO);
        Vector3Value p1 = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, HANDLE_START_2, HANDLE_START.defaultValue()), DEFAULT_HANDLE_START);
        Vector3Value p2 = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, HANDLE_END_2, HANDLE_END.defaultValue()), DEFAULT_HANDLE_END);
        Vector3Value p3 = FieldBroadcast.vector3ValueOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, END_2, END.defaultValue()), DEFAULT_END);

        Object modeObj = FieldBroadcast.getInputOrDefault(ctx, MODE_2, MODE.defaultValue());
        String mode = modeObj instanceof String s ? s : CUBIC;
        boolean quadratic = QUADRATIC.equalsIgnoreCase(mode);

        int numPoints = resolution + 1;
        float[] positions = new float[numPoints * NUM_3];

        for (int i = 0; i <= resolution; i++) {
            float t = (float) i / resolution;
            float x, y, z;

            if (quadratic) {
                // Quadratic Bézier: P = (1-t)²·P0 + 2(1-t)t·P1 + t²·P3
                float u = NUM_1 - t;
                float uu = u * u;
                float tt = t * t;
                float ut2 = NUM_2 * u * t;
                x = uu * p0.x() + ut2 * p1.x() + tt * p3.x();
                y = uu * p0.y() + ut2 * p1.y() + tt * p3.y();
                z = uu * p0.z() + ut2 * p1.z() + tt * p3.z();
            } else {
                // Cubic Bézier: P = (1-t)³·P0 + 3(1-t)²t·P1 + 3(1-t)t²·P2 + t³·P3
                float u = NUM_1 - t;
                float uu = u * u;
                float uuu = uu * u;
                float tt = t * t;
                float ttt = tt * t;
                x = uuu * p0.x() + NUM_3_2 * uu * t * p1.x() + NUM_3_2 * u * tt * p2.x() + ttt * p3.x();
                y = uuu * p0.y() + NUM_3_2 * uu * t * p1.y() + NUM_3_2 * u * tt * p2.y() + ttt * p3.y();
                z = uuu * p0.z() + NUM_3_2 * uu * t * p1.z() + NUM_3_2 * u * tt * p2.z() + ttt * p3.z();
            }

            int b = i * NUM_3;
            positions[b] = x;
            positions[b + 1] = y;
            positions[b + 2] = z;
        }

        CurveGeometry curve = CurveGeometry.singlePolyline(positions);
        ctx.setOutput(GEOMETRY_2, GeometryBundle.empty().withSlot("_curve", curve));
    }
}
