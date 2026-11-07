package ixdar.nodegraph.nodes.curve;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;

/**
 * Up to eight curve sets side by side in one, every curve kept whole with its closed flag and
 * attributes; join_curves joins two curves end to end instead.
 */
@MeshNodeAnnotation(id = "merge_curves", category = NodeCategory.CURVE_OPERATIONS)
public class MergeCurvesNode implements MeshNode {

    public static final List<InputPort> CURVE_INPUTS = List.of(
            new InputPort("a", PortType.GEOMETRY_BUNDLE, null), new InputPort("b", PortType.GEOMETRY_BUNDLE, null),
            new InputPort("c", PortType.GEOMETRY_BUNDLE, null), new InputPort("d", PortType.GEOMETRY_BUNDLE, null),
            new InputPort("e", PortType.GEOMETRY_BUNDLE, null), new InputPort("f", PortType.GEOMETRY_BUNDLE, null),
            new InputPort("g", PortType.GEOMETRY_BUNDLE, null), new InputPort("h", PortType.GEOMETRY_BUNDLE, null));

    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return CURVE_INPUTS;
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public String description() {
        return "Puts up to eight curve sets side by side in one, a then b and on to h, every curve kept whole with "
                + "its closed flag and attributes, such as one traced outline per reference picture for "
                + "fit_references. An attribute a set lacks reads 0 on its curves; unconnected inputs are skipped.";
    }

    @Override
    public Map<String, String> socketDocs() {
        Map<String, String> docs = new TreeMap<>();
        for (InputPort input : CURVE_INPUTS) {
            docs.put(input.name, "A curve set; only its curve geometry is read.");
        }
        docs.put(GEOMETRY.name, "Every input's curves in input order, alone in the bundle.");
        return docs;
    }

    @Override
    public void evaluate(NodeContext ctx) {
        List<CurveGeometry> sets = new ArrayList<>();
        for (InputPort input : CURVE_INPUTS) {
            GeometryBundle bundle = ctx.getInput(input.name, GeometryBundle.class);
            if (bundle != null && bundle.slots().get(CurveGeometry.SLOT) instanceof CurveGeometry curves) {
                sets.add(curves);
            }
        }
        int points = 0;
        int curveTotal = 0;
        Map<String, float[]> pointAttributes = new TreeMap<>();
        Map<String, float[]> curveAttributes = new TreeMap<>();
        for (CurveGeometry set : sets) {
            points += set.pointCount();
            curveTotal += set.curveCount();
        }
        for (CurveGeometry set : sets) {
            for (String name : set.pointAttributes.keySet()) {
                pointAttributes.putIfAbsent(name, new float[points]);
            }
            for (String name : set.curveAttributes.keySet()) {
                curveAttributes.putIfAbsent(name, new float[curveTotal]);
            }
        }
        float[] positions = new float[points * CurveGeometry.XYZ];
        int[] offsets = new int[curveTotal + 1];
        boolean[] closed = new boolean[curveTotal];
        int pointBase = 0;
        int curveBase = 0;
        for (CurveGeometry set : sets) {
            System.arraycopy(set.positions, 0, positions, pointBase * CurveGeometry.XYZ, set.positions.length);
            for (int curve = 0; curve < set.curveCount(); curve++) {
                offsets[curveBase + curve + 1] = pointBase + set.firstPoint(curve) + set.pointCount(curve);
                closed[curveBase + curve] = set.closed[curve];
            }
            for (Map.Entry<String, float[]> attribute : set.pointAttributes.entrySet()) {
                System.arraycopy(attribute.getValue(), 0, pointAttributes.get(attribute.getKey()), pointBase,
                        set.pointCount());
            }
            for (Map.Entry<String, float[]> attribute : set.curveAttributes.entrySet()) {
                System.arraycopy(attribute.getValue(), 0, curveAttributes.get(attribute.getKey()), curveBase,
                        set.curveCount());
            }
            pointBase += set.pointCount();
            curveBase += set.curveCount();
        }
        ctx.setOutput(GEOMETRY.name, curveTotal == 0 ? GeometryBundle.empty()
                : GeometryBundle.empty().withSlot(CurveGeometry.SLOT,
                        new CurveGeometry(positions, offsets, closed, pointAttributes, curveAttributes)));
    }
}
