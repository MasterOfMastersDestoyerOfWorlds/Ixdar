package ixdar.geometry.mesh.nodes.selection;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.AuthoredSplineRing;
import ixdar.geometry.mesh.data.paths.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
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
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name,
            PortType.GEOMETRY_BUNDLE);
    public static final OutputPort SELECTION = new OutputPort("selection", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, POINTS, NORMAL, LABEL);
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
        mesh = HalfEdgeMeshEngine.fromMeshTopology(mesh);
        if (mesh != bundle.mesh()) {
            bundle = bundle.withMesh(mesh);
        }
        String label = ctx.getInput(LABEL.name, String.class);
        if (label == null || label.isBlank()) {
            label = DEFAULT_MARK_LABEL;
        }
        float[] points = SurfaceWaypoints.parse(ctx.getInput(POINTS.name, String.class));
        int anchorCount = points.length / SurfaceWaypoints.COORDINATES_PER_WAYPOINT;
        float[] normal = SurfaceWaypoints.parse(ctx.getInput(NORMAL.name, String.class));
        AuthoredSplineRing ring = new AuthoredSplineRing(SurfaceGeodesics.over(mesh));
        boolean traced = ring.trace(SurfaceWaypoints.snap(mesh, points, anchorCount), anchorCount,
                normal.length == SurfaceWaypoints.COORDINATES_PER_WAYPOINT ? normal : null,
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH);
        if (!traced) {
            throw new IllegalArgumentException("spline_ring " + label + ": " + ring.failure);
        }
        SurfaceSpline spline = SurfaceSpline.of(ring.tracer);

        GeometryBundle out = SurfaceSpline.with(bundle.withSlot(CurveGeometry.SLOT,
                CurveGeometry.singlePolyline(spline.polyline)), label, spline);
        ctx.setOutput(GEOMETRY.name, EdgeMarks.with(out, label, spline.markedByEdgeId));
        boolean[] selection = new boolean[mesh.edgeCount()];
        for (int activeEdge = 0; activeEdge < selection.length; activeEdge++) {
            int edgeId = mesh.edgeIdAt(activeEdge);
            selection[activeEdge] =
                    edgeId < spline.markedByEdgeId.length && spline.markedByEdgeId[edgeId];
        }
        ctx.setOutput(SELECTION.name, new BoolField(selection));
    }
}
