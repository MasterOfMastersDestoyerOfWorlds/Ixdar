package ixdar.geometry.mesh.nodes.curve;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import java.util.Map;

import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

@MeshNodeAnnotation(id = "resample_curve")
public class ResampleCurveNode implements MeshNode {
    public static final float NUM_0_1 = 0.1f;
    public static final float NUM_1e_20 = 1e-20f;
    public static final int NUM_3 = 3;

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
                NUM_0_1);
        if (segLen <= NUM_1e_20) {
            ctx.setOutput(GEOMETRY_OUT.name,gb);
            return;
        }

        Object raw = gb.slots().get(CurveGeometry.SLOT);
        if (!(raw instanceof CurveGeometry cg)) {
            ctx.setOutput(GEOMETRY_OUT.name,gb);
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
            ctx.setOutput(GEOMETRY_OUT.name,gb.withSlot(CurveGeometry.SLOT, CurveGeometry.singlePolyline(new float[0])));
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
        ctx.setOutput(GEOMETRY_OUT.name,gb.withSlot(CurveGeometry.SLOT, new CurveGeometry(np, ofa)));
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
