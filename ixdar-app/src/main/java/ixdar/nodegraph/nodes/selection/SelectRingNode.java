package ixdar.nodegraph.nodes.selection;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.bundle.RingBundle;
import ixdar.geometry.mesh.data.rings.RingCandidates;
import ixdar.nodegraph.field.BoolField;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.Vector3Value;

/**
 * Picks the one proposed ring passing nearest a surface point, so a graph names a ring by where it
 * is rather than by the number a run happened to give it.
 */
@MeshNodeAnnotation(id = "select_ring", category = NodeCategory.SELECTION)
public class SelectRingNode implements MeshNode {

    public static final String SELECTED_LABEL = "selected_ring";

    public static final InputPort RINGS = new InputPort("rings", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort POINT = new InputPort("point", PortType.VECTOR3,
            new Vector3Value(0f, 0f, 0f));
    public static final OutputPort GEOMETRY_OUT = new OutputPort("geometry",
            PortType.GEOMETRY_BUNDLE);
    public static final OutputPort SELECTION = new OutputPort("selection", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(RINGS, POINT);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT, SELECTION);
    }

    @Override
    public String description() {
        return "Chooses the proposed ring whose tightened polyline passes nearest a surface point "
                + "and republishes it alone, as curve geometry, as the `selected_ring` edge marks "
                + "and as a per-edge selection.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                RINGS.name,
                "A bundle carrying ring candidates in the " + RingCandidates.SLOT + " slot, as "
                        + "`ring_candidates` or `ring_at_branch` leaves it.",
                POINT.name,
                "Surface point naming the ring: the ring with the nearest polyline point wins, so "
                        + "a coordinate anywhere along the intended neck selects it.",
                GEOMETRY_OUT.name,
                "The input bundle with the picked ring alone in the " + RingCandidates.SLOT
                        + " slot, its polyline as curve geometry, and its edges marked "
                        + SELECTED_LABEL + "; unchanged when the input carries no rings.",
                SELECTION.name,
                "Per-edge BoolField, true on the picked ring's conforming cycle.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = ctx.getInput(RINGS.name, GeometryBundle.class);
        if (bundle == null) {
            bundle = GeometryBundle.empty();
        }
        MeshTopology mesh = bundle.mesh();
        RingCandidates rings = RingBundle.of(bundle);
        Vector3Value point = ctx.getInput(POINT.name, Vector3Value.class);
        if (rings == null || rings.ringCount == 0 || mesh == null || point == null) {
            ctx.setOutput(GEOMETRY_OUT.name, bundle);
            ctx.setOutput(SELECTION.name, false);
            return;
        }
        int ring = rings.nearestRing(point.x(), point.y(), point.z());
        RingCandidates picked = rings.single(ring);
        GeometryBundle out = RingBundle.with(bundle, picked);
        out = EdgeMarks.with(out, SELECTED_LABEL,
                picked.edgeMarks(0, RingBundle.edgeIdCeiling(mesh)));
        ctx.setOutput(GEOMETRY_OUT.name, out);
        ctx.setOutput(SELECTION.name,
                new BoolField(RingBundle.selectionByActiveEdge(mesh, picked)));
    }
}
