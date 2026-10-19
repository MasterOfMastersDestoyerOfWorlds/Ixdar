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

    public static final String END_SEPARATOR = ";";

    public static final InputPort GEOMETRY = InputPort.manifoldGeometry("geometry");
    public static final InputPort POINTS = new InputPort("points", PortType.STRING, "");
    public static final InputPort ENDS = new InputPort("ends", PortType.STRING, "");
    public static final InputPort LABEL = new InputPort("label", PortType.STRING, NODE_ID);
    public static final InputPort METRIC = SplineRingNode.METRIC;
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name,
            PortType.GEOMETRY_BUNDLE);
    public static final OutputPort SELECTION = new OutputPort("selection", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, POINTS, ENDS, LABEL, METRIC);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT, SELECTION);
    }

    @Override
    public String description() {
        return "A bisector: an open geodesic spline through authored anchors, traced like a "
                + "spline_ring, its first and last anchor attached to the walls named in ends, "
                + "rings or earlier bisectors on the bundle. It may cross other walls, and splits "
                + "every region it crosses. The ends are linked: each is re-attached to its "
                + "wall's nearest vertex on every evaluation, so editing the wall moves the end. "
                + "Emits the snapped edge path, trimmed to touch its end walls only at its two "
                + "ends, as edge marks the region flood takes as a wall.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name,
                "Input: the mesh carrying both end walls as edge marks under their labels. "
                        + "Output: the same mesh with the bisector's edge path added under `label` "
                        + "and its polyline as curve geometry.",
                POINTS.name,
                "Anchors as \"x,y,z; x,y,z; ...\" from one end to the other, at least two. The "
                        + "first and last attach to the nearest vertex of their wall, the rest "
                        + "snap to their nearest vertex.",
                ENDS.name,
                "The edge-mark labels of the walls the first and the last anchor sit on, as "
                        + "\"ring_61; ring_62\"; both may name the same wall.",
                LABEL.name,
                "Name the bisector's edge path is stored under in the edge-marks slot.",
                METRIC.name,
                "Required: the metric output of the surface_metric statement measured on this "
                        + "very mesh, the one every spline_ring of the chain shares. A bisector "
                        + "without one is refused.",
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
        String[] ends = String.valueOf(ctx.getInput(ENDS.name, String.class)).split(END_SEPARATOR);
        if (anchorCount < SurfaceWaypoints.OPEN_PATH_MINIMUM || ends.length != 2) {
            throw new IllegalArgumentException(NODE_ID + " " + label + " needs at least two "
                    + "points and two end walls written ends=\"ring_a; ring_b\"");
        }
        boolean[][] walls = new boolean[2][];
        int[] anchors = SurfaceWaypoints.snap(metric.nearestVertex, points, anchorCount);
        for (int end = 0; end < 2; end++) {
            String wall = ends[end].strip();
            walls[end] = EdgeMarks.bools(bundle, wall);
            int anchor = end == 0 ? 0 : anchorCount - 1;
            int base = SurfaceWaypoints.COORDINATES_PER_WAYPOINT * anchor;
            anchors[anchor] = walls[end] == null ? -1 : RingAttachment.attach(mesh, walls[end],
                    points[base], points[base + 1], points[base + 2]);
            if (anchors[anchor] < 0) {
                throw new IllegalArgumentException(NODE_ID + " " + label + ": the bundle carries "
                        + "no wall " + wall + " for its " + (end == 0 ? "first" : "last")
                        + " end to sit on");
            }
        }
        SurfaceSpline bisector = SurfaceSpline.bisector(SurfaceGeodesics.over(metric), anchors,
                walls[0], walls[1]);
        if (bisector.markedEdgeCount == 0) {
            throw new IllegalArgumentException(NODE_ID + " " + label + ": its snapped path does "
                    + "not join its two ends");
        }
        GeometryBundle out = bundle.withSlot(CurveGeometry.SLOT,
                CurveGeometry.singlePolyline(bisector.polyline));
        ctx.setOutput(GEOMETRY.name, EdgeMarks.with(out, label, bisector.markedByEdgeId));
        boolean[] selection = new boolean[mesh.edgeCount()];
        for (int activeEdge = 0; activeEdge < selection.length; activeEdge++) {
            int edgeId = mesh.edgeIdAt(activeEdge);
            selection[activeEdge] = edgeId < bisector.markedByEdgeId.length
                    && bisector.markedByEdgeId[edgeId];
        }
        ctx.setOutput(SELECTION.name, new BoolField(selection));
    }
}
