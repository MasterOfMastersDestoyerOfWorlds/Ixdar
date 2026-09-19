package ixdar.geometry.mesh.nodes.selection;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.FlipGeodesics;
import ixdar.geometry.mesh.data.paths.SurfaceRing;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.geometry.mesh.nodes.api.BoolField;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;

/**
 * Turns a list of surface points into a ring: each point snaps to its nearest vertex, consecutive
 * points are joined by shortest edge walks, and FlipOut tightens the closed cycle.
 */
@MeshNodeAnnotation(id = "loop_through_points")
public class LoopThroughPointsNode implements MeshNode {

    /** Edge-marks label the snapped edge cycle is written under unless {@code label} says else. */
    public static final String DEFAULT_MARK_LABEL = "ring";

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE,
            null);
    public static final InputPort POINTS = new InputPort("points", PortType.STRING, "");
    public static final InputPort TIGHTEN = new InputPort("tighten", PortType.BOOLEAN, true);
    public static final InputPort CLOSED = new InputPort("closed", PortType.BOOLEAN, true);
    public static final InputPort PIN = new InputPort("pin", PortType.BOOLEAN, false);
    public static final InputPort ITERATIONS = new InputPort("iterations", PortType.INT, -1, -1f,
            1000000f);
    public static final InputPort LABEL = new InputPort("label", PortType.STRING,
            DEFAULT_MARK_LABEL);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name,
            PortType.GEOMETRY_BUNDLE);
    public static final OutputPort SELECTION = new OutputPort("selection", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, POINTS, TIGHTEN, CLOSED, PIN, ITERATIONS, LABEL);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT, SELECTION);
    }

    @Override
    public String description() {
        return "Rings a surface through authored points: each point snaps to its nearest vertex, "
                + "consecutive points are joined by shortest edge walks, the last closes back to "
                + "the first, and FlipOut (Sharp & Crane 2020) tightens the cycle into a locally "
                + "shortest geodesic, emitting the polyline and the mesh edges nearest it.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name,
                "Input: the triangle mesh to ring. Output: the same bundle carrying the ring as "
                        + "curve geometry and its snapped edge cycle in the edge-marks slot under "
                        + "`label`, ready for mark_edges consumers and delete_geometry cuts.",
                POINTS.name,
                "Surface points as \"x,y,z; x,y,z; ...\", stored as positions and re-snapped on "
                        + "reload, so the same statement re-evaluates on another aligned scan. "
                        + "Three are needed to bound a loop; two only define an open path.",
                TIGHTEN.name,
                "If true the closed walk is straightened to a geodesic; if false the raw "
                        + "shortest-edge seed is kept, which is how a seed and its geodesic are "
                        + "drawn over one another in contrasting colours.",
                CLOSED.name,
                "If true the walk returns from the last point to the first and tightens as a loop "
                        + "with no fixed endpoints; if false the two end points are pinned and "
                        + "only the interior straightens.",
                PIN.name,
                "If true every point stays where it was authored and only the arcs between "
                        + "consecutive points straighten, so a ring keeps a kink at each point "
                        + "instead of sliding off to the nearest neck; if false the tightened "
                        + "loop is free to leave the points behind.",
                ITERATIONS.name,
                "Cap on wedge straightenings: -1 runs until every wedge is straight, and a "
                        + "positive value stops part-way so the tightening can be watched.",
                LABEL.name,
                "Name the snapped edge cycle is stored under in the edge-marks slot, so a seed "
                        + "run and a tightened run of this node can both be kept on one bundle.",
                SELECTION.name,
                "Per-edge BoolField, true on every edge of the conforming cycle nearest the "
                        + "tightened ring.");
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
        boolean closed = !Boolean.FALSE.equals(ctx.getInput(CLOSED.name, Boolean.class));
        boolean tighten = !Boolean.FALSE.equals(ctx.getInput(TIGHTEN.name, Boolean.class));
        boolean pin = Boolean.TRUE.equals(ctx.getInput(PIN.name, Boolean.class));
        Number iterationsInput = ctx.getInput(ITERATIONS.name, Number.class);
        int iterations = iterationsInput == null ? FlipGeodesics.UNBOUNDED_ITERATIONS
                : Math.max(FlipGeodesics.UNBOUNDED_ITERATIONS, iterationsInput.intValue());
        if (!tighten) {
            iterations = 0;
        }
        String label = ctx.getInput(LABEL.name, String.class);
        if (label == null || label.isBlank()) {
            label = DEFAULT_MARK_LABEL;
        }
        float[] points = SurfaceWaypoints.parse(ctx.getInput(POINTS.name, String.class));
        int waypointCount = points.length / SurfaceWaypoints.COORDINATES_PER_WAYPOINT;
        SurfaceRing ring = SurfaceRing.through(mesh, points, waypointCount, closed,
                pin ? waypointCount : 0, iterations);

        GeometryBundle out = bundle.withSlot(CurveGeometry.SLOT,
                CurveGeometry.singlePolyline(ring.polyline));
        ctx.setOutput(GEOMETRY.name, EdgeMarks.with(out, label, ring.markedByEdgeId));
        boolean[] selection = new boolean[mesh.edgeCount()];
        for (int activeEdge = 0; activeEdge < selection.length; activeEdge++) {
            int edgeId = mesh.edgeIdAt(activeEdge);
            selection[activeEdge] = edgeId < ring.markedByEdgeId.length
                    && ring.markedByEdgeId[edgeId];
        }
        ctx.setOutput(SELECTION.name, new BoolField(selection));
    }
}
