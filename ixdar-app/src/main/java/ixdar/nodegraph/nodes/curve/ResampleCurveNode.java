package ixdar.nodegraph.nodes.curve;

import java.util.List;
import java.util.Map;

import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.nodegraph.field.FieldBroadcast;

/**
 * Splits every segment of every curve into {@code ceil(length / spacing)} equal pieces, keeping
 * every input point (corners included), closed flags and attributes. Even arc-length resampling to
 * a point count is {@link CurveGeometry#resampled}, a different rule.
 */
@MeshNodeAnnotation(id = "resample_curve", category = NodeCategory.CURVE_OPERATIONS)
public class ResampleCurveNode implements MeshNode {
    public static final float DEFAULT_SEGMENT_LENGTH = 0.1f;
    public static final float MIN_LENGTH = 1e-20f;

    public static final InputPort CURVE = new InputPort("curve", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort LENGTH = new InputPort("length", PortType.FLOAT, 0.1f, 0.001f, 100f);
    public static final OutputPort GEOMETRY_OUT = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Resamples curve polylines to uniform segment lengths, controlled by the length parameter.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                CURVE.name, "Input curve polyline.",
                LENGTH.name, "Target segment length (world units). Smaller = more points, smoother sweeps. Ignored when the curve is already shorter than `length`.",
                GEOMETRY_OUT.name, "Resampled curve polyline (same curves, uniform segment lengths)."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(CURVE, LENGTH);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle gb = ctx.getInput(CURVE.name, GeometryBundle.class);
        if (gb == null) {
            ctx.setOutput(GEOMETRY_OUT.name,GeometryBundle.empty());
            return;
        }
        float segLen = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, LENGTH.name, LENGTH.defaultValue),
                DEFAULT_SEGMENT_LENGTH);
        if (segLen <= MIN_LENGTH) {
            ctx.setOutput(GEOMETRY_OUT.name,gb);
            return;
        }

        Object raw = gb.slots().get(CurveGeometry.SLOT);
        if (!(raw instanceof CurveGeometry cg)) {
            ctx.setOutput(GEOMETRY_OUT.name,gb);
            return;
        }

        CurveGeometry subdivided = cg.subdivided(segLen);
        if (subdivided.pointCount() == 0) {
            subdivided = CurveGeometry.singlePolyline(new float[0], false);
        }
        ctx.setOutput(GEOMETRY_OUT.name, gb.withSlot(CurveGeometry.SLOT, subdivided));
    }
}
