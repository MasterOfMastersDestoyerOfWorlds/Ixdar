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
import ixdar.geometry.mesh.curve.FloatCurveKernel;
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Deforms curve points using a float curve closure. For each point:
 * <ol>
 *   <li>Read the source_axis coordinate</li>
 *   <li>Map it from [from_min, from_max] to [0, 1]</li>
 *   <li>Evaluate the closure at that parameter</li>
 *   <li>Multiply by amplitude</li>
 *   <li>Add the result to the target_axis coordinate</li>
 * </ol>
 * This replaces the Blender pipeline of input_position → separate_xyz → map_range →
 * evaluate_closure → combine_xyz → set_position for curve geometry, without needing
 * the field context system.
 */
@MeshNodeAnnotation(id = "curve_deform")
public class CurveDeformNode implements MeshNode {
    public static final String CURVE_2 = "curve";
    public static final String CLOSURE_2 = "closure";
    public static final String SOURCE_AXIS_2 = "source_axis";
    public static final String Y = "Y";
    public static final String X = "X";
    public static final String Z = "Z";
    public static final String TARGET_AXIS_2 = "target_axis";
    public static final String FROM_MIN_2 = "from_min";
    public static final String FROM_MAX_2 = "from_max";
    public static final String AMPLITUDE_2 = "amplitude";
    public static final String GEOMETRY_2 = "geometry";
    public static final String CURVE_3 = "_curve";
    public static final float NUM_0 = 0f;
    public static final float NUM_1 = 1f;
    public static final float NUM_1e_10 = 1e-10f;
    public static final int NUM_3 = 3;

    private static final InputPort CURVE = new InputPort(CURVE_2, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort CLOSURE = new InputPort(CLOSURE_2, PortType.CLOSURE, null);
    private static final InputPort SOURCE_AXIS = new InputPort(SOURCE_AXIS_2, PortType.STRING, Y,
            new ModeConstraint(Y, List.of(X, Y, Z), Map.of()));
    private static final InputPort TARGET_AXIS = new InputPort(TARGET_AXIS_2, PortType.STRING, Z,
            new ModeConstraint(Z, List.of(X, Y, Z), Map.of()));
    private static final InputPort FROM_MIN = new InputPort(FROM_MIN_2, PortType.FLOAT, 0f, -100f, 100f);
    private static final InputPort FROM_MAX = new InputPort(FROM_MAX_2, PortType.FLOAT, 1f, -100f, 100f);
    private static final InputPort AMPLITUDE = new InputPort(AMPLITUDE_2, PortType.FLOAT, 1f, -100f, 100f);

    private static final OutputPort GEOMETRY = new OutputPort(GEOMETRY_2, PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Deforms curve points by mapping a source axis coordinate through a float curve closure and adding the scaled result to a target axis.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                CURVE_2, "Input curve to deform.",
                CLOSURE_2, "Float closure mapping source coordinate to displacement.",
                SOURCE_AXIS_2, "Axis whose coordinate is fed into `closure`: X, Y, or Z.",
                TARGET_AXIS_2, "Axis where the closure's output is added: X, Y, or Z.",
                FROM_MIN_2, "Low end of the source axis range remapped to closure input 0.",
                FROM_MAX_2, "High end of the source axis range remapped to closure input 1.",
                AMPLITUDE_2, "Multiplier on the closure's output before adding to the target axis.",
                GEOMETRY_2, "Deformed curve."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(CURVE, CLOSURE, SOURCE_AXIS, TARGET_AXIS, FROM_MIN, FROM_MAX, AMPLITUDE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput(CURVE_2, Object.class));
        Object closureObj = ctx.getInput(CLOSURE_2, Object.class);

        if (!(closureObj instanceof FloatCurveKernel kernel)) {
            ctx.setOutput(GEOMETRY_2, base);
            return;
        }

        Object curveObj = base.slots().get(CURVE_3);
        if (!(curveObj instanceof CurveGeometry cg) || cg.pointCount() == 0) {
            ctx.setOutput(GEOMETRY_2, base);
            return;
        }

        int srcIdx = axisIndex(FieldBroadcast.getInputOrDefault(ctx, SOURCE_AXIS_2, SOURCE_AXIS.defaultValue()));
        int tgtIdx = axisIndex(FieldBroadcast.getInputOrDefault(ctx, TARGET_AXIS_2, TARGET_AXIS.defaultValue()));

        float fromMin = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, FROM_MIN_2, FROM_MIN.defaultValue()), 0, NUM_0);
        float fromMax = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, FROM_MAX_2, FROM_MAX.defaultValue()), 0, NUM_1);
        float amplitude = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, AMPLITUDE_2, AMPLITUDE.defaultValue()), 0, NUM_1);

        float range = fromMax - fromMin;
        if (Math.abs(range) < NUM_1e_10) range = NUM_1;

        float[] srcPos = cg.positions();
        float[] dstPos = srcPos.clone();
        int nPts = srcPos.length / NUM_3;

        for (int i = 0; i < nPts; i++) {
            int base3 = i * NUM_3;
            float src = dstPos[base3 + srcIdx];
            float t = (src - fromMin) / range;
            t = Math.max(NUM_0, Math.min(NUM_1, t));
            float offset = kernel.evaluate(t) * amplitude;
            dstPos[base3 + tgtIdx] += offset;
        }

        CurveGeometry deformed = new CurveGeometry(dstPos, cg.curveOffsets());
        ctx.setOutput(GEOMETRY_2, base.withSlot(CURVE_3, deformed));
    }

    private static int axisIndex(Object axisObj) {
        if (axisObj instanceof String s) {
            return switch (s.toUpperCase()) {
                case X -> 0;
                case Z -> 2;
                default -> 1; // Y
            };
        }
        return 1;
    }
}
