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

    private static final InputPort CURVE = new InputPort("curve", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort CLOSURE = new InputPort("closure", PortType.CLOSURE, null);
    private static final InputPort SOURCE_AXIS = new InputPort("source_axis", PortType.STRING, "Y",
            new ModeConstraint("Y", List.of("X", "Y", "Z"), Map.of()));
    private static final InputPort TARGET_AXIS = new InputPort("target_axis", PortType.STRING, "Z",
            new ModeConstraint("Z", List.of("X", "Y", "Z"), Map.of()));
    private static final InputPort FROM_MIN = new InputPort("from_min", PortType.FLOAT, 0f);
    private static final InputPort FROM_MAX = new InputPort("from_max", PortType.FLOAT, 1f);
    private static final InputPort AMPLITUDE = new InputPort("amplitude", PortType.FLOAT, 1f);

    private static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

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
        GeometryBundle base = GeometryBundles.requireBundle(ctx.getInput("curve", Object.class));
        Object closureObj = ctx.getInput("closure", Object.class);

        if (!(closureObj instanceof FloatCurveKernel kernel)) {
            ctx.setOutput("geometry", base);
            return;
        }

        Object curveObj = base.slots().get("_curve");
        if (!(curveObj instanceof CurveGeometry cg) || cg.pointCount() == 0) {
            ctx.setOutput("geometry", base);
            return;
        }

        int srcIdx = axisIndex(FieldBroadcast.getInputOrDefault(ctx, "source_axis", SOURCE_AXIS.defaultValue()));
        int tgtIdx = axisIndex(FieldBroadcast.getInputOrDefault(ctx, "target_axis", TARGET_AXIS.defaultValue()));

        float fromMin = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, "from_min", FROM_MIN.defaultValue()), 0, 0f);
        float fromMax = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, "from_max", FROM_MAX.defaultValue()), 0, 1f);
        float amplitude = FieldBroadcast.floatAt(
                FieldBroadcast.getInputOrDefault(ctx, "amplitude", AMPLITUDE.defaultValue()), 0, 1f);

        float range = fromMax - fromMin;
        if (Math.abs(range) < 1e-10f) range = 1f;

        float[] srcPos = cg.positions();
        float[] dstPos = srcPos.clone();
        int nPts = srcPos.length / 3;

        for (int i = 0; i < nPts; i++) {
            int base3 = i * 3;
            float src = dstPos[base3 + srcIdx];
            float t = (src - fromMin) / range;
            t = Math.max(0f, Math.min(1f, t));
            float offset = kernel.evaluate(t) * amplitude;
            dstPos[base3 + tgtIdx] += offset;
        }

        CurveGeometry deformed = new CurveGeometry(dstPos, cg.curveOffsets());
        ctx.setOutput("geometry", base.withSlot("_curve", deformed));
    }

    private static int axisIndex(Object axisObj) {
        if (axisObj instanceof String s) {
            return switch (s.toUpperCase()) {
                case "X" -> 0;
                case "Z" -> 2;
                default -> 1; // Y
            };
        }
        return 1;
    }
}
