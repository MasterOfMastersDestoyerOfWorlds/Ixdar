package ixdar.geometry.mesh.nodes.selection;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.AuthoredSplineRing;
import ixdar.geometry.mesh.data.paths.RingSegmentMode;
import ixdar.geometry.mesh.data.paths.SurfaceCreases;
import ixdar.geometry.mesh.data.paths.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.nodes.api.BoolField;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;

/**
 * Rings a surface with a closed cubic spline through authored anchor points, traced by recursive
 * De Casteljau bisection with geodesic midpoints so the ring turns smoothly at every anchor.
 */
@MeshNodeAnnotation(id = "spline_ring")
public class SplineRingNode implements MeshNode {

    public static final String DEFAULT_MARK_LABEL = "spline_ring";

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE,
            null);
    public static final InputPort POINTS = new InputPort("points", PortType.STRING, "");
    public static final InputPort NORMAL = new InputPort("normal", PortType.STRING, "");
    public static final InputPort LABEL = new InputPort("label", PortType.STRING,
            DEFAULT_MARK_LABEL);
    public static final InputPort METRIC = new InputPort("metric", PortType.SURFACE_METRIC,
            null);
    public static final InputPort MODE = new InputPort("mode", PortType.STRING,
            RingSegmentMode.GEODESIC.dslName);
    public static final InputPort CREASES = new InputPort("creases", PortType.SURFACE_CREASES,
            null);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name,
            PortType.GEOMETRY_BUNDLE);
    public static final OutputPort SELECTION = new OutputPort("selection", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, POINTS, NORMAL, LABEL, METRIC, MODE, CREASES);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT, SELECTION);
    }

    @Override
    public String description() {
        return "Rings a surface with a closed cubic spline through authored anchor points, in the "
                + "order given: each anchor snaps to its nearest vertex, and supporting anchors "
                + "are fitted until every span stays within tolerance of its reference, the "
                + "geodesic to the next anchor, or for up to three anchors (or where geodesics "
                + "would make the ring cross itself) the loop of a plane fitted through them, "
                + "leaning toward the base normal. Segments are traced "
                + "with b/Surf's recursive De Casteljau bisection over geodesic midpoints "
                + "(Mancinelli et al. 2021), both tangent handles at an anchor on one line so the "
                + "ring turns smoothly. Emits the polyline as curve geometry and the mesh edges "
                + "nearest it as edge marks.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name,
                "Input: the mesh to ring, of any polygon sizes; quads and larger polygons are "
                        + "split only inside the geodesic engine. Output: the same mesh, "
                        + "unchanged, carrying the spline as curve geometry, its edge cycle, on "
                        + "the mesh's own edges, in the edge-marks slot under `label`, ready for "
                        + "mark_edges consumers and delete_geometry cuts, and the traced spline "
                        + "itself under `label` in the " + SurfaceSpline.SLOT + " slot, which "
                        + "extract_ring_region cuts along.",
                POINTS.name,
                "Authored anchor points as \"x,y,z; x,y,z; ...\" in ring order, each snapped to "
                        + "its nearest vertex. The first leads the ring. The supporting anchors "
                        + "between them "
                        + "are re-fitted on every evaluation, so only the points the user placed "
                        + "are stored. One is enough with a normal, three without.",
                NORMAL.name,
                "Base normal \"x,y,z\" of the ring's plane: one anchor takes it as is, two take "
                        + "the plane through both nearest it, three or more lean toward it only "
                        + "where they leave the plane undecided. Empty lets three anchors decide.",
                LABEL.name,
                "Name the snapped edge cycle is stored under in the edge-marks slot, so several "
                        + "spline rings can be kept on one bundle.",
                METRIC.name,
                "Required: the metric output of a surface_metric statement measured on this very "
                        + "mesh, shared by every ring of a chain so the surface is measured "
                        + "once. A ring without one is refused.",
                MODE.name,
                "How the ring runs between authored anchors: \"geodesic\" (the default) is the "
                        + "smooth spline; \"crease\" fits the spline to the cheapest path over "
                        + "the surface's crease cost that joins the anchors in order once round "
                        + "the part, so it hugs the groove they sit in.",
                CREASES.name,
                "The creases output of a surface_creases statement on this very mesh; required "
                        + "by the crease mode and ignored by the geodesic one.",
                SELECTION.name,
                "Per-edge BoolField, true on every edge of the conforming cycle nearest the "
                        + "traced spline.");
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
        // The half-edge form ring_candidates also passes on, so a ring reading a loaded file
        // marks the same edges as one chained after the proposed rings.
        HalfEdgeMesh surface = HalfEdgeMeshEngine.fromMeshTopology(mesh);
        if (surface != mesh) {
            bundle = bundle.withMesh(surface);
        }
        String label = ctx.getInput(LABEL.name, String.class);
        if (label == null || label.isBlank()) {
            label = DEFAULT_MARK_LABEL;
        }
        float[] points = SurfaceWaypoints.parse(ctx.getInput(POINTS.name, String.class));
        int anchorCount = points.length / SurfaceWaypoints.COORDINATES_PER_WAYPOINT;
        float[] normal = SurfaceWaypoints.parse(ctx.getInput(NORMAL.name, String.class));
        SurfaceMetric metric = ctx.getInput(METRIC.name, SurfaceMetric.class);
        if (metric == null) {
            throw new IllegalArgumentException("spline_ring " + label + " has no metric; wire "
                    + "surface = surface_metric(geometry=...) on the surface it rings and pass "
                    + "metric=surface.metric to every spline_ring downstream of it");
        }
        if (metric.sourceMesh != surface) {
            throw new IllegalArgumentException("spline_ring " + label + ": its metric was "
                    + "measured on another mesh; read the geometry from the surface_metric "
                    + "statement that measured it, or from a ring downstream of it");
        }
        int[] anchorVertexIds;
        try {
            anchorVertexIds = SurfaceWaypoints.snap(metric.nearestVertex, points, anchorCount);
        } catch (IllegalStateException unresolved) {
            // Passing the surface on unmarked lets every later ring report its own points too.
            ctx.reportFailure("spline_ring " + label + ":\n" + unresolved.getMessage());
            ctx.setOutput(GEOMETRY.name, bundle);
            ctx.setOutput(SELECTION.name, false);
            return;
        }
        AuthoredSplineRing ring = new AuthoredSplineRing(SurfaceGeodesics.over(metric));
        ring.mode = RingSegmentMode.named(ctx.getInput(MODE.name, String.class));
        ring.creases = ctx.getInput(CREASES.name, SurfaceCreases.class);
        if (ring.mode == RingSegmentMode.CREASE && ring.creases == null) {
            throw new IllegalArgumentException("spline_ring " + label + " runs in crease mode "
                    + "but has no creases; wire creases = surface_creases(geometry=...) on the "
                    + "surface it rings and pass creases=creases.creases");
        }
        boolean traced = ring.trace(anchorVertexIds, anchorCount,
                normal.length == SurfaceWaypoints.COORDINATES_PER_WAYPOINT ? normal : null,
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH);
        if (!traced) {
            throw new IllegalArgumentException("spline_ring " + label + ": " + ring.failure);
        }
        SurfaceSpline spline = SurfaceSpline.of(ring.tracer);

        GeometryBundle out = SurfaceSpline.with(bundle.withSlot(CurveGeometry.SLOT,
                CurveGeometry.singlePolyline(spline.polyline)), label, spline);
        ctx.setOutput(GEOMETRY.name, EdgeMarks.with(out, label, spline.markedByEdgeId));
        boolean[] selection = new boolean[surface.edgeCount()];
        for (int edgeId : spline.markedEdgeIds) {
            int activeEdge = surface.activeEdgeIndexOf(edgeId);
            if (activeEdge >= 0) {
                selection[activeEdge] = true;
            }
        }
        ctx.setOutput(SELECTION.name, new BoolField(selection));
    }
}
