package ixdar.geometry.mesh.nodes.math;

import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.nodes.api.FloatField;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.ModeConstraint;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;

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
    public static final String LINEAR = "LINEAR";
    public static final String SMOOTH_STEP = "SMOOTH_STEP";
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_0 = 0f;
    public static final float NUM_1 = 1f;
    public static final float NUM_3 = 3f;
    public static final float NUM_2 = 2f;

    public static final InputPort VALUE = new InputPort("value", PortType.FLOAT, 0.5f, -1000f, 1000f);
    public static final InputPort FROM_MIN = new InputPort("from_min", PortType.FLOAT, 0.0f, -1000f, 1000f);
    public static final InputPort FROM_MAX = new InputPort("from_max", PortType.FLOAT, 1.0f, -1000f, 1000f);
    public static final InputPort TO_MIN = new InputPort("to_min", PortType.FLOAT, 0.0f, -1000f, 1000f);
    public static final InputPort TO_MAX = new InputPort("to_max", PortType.FLOAT, 1.0f, -1000f, 1000f);
    public static final InputPort CLAMP = new InputPort("clamp", PortType.BOOLEAN, true);
    public static final InputPort MODE = new InputPort("mode", PortType.STRING, LINEAR,
            new ModeConstraint(LINEAR, List.of(LINEAR, SMOOTH_STEP), Map.of()));
    public static final OutputPort RESULT = new OutputPort("result", PortType.FLOAT);

    @Override
    public String description() {
        return "Remaps a float value from one range to another using LINEAR or SMOOTH_STEP interpolation, with optional clamping.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                VALUE.name, "Input value to remap.",
                FROM_MIN.name, "Input range low end.",
                FROM_MAX.name, "Input range high end.",
                TO_MIN.name, "Output range low end.",
                TO_MAX.name, "Output range high end.",
                CLAMP.name, "If true (default), clamp output to [to_min, to_max]; if false, extrapolate beyond.",
                MODE.name, "Interpolation curve: LINEAR or SMOOTH_STEP.",
                RESULT.name, "Remapped float."
        );
    }

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
        Object valObj = FieldBroadcast.getInputOrDefault(ctx, VALUE.name, VALUE.defaultValue);
        Object fMinObj = FieldBroadcast.getInputOrDefault(ctx, FROM_MIN.name, FROM_MIN.defaultValue);
        Object fMaxObj = FieldBroadcast.getInputOrDefault(ctx, FROM_MAX.name, FROM_MAX.defaultValue);
        Object tMinObj = FieldBroadcast.getInputOrDefault(ctx, TO_MIN.name, TO_MIN.defaultValue);
        Object tMaxObj = FieldBroadcast.getInputOrDefault(ctx, TO_MAX.name, TO_MAX.defaultValue);

        boolean clamp = FieldBroadcast.boolAt(
                FieldBroadcast.getInputOrDefault(ctx, CLAMP.name, CLAMP.defaultValue), 0, true);

        Object modeObj = FieldBroadcast.getInputOrDefault(ctx, MODE.name, MODE.defaultValue);
        String mode = modeObj instanceof String s ? s : LINEAR;
        boolean smooth = SMOOTH_STEP.equalsIgnoreCase(mode);

        // Determine if any input is a field (per-vertex)
        int len = FieldBroadcast.floatFieldLength(valObj, fMinObj);
        len = Math.max(len, FieldBroadcast.floatFieldLength(fMaxObj, tMinObj));
        len = Math.max(len, FieldBroadcast.floatFieldLength(tMaxObj, valObj));

        if (len <= 1) {
            // Scalar path
            float value = FieldBroadcast.floatScalarOrDefault(valObj, NUM_0_5);
            float fromMin = FieldBroadcast.floatScalarOrDefault(fMinObj, 0.0f);
            float fromMax = FieldBroadcast.floatScalarOrDefault(fMaxObj, 1.0f);
            float toMin = FieldBroadcast.floatScalarOrDefault(tMinObj, 0.0f);
            float toMax = FieldBroadcast.floatScalarOrDefault(tMaxObj, 1.0f);
            ctx.setOutput(RESULT.name, mapRange(value, fromMin, fromMax, toMin, toMax, clamp, smooth));
        } else {
            // Field path
            float[] data = new float[len];
            for (int i = 0; i < len; i++) {
                float value = FieldBroadcast.floatAt(valObj, i, NUM_0_5);
                float fromMin = FieldBroadcast.floatAt(fMinObj, i, 0.0f);
                float fromMax = FieldBroadcast.floatAt(fMaxObj, i, 1.0f);
                float toMin = FieldBroadcast.floatAt(tMinObj, i, 0.0f);
                float toMax = FieldBroadcast.floatAt(tMaxObj, i, 1.0f);
                data[i] = mapRange(value, fromMin, fromMax, toMin, toMax, clamp, smooth);
            }
            ctx.setOutput(RESULT.name, new FloatField(data));
        }
    }

    private static float mapRange(float value, float fromMin, float fromMax,
                                   float toMin, float toMax, boolean clamp, boolean smooth) {
        float range = fromMax - fromMin;
        float t = (range == NUM_0) ? NUM_0 : (value - fromMin) / range;

        if (smooth) {
            t = Math.max(NUM_0, Math.min(NUM_1, t));
            t = t * t * (NUM_3 - NUM_2 * t); // smoothstep
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
