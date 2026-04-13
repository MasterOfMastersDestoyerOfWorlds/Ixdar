package ixdar.geometry.mesh.nodes.math;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.FloatField;
import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.ModeConstraint;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;

/**
 * Remaps a float value from one range to another.
 * <p>
 * Modes:
 * <ul>
 *   <li>LINEAR — standard linear interpolation remap</li>
 *   <li>SMOOTH_STEP — applies smoothstep (3t²-2t³) before remapping</li>
 * </ul>
 * Works with both scalar values and per-vertex FloatFields.
 */
@MeshNodeAnnotation(id = "map_range")
public class MapRangeNode implements MeshNode {

    private static final InputPort VALUE = new InputPort("value", PortType.FLOAT, 0.5f, -1000f, 1000f);
    private static final InputPort FROM_MIN = new InputPort("from_min", PortType.FLOAT, 0.0f, -1000f, 1000f);
    private static final InputPort FROM_MAX = new InputPort("from_max", PortType.FLOAT, 1.0f, -1000f, 1000f);
    private static final InputPort TO_MIN = new InputPort("to_min", PortType.FLOAT, 0.0f, -1000f, 1000f);
    private static final InputPort TO_MAX = new InputPort("to_max", PortType.FLOAT, 1.0f, -1000f, 1000f);
    private static final InputPort CLAMP = new InputPort("clamp", PortType.BOOLEAN, true);
    private static final InputPort MODE = new InputPort("mode", PortType.STRING, "LINEAR",
            new ModeConstraint("LINEAR", List.of("LINEAR", "SMOOTH_STEP"), Map.of()));
    private static final OutputPort RESULT = new OutputPort("result", PortType.FLOAT);

    @Override
    public List<InputPort> inputs() {
        return List.of(VALUE, FROM_MIN, FROM_MAX, TO_MIN, TO_MAX, CLAMP, MODE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(RESULT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Object valObj = FieldBroadcast.getInputOrDefault(ctx, "value", VALUE.defaultValue());
        Object fMinObj = FieldBroadcast.getInputOrDefault(ctx, "from_min", FROM_MIN.defaultValue());
        Object fMaxObj = FieldBroadcast.getInputOrDefault(ctx, "from_max", FROM_MAX.defaultValue());
        Object tMinObj = FieldBroadcast.getInputOrDefault(ctx, "to_min", TO_MIN.defaultValue());
        Object tMaxObj = FieldBroadcast.getInputOrDefault(ctx, "to_max", TO_MAX.defaultValue());

        boolean clamp = FieldBroadcast.boolAt(
                FieldBroadcast.getInputOrDefault(ctx, "clamp", CLAMP.defaultValue()), 0, true);

        Object modeObj = FieldBroadcast.getInputOrDefault(ctx, "mode", MODE.defaultValue());
        String mode = modeObj instanceof String s ? s : "LINEAR";
        boolean smooth = "SMOOTH_STEP".equalsIgnoreCase(mode);

        // Determine if any input is a field (per-vertex)
        int len = FieldBroadcast.floatFieldLength(valObj, fMinObj);
        len = Math.max(len, FieldBroadcast.floatFieldLength(fMaxObj, tMinObj));
        len = Math.max(len, FieldBroadcast.floatFieldLength(tMaxObj, valObj));

        if (len <= 1) {
            // Scalar path
            float value = FieldBroadcast.floatScalarOrDefault(valObj, 0.5f);
            float fromMin = FieldBroadcast.floatScalarOrDefault(fMinObj, 0.0f);
            float fromMax = FieldBroadcast.floatScalarOrDefault(fMaxObj, 1.0f);
            float toMin = FieldBroadcast.floatScalarOrDefault(tMinObj, 0.0f);
            float toMax = FieldBroadcast.floatScalarOrDefault(tMaxObj, 1.0f);
            ctx.setOutput("result", mapRange(value, fromMin, fromMax, toMin, toMax, clamp, smooth));
        } else {
            // Field path
            float[] data = new float[len];
            for (int i = 0; i < len; i++) {
                float value = FieldBroadcast.floatAt(valObj, i, 0.5f);
                float fromMin = FieldBroadcast.floatAt(fMinObj, i, 0.0f);
                float fromMax = FieldBroadcast.floatAt(fMaxObj, i, 1.0f);
                float toMin = FieldBroadcast.floatAt(tMinObj, i, 0.0f);
                float toMax = FieldBroadcast.floatAt(tMaxObj, i, 1.0f);
                data[i] = mapRange(value, fromMin, fromMax, toMin, toMax, clamp, smooth);
            }
            ctx.setOutput("result", new FloatField(data));
        }
    }

    private static float mapRange(float value, float fromMin, float fromMax,
                                   float toMin, float toMax, boolean clamp, boolean smooth) {
        float range = fromMax - fromMin;
        float t = (range == 0f) ? 0f : (value - fromMin) / range;

        if (smooth) {
            t = Math.max(0f, Math.min(1f, t));
            t = t * t * (3f - 2f * t); // smoothstep
        }

        float result = toMin + t * (toMax - toMin);

        if (clamp) {
            float lo = Math.min(toMin, toMax);
            float hi = Math.max(toMin, toMax);
            result = Math.max(lo, Math.min(hi, result));
        }

        return result;
    }
}
