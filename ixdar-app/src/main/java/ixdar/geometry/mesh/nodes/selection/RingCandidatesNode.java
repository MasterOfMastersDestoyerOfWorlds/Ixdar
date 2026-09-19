package ixdar.geometry.mesh.nodes.selection;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshSkeletonExtractor;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.RingBundle;
import ixdar.geometry.mesh.data.RingCandidateExtractor;
import ixdar.geometry.mesh.data.RingCandidates;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.nodes.api.BoolField;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;

/**
 * Proposes every neck loop a skeleton suggests: branch regions, their boundaries as seed loops,
 * FlipOut tightening, and a ranked list with the weak ones dropped.
 */
@MeshNodeAnnotation(id = "ring_candidates")
public class RingCandidatesNode implements MeshNode {

    /** Largest voxel resolution the port accepts, above which the grid stops fitting in memory. */
    public static final float MAXIMUM_RESOLUTION = 512f;

    /**
     * Largest neckness threshold the port accepts: a ring scoring this much girdles a limb eight
     * times thinner than the trunk the skeleton measures it against.
     */
    public static final float MAXIMUM_NECKNESS = 8f;

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE,
            null);
    public static final InputPort RESOLUTION = new InputPort("resolution", PortType.INT,
            MeshSkeletonExtractor.NUM_128, 16f, MAXIMUM_RESOLUTION);
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
        MeshTopology mesh = bundle.mesh() == null || bundle.mesh().faceCount() == 0
                ? bundle.mesh()
                : HalfEdgeMeshEngine.fromMeshTopology(bundle.mesh());
        if (mesh != bundle.mesh()) {
            bundle = bundle.withMesh(mesh);
        }
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
