package ixdar.geometry.mesh.nodes.curve;

import java.util.List;

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

/**
 * Concatenates two curve polylines into a single continuous curve.
 * Extracts the first polyline from each input bundle and appends them.
 * When deduplicate is true (default), removes the duplicate point at the join
 * if the last point of curve A matches the first point of curve B.
 */
@MeshNodeAnnotation(id = "join_curves")
public class JoinCurvesNode implements MeshNode {

    private static final InputPort CURVE_A = new InputPort("curve_a", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort CURVE_B = new InputPort("curve_b", PortType.GEOMETRY_BUNDLE, null);
    private static final InputPort DEDUPLICATE = new InputPort("deduplicate", PortType.BOOLEAN, true);
    private static final OutputPort CURVE = new OutputPort("curve", PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Concatenates two curve polylines into a single continuous curve, optionally deduplicating the shared endpoint at the join.";
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(CURVE_A, CURVE_B, DEDUPLICATE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(CURVE);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle gbA = GeometryBundles.bundlePart(ctx.getInput("curve_a", Object.class));
        GeometryBundle gbB = GeometryBundles.bundlePart(ctx.getInput("curve_b", Object.class));

        CurveGeometry cgA = extractCurve(gbA);
        CurveGeometry cgB = extractCurve(gbB);

        if (cgA == null && cgB == null) {
            ctx.setOutput("curve", GeometryBundle.empty());
            return;
        }
        if (cgA == null) {
            ctx.setOutput("curve", GeometryBundle.empty().withSlot("_curve", cgB));
            return;
        }
        if (cgB == null) {
            ctx.setOutput("curve", GeometryBundle.empty().withSlot("_curve", cgA));
            return;
        }

        boolean dedup = true;
        Object dedupObj = FieldBroadcast.getInputOrDefault(ctx, "deduplicate", DEDUPLICATE.defaultValue());
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
            int lastA = 3 * (offA0 + nA - 1);
            int firstB = 3 * offB0;
            float dx = posA[lastA] - posB[firstB];
            float dy = posA[lastA + 1] - posB[firstB + 1];
            float dz = posA[lastA + 2] - posB[firstB + 2];
            if (dx * dx + dy * dy + dz * dz < 1e-10f) {
                skipB = 1;
            }
        }

        int totalPoints = nA + nB - skipB;
        float[] combined = new float[totalPoints * 3];

        // Copy A points
        System.arraycopy(posA, offA0 * 3, combined, 0, nA * 3);

        // Copy B points (skipping first if dedup matched)
        int srcOffset = (offB0 + skipB) * 3;
        int dstOffset = nA * 3;
        int copyCount = (nB - skipB) * 3;
        System.arraycopy(posB, srcOffset, combined, dstOffset, copyCount);

        CurveGeometry joined = CurveGeometry.singlePolyline(combined);
        ctx.setOutput("curve", GeometryBundle.empty().withSlot("_curve", joined));
    }

    private static CurveGeometry extractCurve(GeometryBundle gb) {
        if (gb == null) return null;
        Object raw = gb.slots().get("_curve");
        if (raw instanceof CurveGeometry cg && cg.pointCount() >= 1) {
            return cg;
        }
        return null;
    }
}
