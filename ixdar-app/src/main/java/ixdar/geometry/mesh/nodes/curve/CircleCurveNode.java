package ixdar.geometry.mesh.nodes.curve;

import java.util.List;

import ixdar.annotations.meshnode.InputPort;
import ixdar.annotations.meshnode.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeContext;
import ixdar.annotations.meshnode.OutputPort;
import ixdar.annotations.meshnode.PortType;
import ixdar.annotations.meshnode.Vector3Value;
import ixdar.geometry.mesh.data.CurveGeometry;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;

/**
 * Circle curve primitive: generates a closed circular polyline as a CurveGeometry.
 * Points are evenly spaced on a circle in the XZ plane.
 * <p>
 * Useful as a direct rail input for {@code birail_loft} and {@code curve_sweep}
 * without needing to construct a thin mesh strip first.
 */
@MeshNodeAnnotation(id = "circle_curve")
public class CircleCurveNode implements MeshNode {

    private static final InputPort RADIUS = new InputPort("radius", PortType.FLOAT, 0.1f, 0.001f, 100f);
    private static final InputPort RESOLUTION = new InputPort("resolution", PortType.INT, 32, 2f, 256f);
    private static final InputPort CENTER = new InputPort("center", PortType.VECTOR3, new Vector3Value(0.0f, 0.0f, 0.0f));
    private static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public String description() {
        return "Generates a closed circular polyline in the XZ plane with configurable radius, resolution, and center position.";
    }

    @Override
    public java.util.Map<String, String> socketDocs() {
        return java.util.Map.of(
                "radius", "Distance from center to each polyline vertex. The circle has diameter 2r in the XZ plane (extent 2r on those axes).",
                "resolution", "Number of segments. 3 = triangle; 32 = near-circle; 128 = very smooth.",
                "center", "World-space position of the circle's center.",
                "geometry", "Closed curve geometry bundle (polyline in XZ plane)."
        );
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(RADIUS, RESOLUTION, CENTER);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public void evaluate(NodeContext ctx) {
        Number radiusInput = ctx.getInput("radius", Number.class);
        Number resolutionInput = ctx.getInput("resolution", Number.class);
        Object centerInput = ctx.getInput("center", Object.class);

        float radius = radiusInput == null ? 0.1f : radiusInput.floatValue();
        int resolution = resolutionInput == null ? 32 : Math.max(3, resolutionInput.intValue());

        float cx = 0.0f, cy = 0.0f, cz = 0.0f;
        if (centerInput instanceof Vector3Value v) {
            cx = v.x();
            cy = v.y();
            cz = v.z();
        }

        // Generate points evenly spaced on a circle in the XZ plane
        // x = cx + radius * cos(theta), y = cy, z = cz + radius * sin(theta)
        float[] positions = new float[resolution * 3];
        float twoPi = 2.0f * (float) Math.PI;
        
        for (int i = 0; i < resolution; i++) {
            float theta = (i / (float) resolution) * twoPi;
            int base = i * 3;
            positions[base] = cx + radius * (float) Math.cos(theta);
            positions[base + 1] = cy;
            positions[base + 2] = cz + radius * (float) Math.sin(theta);
        }

        // Close the curve by repeating the first point at the end
        // This allows downstream nodes to detect the curve as closed
        float[] closedPositions = new float[(resolution + 1) * 3];
        System.arraycopy(positions, 0, closedPositions, 0, resolution * 3);
        closedPositions[resolution * 3] = positions[0];
        closedPositions[resolution * 3 + 1] = positions[1];
        closedPositions[resolution * 3 + 2] = positions[2];

        CurveGeometry curve = CurveGeometry.singlePolyline(closedPositions);
        GeometryBundle curveBundle = GeometryBundle.empty().withSlot("_curve", curve);

        ctx.setOutput("geometry", curveBundle);
    }
}
