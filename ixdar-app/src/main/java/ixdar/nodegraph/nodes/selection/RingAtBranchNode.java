package ixdar.nodegraph.nodes.selection;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.rings.BranchCrossSectionRing;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor;
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
 * Cuts one ring across a limb at a fraction of its length: the skeleton branch nearest an authored
 * point supplies the cutting plane, which seeds a loop that FlipOut tightens.
 */
@MeshNodeAnnotation(id = "ring_at_branch", category = NodeCategory.SELECTION)
public class RingAtBranchNode implements MeshNode {

    public static final float MAXIMUM_RESOLUTION = 512f;

    public static final InputPort GEOMETRY = InputPort.manifoldGeometry("geometry");
    public static final InputPort BRANCH = new InputPort("branch", PortType.VECTOR3,
            new Vector3Value(0f, 0f, 0f));
    public static final InputPort PARAMETER = new InputPort("t", PortType.FLOAT, 0.5f, 0f, 1f);
    public static final InputPort RESOLUTION = new InputPort("resolution", PortType.INT,
            MeshSkeletonExtractor.DEFAULT_RESOLUTION, 16f, MAXIMUM_RESOLUTION);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name,
            PortType.GEOMETRY_BUNDLE);
    public static final OutputPort SELECTION = new OutputPort("selection", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, BRANCH, PARAMETER, RESOLUTION);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT, SELECTION);
    }

    @Override
    public String description() {
        return "Cuts a cross-section ring across the TEASAR skeleton branch nearest an authored "
                + "point, at a fraction of that branch's length: the plane there picks three "
                + "surface points around the limb and FlipOut tightens the walk through them.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name,
                "Input: the triangle mesh to cut. Output: the same bundle carrying the one ring "
                        + "in the " + RingCandidates.SLOT + " slot, its polyline as curve "
                        + "geometry and its edges marked `ring_00`.",
                BRANCH.name,
                "A point near the limb to cut, in surface coordinates; the skeleton branch with "
                        + "the nearest joint is the one cut. Branch numbers are not authored: "
                        + "they change with the voxel resolution, a point does not.",
                PARAMETER.name,
                "Fraction of the branch's length to cut at, 0 at its tip and 1 at the joint where "
                        + "it meets its parent. The plane only seeds the loop, so the tightened "
                        + "ring settles on the nearest constriction to that fraction.",
                RESOLUTION.name,
                "Voxel-grid resolution along the longest bounding-box axis for the skeleton; a "
                        + "branch too thin to appear at this resolution cannot be cut.",
                SELECTION.name,
                "Per-edge BoolField, true on the ring's conforming cycle.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = ctx.getInput(GEOMETRY.name, GeometryBundle.class);
        if (bundle == null) {
            bundle = GeometryBundle.empty();
        }
        MeshTopology mesh = bundle.mesh();
        Vector3Value branchPoint = ctx.getInput(BRANCH.name, Vector3Value.class);
        if (mesh == null || mesh.faceCount() == 0 || branchPoint == null) {
            ctx.setOutput(GEOMETRY.name, bundle);
            ctx.setOutput(SELECTION.name, false);
            return;
        }
        BranchCrossSectionRing cutter = new BranchCrossSectionRing();
        Number resolution = ctx.getInput(RESOLUTION.name, Number.class);
        if (resolution != null) {
            cutter.resolution = resolution.intValue();
        }
        Number parameter = ctx.getInput(PARAMETER.name, Number.class);
        RingCandidates rings = cutter.extract(mesh,
                new float[] { branchPoint.x(), branchPoint.y(), branchPoint.z() },
                parameter == null ? 0.5f : parameter.floatValue());
        ctx.setOutput(GEOMETRY.name, RingBundle.with(bundle, rings));
        ctx.setOutput(SELECTION.name,
                new BoolField(RingBundle.selectionByActiveEdge(mesh, rings)));
    }
}
