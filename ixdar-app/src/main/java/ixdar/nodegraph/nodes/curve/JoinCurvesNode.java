package ixdar.nodegraph.nodes.curve;

import java.util.List;

import ixdar.nodegraph.api.InputPort;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
 * Appends the first polyline of curve B to the first of curve A as one open curve carrying both
 * inputs' attributes, dropping B's start where it repeats A's end unless told not to.
 */
@MeshNodeAnnotation(id = "join_curves", category = NodeCategory.CURVE_OPERATIONS)
public class JoinCurvesNode implements MeshNode {
    public static final float DEDUP_DISTANCE_SQUARED = 1e-10f;

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
        GeometryBundle gbA = ctx.getInput(CURVE_A.name, GeometryBundle.class);
        GeometryBundle gbB = ctx.getInput(CURVE_B.name, GeometryBundle.class);

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
        float[] posA = cgA.positions;
        int offA0 = cgA.firstPoint(0);
        int nA = cgA.pointCount(0);

        float[] posB = cgB.positions;
        int offB0 = cgB.firstPoint(0);
        int nB = cgB.pointCount(0);

        // Check if endpoint of A matches startpoint of B
        int skipB = 0;
        if (dedup && nA > 0 && nB > 0) {
            int lastA = 3 * (offA0 + nA - 1);
            int firstB = 3 * offB0;
            float dx = posA[lastA] - posB[firstB];
            float dy = posA[lastA + 1] - posB[firstB + 1];
            float dz = posA[lastA + 2] - posB[firstB + 2];
            if (dx * dx + dy * dy + dz * dz < DEDUP_DISTANCE_SQUARED) {
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

        // An attribute only one side carries reads 0 on the other side's points.
        Map<String, float[]> pointAttributes = new TreeMap<>();
        Map<String, float[]> curveAttributes = new TreeMap<>();
        Set<String> pointNames = new TreeSet<>(cgA.pointAttributes.keySet());
        pointNames.addAll(cgB.pointAttributes.keySet());
        for (String name : pointNames) {
            float[] values = new float[totalPoints];
            if (cgA.pointAttributes.containsKey(name)) {
                System.arraycopy(cgA.pointAttributes.get(name), offA0, values, 0, nA);
            }
            if (cgB.pointAttributes.containsKey(name)) {
                System.arraycopy(cgB.pointAttributes.get(name), offB0 + skipB, values, nA, nB - skipB);
            }
            pointAttributes.put(name, values);
        }
        Set<String> curveNames = new TreeSet<>(cgA.curveAttributes.keySet());
        curveNames.addAll(cgB.curveAttributes.keySet());
        for (String name : curveNames) {
            float[] source = cgA.curveAttributes.getOrDefault(name, cgB.curveAttributes.get(name));
            curveAttributes.put(name, new float[] { source[0] });
        }

        CurveGeometry joined = new CurveGeometry(combined, new int[] { 0, totalPoints }, new boolean[1],
                pointAttributes, curveAttributes);
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
