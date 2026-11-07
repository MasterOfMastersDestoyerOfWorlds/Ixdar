package ixdar.nodegraph.nodes.selection;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RingAttachment;
import ixdar.geometry.mesh.data.rings.RingSegmentMode;
import ixdar.geometry.mesh.data.paths.query.SurfaceCreases;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.spline.SurfaceWaypoints;
import ixdar.nodegraph.field.BoolField;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;

/**
 * A bisector: an open geodesic spline whose two ends sit on walls already on the bundle, rings or
 * earlier bisectors, crossing any walls between, so every region it crosses is split along it.
 */
@MeshNodeAnnotation(id = SplineCutNode.NODE_ID, category = NodeCategory.SELECTION)
public class SplineCutNode implements MeshNode {

    public static final String NODE_ID = "spline_cut";

    public static final InputPort GEOMETRY = InputPort.manifoldGeometry("geometry");
    public static final InputPort POINTS = new InputPort("points", PortType.STRING, "");
    public static final InputPort ENDS = new InputPort("ends", PortType.STRING, "");
    public static final InputPort STRETCH = new InputPort("stretch", PortType.STRING, "");
    public static final int STRETCH_POINTS = 4;
    public static final InputPort LABEL = new InputPort("label", PortType.STRING, NODE_ID);
    public static final InputPort METRIC = SplineRingNode.METRIC;
    public static final InputPort MODE = SplineRingNode.MODE;
    public static final InputPort CREASES = SplineRingNode.CREASES;
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name,
            PortType.GEOMETRY_BUNDLE);
    public static final OutputPort SELECTION = new OutputPort("selection", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, POINTS, ENDS, STRETCH, LABEL, METRIC, MODE, CREASES);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT, SELECTION);
    }

    @Override
    public String description() {
        return "A bisector: an open geodesic spline through authored anchors, traced like a "
                + "spline_ring, or in crease mode the cheapest crease path joining them, its first and last anchor attached to the walls named in ends, "
                + "rings or earlier bisectors on the bundle. It may cross other walls, and splits "
                + "every region it crosses. The ends are linked: each is re-attached to its "
                + "wall's nearest vertex on every evaluation, so editing the wall moves the end. "
                + "Emits the snapped edge path, trimmed to touch its end walls only at its two "
                + "ends, as edge marks the region flood takes as a wall. Given stretch, it is a "
                + "stretch of a closed ring: its points stay where written and the path is "
                + "trimmed where it meets its walls instead.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name,
                "Input: the mesh carrying both end walls as edge marks under their labels. "
                        + "Output: the same mesh with the bisector's edge path added under `label` "
                        + "in the edge-marks slot, its polyline as curve geometry, and the traced "
                        + "bisector itself under `label` in the " + SurfaceSpline.SLOT + " slot, "
                        + "which the ring tool adopts without tracing it again.",
                POINTS.name,
                "Anchors as \"x,y,z; x,y,z; ...\" from one end to the other, at least two. The "
                        + "first and last attach to the nearest vertex of their wall, the rest "
                        + "snap to their nearest vertex.",
                ENDS.name,
                "The edge-mark labels of the walls the first and the last anchor sit on, as "
                        + "\"ring_61; ring_62\"; both may name the same wall.",
                STRETCH.name,
                "Optional, for a bisector that is a stretch of a closed ring, whose points are "
                        + "that ring's anchors over the stretch and one past each wall: four "
                        + "points \"before; start; end; after\", the ring's anchor before the "
                        + "first point, where the stretch meets the first wall, where it meets the "
                        + "last wall, and the ring's anchor after the last point. The end points "
                        + "are not attached to their walls; each end's tangent takes before or "
                        + "after as the ring's took its neighbour, so every segment is traced as "
                        + "the ring traced it. The meeting points re-attach to their walls' "
                        + "nearest vertex on every evaluation, and the edge path is kept between "
                        + "the touches of the walls nearest them, outside the second and the "
                        + "second-to-last point. Mode is ignored.",
                LABEL.name,
                "Name the bisector's edge path is stored under in the edge-marks slot.",
                METRIC.name,
                "Required: the metric output of the surface_metric statement measured on this "
                        + "very mesh, the one every spline_ring of the chain shares. A bisector "
                        + "without one is refused.",
                MODE.name,
                "How the bisector runs between its anchors: \"geodesic\" (the default) is the "
                        + "smooth open spline; \"crease\" is the cheapest path over the surface's "
                        + "crease cost from each anchor to the next, so it follows the groove "
                        + "they sit in.",
                CREASES.name,
                "The creases output of a surface_creases statement on this very mesh; required "
                        + "by the crease mode and ignored by the geodesic one.",
                SELECTION.name,
                "Per-edge BoolField, true on every edge of the bisector.");
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
        String label = ctx.getInput(LABEL.name, String.class);
        if (label == null || label.isBlank()) {
            label = NODE_ID;
        }
        SurfaceMetric metric = ctx.getInput(METRIC.name, SurfaceMetric.class);
        if (metric == null) {
            throw new IllegalArgumentException(NODE_ID + " " + label + " has no metric; wire "
                    + "surface = surface_metric(geometry=...) on the surface it bisects and pass "
                    + "metric=surface.metric to every spline_ring and spline_cut downstream of it");
        }
        if (metric.sourceMesh != mesh) {
            throw new IllegalArgumentException(NODE_ID + " " + label + ": its metric was "
                    + "measured on another mesh; read the geometry from the surface_metric "
                    + "statement that measured it, or from a ring downstream of it");
        }
        float[] points = SurfaceWaypoints.parse(ctx.getInput(POINTS.name, String.class));
        int anchorCount = points.length / SurfaceWaypoints.COORDINATES_PER_WAYPOINT;
        String[] ends = EdgeMarks.parseLabels(ctx.getInput(ENDS.name, String.class));
        if (anchorCount < SurfaceWaypoints.OPEN_PATH_MINIMUM || ends.length != 2) {
            throw new IllegalArgumentException(NODE_ID + " " + label + " needs at least two "
                    + "points and two end walls written ends=\"ring_a; ring_b\"");
        }
        float[] stretchPoints = SurfaceWaypoints.parse(ctx.getInput(STRETCH.name, String.class));
        boolean stretch = stretchPoints.length > 0;
        if (stretch && stretchPoints.length != STRETCH_POINTS
                * SurfaceWaypoints.COORDINATES_PER_WAYPOINT) {
            throw new IllegalArgumentException(NODE_ID + " " + label + ": stretch takes four "
                    + "points, the ring's anchor before, the two meeting points and the anchor "
                    + "after");
        }
        boolean[][] walls = new boolean[2][];
        int[] anchors = SurfaceWaypoints.snap(metric.nearestVertex, points, anchorCount);
        for (int end = 0; end < 2; end++) {
            String wall = ends[end];
            walls[end] = EdgeMarks.bools(bundle, wall);
            int anchor = end == 0 ? 0 : anchorCount - 1;
            int base = SurfaceWaypoints.COORDINATES_PER_WAYPOINT * anchor;
            anchors[anchor] = walls[end] == null ? -1 : stretch ? anchors[anchor]
                    : RingAttachment.attach(mesh, walls[end], points[base], points[base + 1],
                            points[base + 2]);
            if (anchors[anchor] < 0) {
                throw new IllegalArgumentException(NODE_ID + " " + label + ": the bundle carries "
                        + "no wall " + wall + " for its " + (end == 0 ? "first" : "last")
                        + " end to sit on");
            }
        }
        if (stretch) {
            int[] frame = SurfaceWaypoints.snap(metric.nearestVertex, stretchPoints,
                    STRETCH_POINTS);
            for (int end = 0; end < 2; end++) {
                int base = SurfaceWaypoints.COORDINATES_PER_WAYPOINT * (1 + end);
                frame[1 + end] = RingAttachment.attach(mesh, walls[end], stretchPoints[base],
                        stretchPoints[base + 1], stretchPoints[base + 2]);
            }
            publish(ctx, bundle, label, SurfaceSpline.ringStretch(SurfaceGeodesics.over(metric),
                    anchors, frame, walls[0], walls[1]));
            return;
        }
        RingSegmentMode mode = RingSegmentMode.named(ctx.getInput(MODE.name, String.class));
        SurfaceCreases creases = ctx.getInput(CREASES.name, SurfaceCreases.class);
        if (mode == RingSegmentMode.CREASE && (creases == null || creases.sourceMesh != mesh)) {
            throw new IllegalArgumentException(NODE_ID + " " + label + " runs in crease mode but "
                    + "has no creases of its mesh; wire creases = surface_creases(geometry=...) on "
                    + "the surface it bisects and pass creases=creases.creases");
        }
        publish(ctx, bundle, label, SurfaceSpline.bisector(SurfaceGeodesics.over(metric),
                mode == RingSegmentMode.CREASE ? creases : null, anchors, walls[0], walls[1]));
    }

    /**
     * Emit a traced bisector: its edge path under its label, its curve and its spline, and the
     * per-edge selection.
     *
     * @param ctx      the evaluation the outputs go to
     * @param bundle   the bundle it was traced on
     * @param label    its edge-marks label
     * @param bisector the traced bisector
     * @throws IllegalArgumentException when its edge path marks nothing
     */
    private static void publish(NodeContext ctx, GeometryBundle bundle, String label,
            SurfaceSpline bisector) {
        MeshTopology mesh = bundle.mesh();
        if (bisector.marks.markedEdgeCount == 0) {
            throw new IllegalArgumentException(NODE_ID + " " + label + ": its snapped path does "
                    + "not join its two ends");
        }
        GeometryBundle out = SurfaceSpline.with(bundle.withSlot(CurveGeometry.SLOT,
                bisector.path.curve(bisector.path.positions)),
                label, bisector);
        boolean[] marks = bisector.marks.markedByEdgeId;
        ctx.setOutput(GEOMETRY.name, EdgeMarks.with(out, label, marks));
        boolean[] selection = new boolean[mesh.edgeCount()];
        for (int activeEdge = 0; activeEdge < selection.length; activeEdge++) {
            int edgeId = mesh.edgeIdAt(activeEdge);
            selection[activeEdge] = edgeId < marks.length && marks[edgeId];
        }
        ctx.setOutput(SELECTION.name, new BoolField(selection));
    }
}
