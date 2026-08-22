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
 * Deforms curve points with a float curve closure: each point's {@code source_axis} coordinate is
 * remapped from [{@code from_min}, {@code from_max}] to [0, 1], evaluated through the closure,
 * scaled by {@code amplitude}, and added to its {@code target_axis} coordinate.
 */
@MeshNodeAnnotation(id = "curve_deform")
public class CurveDeformNode implements MeshNode {
    public static final String Y = "Y";
    public static final String X = "X";
    public static final String Z = "Z";
    public static final float NUM_0 = 0f;
    public static final float NUM_1 = 1f;
    public static final float NUM_1e_10 = 1e-10f;
    public static final int NUM_3 = 3;

    public static final InputPort CURVE = new InputPort("curve", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort CLOSURE = new InputPort("closure", PortType.CLOSURE, null);
    public static final InputPort SOURCE_AXIS = new InputPort("source_axis", PortType.STRING, Y,
            new ModeConstraint(Y, List.of(X, Y, Z), Map.of()));
    public static final InputPort TARGET_AXIS = new InputPort("target_axis", PortType.STRING, Z,
            new ModeConstraint(Z, List.of(X, Y, Z), Map.of()));
    public static final InputPort FROM_MIN = new InputPort("from_min", PortType.FLOAT, 0f, -100f, 100f);
    public static final InputPort FROM_MAX = new InputPort("from_max", PortType.FLOAT, 1f, -100f, 100f);
    public static final InputPort AMPLITUDE = new InputPort("amplitude", PortType.FLOAT, 1f, -100f, 100f);

    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Deforms curve points by mapping a source axis coordinate through a float curve closure and adding the scaled result to a target axis.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                CURVE.name, "Input curve to deform.",
                CLOSURE.name, "Float closure mapping source coordinate to displacement.",
                SOURCE_AXIS.name, "Axis whose coordinate is fed into `closure`: X, Y, or Z.",
                TARGET_AXIS.name, "Axis where the closure's output is added: X, Y, or Z.",
                FROM_MIN.name, "Low end of the source axis range remapped to closure input 0.",
                FROM_MAX.name, "High end of the source axis range remapped to closure input 1.",
                AMPLITUDE.name, "Multiplier on the closure's output before adding to the target axis.",
                GEOMETRY.name, "Deformed curve."
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
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput(CURVE.name, Object.class));
        Object closureObj = ctx.getInput(CLOSURE.name, Object.class);

        if (!(closureObj instanceof FloatCurveKernel kernel)) {
            ctx.setOutput(GEOMETRY.name, base);
            return;
        }

        Object curveObj = base.slots().get(CurveGeometry.SLOT);
        if (!(curveObj instanceof CurveGeometry cg) || cg.pointCount() == 0) {
            ctx.setOutput(GEOMETRY.name, base);
            return;
        }

        int srcIdx = axisIndex(FieldBroadcast.getInputOrDefault(ctx, SOURCE_AXIS.name, SOURCE_AXIS.defaultValue));
        int tgtIdx = axisIndex(FieldBroadcast.getInputOrDefault(ctx, TARGET_AXIS.name, TARGET_AXIS.defaultValue));

        float fromMin = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, FROM_MIN.name, FROM_MIN.defaultValue), 0, NUM_0);
        float fromMax = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, FROM_MAX.name, FROM_MAX.defaultValue), 0, NUM_1);
        float amplitude = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, AMPLITUDE.name, AMPLITUDE.defaultValue), 0, NUM_1);

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
        ctx.setOutput(GEOMETRY.name, base.withSlot(CurveGeometry.SLOT, deformed));
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
