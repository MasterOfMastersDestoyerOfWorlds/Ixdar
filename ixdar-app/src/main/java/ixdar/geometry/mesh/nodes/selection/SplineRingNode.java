package ixdar.geometry.mesh.nodes.selection;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
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

    /** Edge-marks label the snapped edge cycle is written under unless {@code label} says else. */
    public static final String DEFAULT_MARK_LABEL = "spline_ring";

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE,
            null);
    public static final InputPort POINTS = new InputPort("points", PortType.STRING, "");
    public static final InputPort LABEL = new InputPort("label", PortType.STRING,
            DEFAULT_MARK_LABEL);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name,
            PortType.GEOMETRY_BUNDLE);
    public static final OutputPort SELECTION = new OutputPort("selection", PortType.BOOLEAN);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, POINTS, LABEL);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT, SELECTION);
    }

    @Override
    public String description() {
        return "Rings a surface with a closed cubic spline through authored anchor points: each "
                + "anchor snaps to its nearest vertex, consecutive anchors are joined by cubic "
                + "segments traced with b/Surf's recursive De Casteljau bisection over geodesic "
                + "midpoints (Mancinelli et al. 2021), and both tangent handles at an anchor lie "
                + "on one line so the ring turns smoothly rather than cornering. Emits the "
                + "polyline as curve geometry and the mesh edges nearest it as edge marks.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name,
                "Input: the triangle mesh to ring. Output: the same bundle carrying the spline as "
                        + "curve geometry and its snapped edge cycle in the edge-marks slot under "
                        + "`label`, ready for mark_edges consumers and delete_geometry cuts.",
                POINTS.name,
                "Anchor points as \"x,y,z; x,y,z; ...\", stored as positions and re-snapped on "
                        + "reload, so the same statement re-traces the same ring on another "
                        + "aligned scan. Three are the minimum; the ring tool writes the four or "
                        + "more its fit settled on.",
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
        String label = ctx.getInput(LABEL.name, String.class);
        if (label == null || label.isBlank()) {
            label = DEFAULT_MARK_LABEL;
        }
        float[] points = SurfaceWaypoints.parse(ctx.getInput(POINTS.name, String.class));
        int anchorCount = points.length / SurfaceWaypoints.COORDINATES_PER_WAYPOINT;
        SurfaceSpline spline = SurfaceSpline.through(mesh, points, anchorCount);

        GeometryBundle out = bundle.withSlot(CurveGeometry.SLOT,
                CurveGeometry.singlePolyline(spline.polyline));
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
