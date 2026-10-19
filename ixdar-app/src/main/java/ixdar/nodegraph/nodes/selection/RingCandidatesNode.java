package ixdar.nodegraph.nodes.selection;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.bundle.RingBundle;
import ixdar.geometry.mesh.data.rings.RingCandidateExtractor;
import ixdar.geometry.mesh.data.rings.RingCandidates;
import ixdar.nodegraph.field.BoolField;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;

/**
 * Proposes every neck loop a skeleton suggests: branch regions, their boundaries as seed loops,
 * FlipOut tightening, and a ranked list with the weak ones dropped.
 */
@MeshNodeAnnotation(id = "ring_candidates", category = NodeCategory.SELECTION)
public class RingCandidatesNode implements MeshNode {

    public static final float MAXIMUM_RESOLUTION = 512f;

    public static final float MAXIMUM_NECKNESS = 8f;

    public static final InputPort GEOMETRY = InputPort.manifoldGeometry("geometry");
    public static final InputPort RESOLUTION = new InputPort("resolution", PortType.INT,
            MeshSkeletonExtractor.DEFAULT_RESOLUTION, 16f, MAXIMUM_RESOLUTION);
    public static final InputPort MIN_NECKNESS = new InputPort("min_neckness", PortType.FLOAT,
            RingCandidateExtractor.DEFAULT_MINIMUM_NECKNESS, 0f, MAXIMUM_NECKNESS);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name,
            PortType.GEOMETRY_BUNDLE);
    public static final OutputPort SELECTION = new OutputPort("selection", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, RESOLUTION, MIN_NECKNESS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT, SELECTION);
    }

    @Override
    public String description() {
        return "Proposes neck loops automatically: every vertex joins its nearest TEASAR skeleton "
                + "branch, each boundary between two branch regions seeds a loop, FlipOut tightens "
                + "it, and loops that grew, collapsed or merged are dropped. Survivors are ranked "
                + "by neckness, the local circumference over the loop's length.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name,
                "Input: the triangle mesh to propose rings on. Output: the same bundle carrying "
                        + "the ranked rings in the " + RingCandidates.SLOT + " slot, their "
                        + "polylines as curve geometry, and one edge-marks label `ring_NN` per "
                        + "ring, numbered in ranked order.",
                RESOLUTION.name,
                "Voxel-grid resolution along the longest bounding-box axis for the skeleton. 128 "
                        + "resolves limb-scale branches; a scan with antenna-scale branches needs "
                        + "256 or more, at cubic memory cost.",
                MIN_NECKNESS.name,
                "Neckness a ring must reach to be kept: the local circumference, 2*pi times the "
                        + "radius of the sphere inscribed at the ring's centroid, over the "
                        + "tightened loop's length. 1 girdles a tube exactly; below about 0.6 the "
                        + "loop wanders across a blend instead of pinching a neck.",
                SELECTION.name,
                "Per-edge BoolField, true on every edge of every accepted ring's conforming "
                        + "cycle.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = ctx.getInput(GEOMETRY.name, GeometryBundle.class);
        if (bundle == null) {
            bundle = GeometryBundle.empty();
        }
        MeshTopology mesh = bundle.mesh();
        if (mesh == null || mesh.faceCount() == 0) {
            ctx.setOutput(GEOMETRY.name, bundle);
            ctx.setOutput(SELECTION.name, false);
            return;
        }
        RingCandidateExtractor extractor = new RingCandidateExtractor();
        Number resolution = ctx.getInput(RESOLUTION.name, Number.class);
        if (resolution != null) {
            extractor.resolution = resolution.intValue();
        }
        Number minimumNeckness = ctx.getInput(MIN_NECKNESS.name, Number.class);
        if (minimumNeckness != null) {
            extractor.minimumNeckness = minimumNeckness.floatValue();
        }
        RingCandidates rings = extractor.extract(mesh);
        ctx.setOutput(GEOMETRY.name, RingBundle.with(bundle, rings));
        ctx.setOutput(SELECTION.name,
                new BoolField(RingBundle.selectionByActiveEdge(mesh, rings)));
    }
}
