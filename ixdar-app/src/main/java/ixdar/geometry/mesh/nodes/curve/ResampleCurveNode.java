package ixdar.geometry.mesh.nodes.curve;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import java.util.Map;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "resample_curve")
public class ResampleCurveNode implements MeshNode {
    public static final String CURVE_2 = "curve";
    public static final String LENGTH_2 = "length";
    public static final String GEOMETRY = "geometry";
    public static final String CURVE_3 = "_curve";
    public static final float NUM_0_1 = 0.1f;
    public static final float NUM_1e_20 = 1e-20f;
    public static final int NUM_3 = 3;

    private static final InputPort CURVE = new InputPort(CURVE_2, PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort LENGTH = new InputPort(LENGTH_2, PortType.FLOAT, 0.1f, 0.001f, 100f);
    private static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY, PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Resamples curve polylines to uniform segment lengths, controlled by the length parameter.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                CURVE_2, "Input curve polyline.",
                LENGTH_2, "Target segment length (world units). Smaller = more points, smoother sweeps. Ignored when the curve is already shorter than `length`.",
                GEOMETRY, "Resampled curve polyline (same curves, uniform segment lengths)."
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
        GeometryBundle gb = GeometryBundles.bundlePart(ctx.getInput(CURVE_2, Object.class));
        if (gb == null) {
            ctx.setOutput(GEOMETRY,GeometryBundle.empty());
            return;
        }
        float segLen = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, LENGTH_2, LENGTH.defaultValue()),
                NUM_0_1);
        if (segLen <= NUM_1e_20) {
            ctx.setOutput(GEOMETRY,gb);
            return;
        }

        Object raw = gb.slots().get(CURVE_3);
        if (!(raw instanceof CurveGeometry cg)) {
            ctx.setOutput(GEOMETRY,gb);
            return;
        }

        float[] pos = cg.positions();
        int[] off = cg.curveOffsets();
        ArrayList<Float> out = new ArrayList<>();
        ArrayList<Integer> newOff = new ArrayList<>();
        newOff.add(0);
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();

        for (int c = 0; c < cg.curveCount(); c++) {
            int s = off[c];
            int e = off[c + 1];
            if (e - s < 2) {
                newOff.add(out.size() / NUM_3);
                continue;
            }
            boolean first = true;
            for (int pi = s; pi < e - 1; pi++) {
                int i0 = NUM_3 * pi;
                int i1 = NUM_3 * (pi + 1);
                p0.set(pos[i0], pos[i0 + 1], pos[i0 + 2]);
                p1.set(pos[i1], pos[i1 + 1], pos[i1 + 2]);
                appendResampled(p0, p1, segLen, out, first);
                first = false;
            }
            newOff.add(out.size() / NUM_3);
        }

        if (out.isEmpty()) {
            ctx.setOutput(GEOMETRY,gb.withSlot(CURVE_3, CurveGeometry.singlePolyline(new float[0])));
            return;
        }

        float[] np = new float[out.size()];
        for (int i = 0; i < out.size(); i++) {
            np[i] = out.get(i);
        }
        int[] ofa = new int[newOff.size()];
        for (int i = 0; i < newOff.size(); i++) {
            ofa[i] = newOff.get(i);
        }
        ctx.setOutput(GEOMETRY,gb.withSlot(CURVE_3, new CurveGeometry(np, ofa)));
    }

    private static void appendResampled(Vector3f a, Vector3f b, float segLen, ArrayList<Float> out, boolean firstOfCurve) {
        float len = a.distance(b);
        if (len < NUM_1e_20) {
            if (firstOfCurve) {
                out.add(a.x);
                out.add(a.y);
                out.add(a.z);
            }
            return;
        }
        int steps = Math.max(1, (int) Math.ceil(len / segLen));
        Vector3f d = new Vector3f(b).sub(a);
        for (int s = 0; s <= steps; s++) {
            float t = s / (float) steps;
            float x = a.x + t * d.x;
            float y = a.y + t * d.y;
            float z = a.z + t * d.z;
            if (!firstOfCurve && s == 0) {
                continue;
            }
            out.add(x);
            out.add(y);
            out.add(z);
        }
    }
}
