package ixdar.geometry.mesh.nodes.modifier;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.ops.MeshHoleFiller;
import ixdar.geometry.mesh.data.ops.MeshRepair;
import ixdar.geometry.mesh.data.ops.MeshRepairReport;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;
import ixdar.platform.Platforms;

/**
 * Repairs a triangle mesh's topology so the half-edge build and the quad-layout pipeline can run
 * on it: epsilon weld, consistent winding, non-manifold edges split apart, degenerate faces
 * resolved, shells classified, small boundary loops filled. Removes no surface beyond degenerate
 * faces and the debris shells the caller asked to drop.
 */
@MeshNodeAnnotation(id = "repair_mesh")
public class RepairMeshNode implements MeshNode {

    /**
     * Longest boundary loop the filler attempts by default. Every hole on a Trellis2 scan is a
     * scanning artefact rather than a gap to preserve, so the default is a safety cap against a
     * pathological loop, not a quality threshold.
     */
    public static final int DEFAULT_MAX_HOLE_EDGES = 4096;

    /** Face count below which a shell counts as debris by default. */
    public static final int DEFAULT_MIN_SHELL_FACES = 100;

    /** Log prefix of the node's report line. */
    public static final String LOG_PREFIX = "[repair_mesh] ";

    public static final InputPort GEOMETRY =
            new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);

    public static final InputPort WELD_EPSILON =
            new InputPort("weld_epsilon", PortType.FLOAT, 0f, 0f, 1f);

    public static final InputPort MAX_HOLE_EDGES = new InputPort("max_hole_edges", PortType.INT,
            DEFAULT_MAX_HOLE_EDGES, 0f, (float) MeshHoleFiller.MAX_LOOP_LENGTH);

    public static final InputPort MIN_SHELL_FACES = new InputPort("min_shell_faces", PortType.INT,
            DEFAULT_MIN_SHELL_FACES, 0f, null);

    public static final InputPort STRICT = new InputPort("strict", PortType.BOOLEAN, false);

    public static final OutputPort GEOMETRY_OUT =
            new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, WELD_EPSILON, MAX_HOLE_EDGES, MIN_SHELL_FACES, STRICT);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Repairs mesh topology in place of a rebuild: welds near-coincident vertices, makes"
                + " the winding consistent, splits non-manifold edges apart, resolves degenerate"
                + " faces, drops only debris and enclosed shells, and fills small boundary loops.";
    }

    @Override
    public boolean destructive() {
        return true;
    }

    @Override
    public List<String> consumes() {
        return List.of(EdgeMarks.SLOT);
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output. The output carries the repaired mesh as a half-edge "
                        + "mesh, which is what the repair exists to produce, plus the per-corner "
                        + CornerUvField.SLOT + " field, the " + MeshRepairReport.SLOT
                        + " report and edge marks labelled '"
                        + MeshRepairReport.OPEN_BOUNDARY_LABEL
                        + "' on the loops the repair left open. Per-edge marks on the input are"
                        + " indexed by the input's edge ids and are dropped.",
                WELD_EPSILON.name, "Distance below which two vertices fuse, in world units. Zero"
                        + " (the default) skips the weld: the glTF loader already welds"
                        + " bitwise-identical positions on import, so this is only for genuinely"
                        + " near-coincident vertices.",
                MAX_HOLE_EDGES.name, "Safety cap on the boundary loops to fill, in edges. Every"
                        + " loop at or under it is filled and faired; larger ones stay open and are"
                        + " reported with their edge counts. The triangulation is cubic in the loop"
                        + " length and quadratic in memory, which is what the cap guards; the"
                        + " ceiling is " + MeshHoleFiller.MAX_LOOP_LENGTH + ".",
                MIN_SHELL_FACES.name, "Shells with fewer faces than this are debris and are"
                        + " dropped, as are shells fully enclosed by another. The largest shell is"
                        + " always kept; everything else is reported either way.",
                STRICT.name, "When true, evaluation fails with the full report if a boundary loop"
                        + " was left open or an orientation contradiction survived the split."
                        + " Debris and bubble shells are a deliberate drop, not a tear, so they do"
                        + " not fail it."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(
                ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        if (base.mesh() == null || base.mesh().faceCount() == 0) {
            ctx.setOutput(GEOMETRY.name, base);
            return;
        }
        MeshRepair repair = new MeshRepair(base);
        repair.weldEpsilon = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, WELD_EPSILON.name, WELD_EPSILON.defaultValue), 0f);
        repair.maxHoleEdges = FieldBroadcast.intScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, MAX_HOLE_EDGES.name, MAX_HOLE_EDGES.defaultValue),
                DEFAULT_MAX_HOLE_EDGES);
        repair.minShellFaces = FieldBroadcast.intScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, MIN_SHELL_FACES.name, MIN_SHELL_FACES.defaultValue),
                DEFAULT_MIN_SHELL_FACES);
        repair.build();
        Platforms.log(LOG_PREFIX + repair.report.summaryLine());

        Object strictInput = FieldBroadcast.getInputOrDefault(ctx, STRICT.name, STRICT.defaultValue);
        if (FieldBroadcast.boolAt(strictInput, 0, false) && repair.report.hasUnrepaired()) {
            throw new IllegalStateException("repair_mesh strict mode: the mesh is still torn -- "
                    + repair.report.oversizedHoleCount + " boundary loops over max_hole_edges, "
                    + repair.report.unfillableHoleCount + " with no valid triangulation, "
                    + repair.report.unorientedEdgeCount + " surviving orientation contradictions\n"
                    + repair.report.toText());
        }
        ctx.setOutput(GEOMETRY.name, repair.output);
    }
}
