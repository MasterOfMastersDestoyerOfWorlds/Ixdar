package ixdar.geometry.mesh.nodes.curve;

import java.util.List;

import ixdar.geometry.mesh.nodes.api.InputPort;

import java.util.Map;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;

/**
 * Concatenates two curve polylines into a single continuous curve.
 * Extracts the first polyline from each input bundle and appends them.
 * When deduplicate is true (default), removes the duplicate point at the join
 * if the last point of curve A matches the first point of curve B.
 */
@MeshNodeAnnotation(id = "join_curves")
public class JoinCurvesNode implements MeshNode {
    public static final int NUM_3 = 3;
    public static final float NUM_1e_10 = 1e-10f;

    public static final InputPort CURVE_A = new InputPort("curve_a", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort CURVE_B = new InputPort("curve_b", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort DEDUPLICATE = new InputPort("deduplicate", PortType.BOOLEAN, true);
    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Concatenates two curve polylines into a single continuous curve, optionally deduplicating the shared endpoint at the join.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                CURVE_A.name, "First curve polyline.",
                CURVE_B.name, "Second curve polyline, appended after a's end.",
                DEDUPLICATE.name, "If true (default), drop b's start vertex when it coincides with a's end, keeping the join clean.",
                GEOMETRY.name, "Combined curve."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(CURVE_A, CURVE_B, DEDUPLICATE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle gbA = GeometryBundles.bundlePart(ctx.getInput(CURVE_A.name, Object.class));
        GeometryBundle gbB = GeometryBundles.bundlePart(ctx.getInput(CURVE_B.name, Object.class));

        CurveGeometry cgA = extractCurve(gbA);
        CurveGeometry cgB = extractCurve(gbB);

        if (cgA == null && cgB == null) {
            ctx.setOutput(GEOMETRY.name,GeometryBundle.empty());
            return;
        }
        if (cgA == null) {
            ctx.setOutput(GEOMETRY.name,GeometryBundle.empty().withSlot(CurveGeometry.SLOT, cgB));
            return;
        }
        if (cgB == null) {
            ctx.setOutput(GEOMETRY.name,GeometryBundle.empty().withSlot(CurveGeometry.SLOT, cgA));
            return;
        }

        boolean dedup = true;
        Object dedupObj = FieldBroadcast.getInputOrDefault(ctx, DEDUPLICATE.name, DEDUPLICATE.defaultValue);
        if (dedupObj instanceof Boolean b) dedup = b;

        // Extract first polyline from each
        float[] posA = cgA.positions();
        int offA0 = cgA.curveOffsets()[0];
        int offA1 = cgA.curveOffsets()[1];
        int nA = offA1 - offA0;

        float[] posB = cgB.positions();
        int offB0 = cgB.curveOffsets()[0];
        int offB1 = cgB.curveOffsets()[1];
        int nB = offB1 - offB0;

        // Check if endpoint of A matches startpoint of B
        int skipB = 0;
        if (dedup && nA > 0 && nB > 0) {
            int lastA = NUM_3 * (offA0 + nA - 1);
            int firstB = NUM_3 * offB0;
            float dx = posA[lastA] - posB[firstB];
            float dy = posA[lastA + 1] - posB[firstB + 1];
            float dz = posA[lastA + 2] - posB[firstB + 2];
            if (dx * dx + dy * dy + dz * dz < NUM_1e_10) {
                skipB = 1;
            }
        }

        int totalPoints = nA + nB - skipB;
        float[] combined = new float[totalPoints * NUM_3];

        // Copy A points
        System.arraycopy(posA, offA0 * NUM_3, combined, 0, nA * NUM_3);

        // Copy B points (skipping first if dedup matched)
        int srcOffset = (offB0 + skipB) * NUM_3;
        int dstOffset = nA * NUM_3;
        int copyCount = (nB - skipB) * NUM_3;
        System.arraycopy(posB, srcOffset, combined, dstOffset, copyCount);

        CurveGeometry joined = CurveGeometry.singlePolyline(combined);
        ctx.setOutput(GEOMETRY.name,GeometryBundle.empty().withSlot(CurveGeometry.SLOT, joined));
    }

    private static CurveGeometry extractCurve(GeometryBundle gb) {
        if (gb == null) return null;
        Object raw = gb.slots().get(CurveGeometry.SLOT);
        if (raw instanceof CurveGeometry cg && cg.pointCount() >= 1) {
            return cg;
        }
        return null;
    }
}
