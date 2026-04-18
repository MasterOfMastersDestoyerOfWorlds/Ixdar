package ixdar.geometry.mesh.nodes.curve;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

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

    private static final InputPort CURVE = new InputPort("curve", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort LENGTH = new InputPort("length", PortType.FLOAT, 0.1f, 0.001f, 100f);
    private static final OutputPort CURVE_OUT = new OutputPort("curve", PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Resamples curve polylines to uniform segment lengths, controlled by the length parameter.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "curve", "Input curve polyline.",
                "length", "Target segment length (world units). Smaller = more points, smoother sweeps. Ignored when the curve is already shorter than `length`."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(CURVE, LENGTH);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(CURVE_OUT);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle gb = GeometryBundles.bundlePart(ctx.getInput("curve", Object.class));
        if (gb == null) {
            ctx.setOutput("curve", GeometryBundle.empty());
            return;
        }
        float segLen = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, "length", LENGTH.defaultValue()),
                0.1f);
        if (segLen <= 1e-20f) {
            ctx.setOutput("curve", gb);
            return;
        }

        Object raw = gb.slots().get("_curve");
        if (!(raw instanceof CurveGeometry cg)) {
            ctx.setOutput("curve", gb);
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
                newOff.add(out.size() / 3);
                continue;
            }
            boolean first = true;
            for (int pi = s; pi < e - 1; pi++) {
                int i0 = 3 * pi;
                int i1 = 3 * (pi + 1);
                p0.set(pos[i0], pos[i0 + 1], pos[i0 + 2]);
                p1.set(pos[i1], pos[i1 + 1], pos[i1 + 2]);
                appendResampled(p0, p1, segLen, out, first);
                first = false;
            }
            newOff.add(out.size() / 3);
        }

        if (out.isEmpty()) {
            ctx.setOutput("curve", gb.withSlot("_curve", CurveGeometry.singlePolyline(new float[0])));
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
        ctx.setOutput("curve", gb.withSlot("_curve", new CurveGeometry(np, ofa)));
    }

    private static void appendResampled(Vector3f a, Vector3f b, float segLen, ArrayList<Float> out, boolean firstOfCurve) {
        float len = a.distance(b);
        if (len < 1e-20f) {
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
