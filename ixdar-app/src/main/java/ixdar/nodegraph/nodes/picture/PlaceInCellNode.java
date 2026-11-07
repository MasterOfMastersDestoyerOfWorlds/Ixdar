package ixdar.nodegraph.nodes.picture;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.image.PictureCurves;
import ixdar.graphics.image.ReferenceOutlines;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.FieldBroadcast;

/**
 * Picture-plane curves placed in the world through one cell of a multiview template: scaled so the
 * subject's box is a world height at the cell's orbit centre, centred on it, and unprojected
 * through the cell's camera onto the plane through that centre.
 */
@MeshNodeAnnotation(id = PlaceInCellNode.NODE_ID, category = NodeCategory.CURVE_OPERATIONS)
public class PlaceInCellNode implements MeshNode {

    public static final String NODE_ID = "place_in_cell";

    public static final InputPort CURVES = new InputPort("curves", PortType.GEOMETRY_BUNDLE, null);

    public static final InputPort OUTLINE = new InputPort("outline", PortType.GEOMETRY_BUNDLE, null);

    public static final InputPort TEMPLATE = new InputPort("template", PortType.STRING, "");

    public static final InputPort CELL = new InputPort("cell", PortType.STRING, "Front");

    public static final InputPort HEIGHT = new InputPort("height", PortType.FLOAT, FitReferencesNode.DEFAULT_HEIGHT,
            0.001f, 100f);

    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(CURVES, OUTLINE, TEMPLATE, CELL, HEIGHT);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public String description() {
        return "Places a picture's curves (trace_outline, shading_edges) in the world through one cell of a "
                + "multiview template, such as a capture of the sculpt: scaled so the subject's box is `height` world "
                + "units tall at the cell's orbit centre, its box centre on that centre, and unprojected through the "
                + "cell's camera onto the plane through the centre facing it. curves_to_cells draws them back into "
                + "any template's cells.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                CURVES.name, "Curves in a picture's pixels.",
                OUTLINE.name, "The picture's trace_outline, whose box sets the scale and centre; empty uses the box "
                        + "the first curve carries, else the curves' own bounds.",
                TEMPLATE.name, "SVG text or path whose cell cameras place the curves, a multiview --svg capture.",
                CELL.name, "The template cell, by label or index.",
                HEIGHT.name, "World height the subject's box spans.",
                GEOMETRY.name, "The curves in the world, closed flags and attributes kept, " + PictureCurves.VIEW
                        + " set to the cell, carrying the template.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        CurveGeometry curves = curvesOf(ctx.getInput(CURVES.name, GeometryBundle.class));
        String source = ctx.getInputValue(TEMPLATE.name) instanceof String text ? text.trim() : "";
        if (curves == null || source.isEmpty()) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }
        StrokeSketch template = StrokeSketch.load(source, NODE_ID);
        String cellName = ctx.getInputValue(CELL.name) instanceof String text ? text.trim() : "";
        String[] labels = ReferenceOutlines.VIEW_LABELS;
        int cell = ReferenceOutlines.cellNamed(NODE_ID + ReferenceOutlines.PICTURE_SEPARATOR + cellName, labels);
        if (cell < 0 || cell >= template.viewCameras.length) {
            throw new IllegalArgumentException(NODE_ID + ": the template has no cell " + cellName);
        }
        float height = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, HEIGHT.name, HEIGHT.defaultValue), FitReferencesNode.DEFAULT_HEIGHT);
        CurveGeometry outline = curvesOf(ctx.getInput(OUTLINE.name, GeometryBundle.class));
        CurveGeometry boxed = outline != null ? outline : curves;
        float[] box = { PictureCurves.value(boxed, PictureCurves.BOX_X, 0), PictureCurves.value(boxed,
            PictureCurves.BOX_Y, 0), PictureCurves.value(boxed, PictureCurves.BOX_WIDTH, 0),
            PictureCurves.value(boxed, PictureCurves.BOX_HEIGHT, 0) };
        if (box[3] <= 0f) {
            float[] bounds = { Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
            for (int point = 0; point < boxed.pointCount(); point++) {
                for (int axis = 0; axis < 2; axis++) {
                    bounds[axis] = Math.min(bounds[axis], boxed.positions[point * CurveGeometry.XYZ + axis]);
                    bounds[axis + 2] = Math.max(bounds[axis + 2], boxed.positions[point * CurveGeometry.XYZ + axis]);
                }
            }
            box = new float[] { bounds[0], bounds[1], bounds[2] - bounds[0], bounds[3] - bounds[1] };
        }
        Camera3D camera = template.cellCamera(cell);
        Vector3f target = camera.orbitTarget;
        float worldPerPixel = (float) camera.worldPerPixel(target.x, target.y, target.z, template.cellHeight);
        float scale = height / worldPerPixel / Math.max(1e-6f, box[3]);
        float[] centre = new float[2];
        template.cellPixel(camera, new Vector3f(target), centre);
        float offsetX = centre[0] - scale * (box[0] + box[2] / 2f);
        float offsetY = centre[1] - scale * (box[1] + box[3] / 2f);
        float[] world = new float[curves.positions.length];
        Vector3f point = new Vector3f();
        for (int at = 0; at < curves.pointCount(); at++) {
            template.cellPlanePoint(camera, curves.positions[at * CurveGeometry.XYZ] * scale + offsetX,
                    curves.positions[at * CurveGeometry.XYZ + 1] * scale + offsetY, point);
            world[at * CurveGeometry.XYZ] = point.x;
            world[at * CurveGeometry.XYZ + 1] = point.y;
            world[at * CurveGeometry.XYZ + 2] = point.z;
        }
        Map<String, float[]> attributes = new TreeMap<>(curves.curveAttributes);
        float[] views = new float[curves.curveCount()];
        Arrays.fill(views, cell);
        attributes.put(PictureCurves.VIEW, views);
        ctx.setOutput(GEOMETRY.name, GeometryBundle.empty().withSlot(CurveGeometry.SLOT, new CurveGeometry(world,
                curves.curveOffsets, curves.closed, curves.pointAttributes, attributes))
                .withSlot(StrokeSketch.TEMPLATE_SLOT, template.text));
    }

    /**
     * A bundle's curves.
     *
     * @param bundle the bundle, or null
     * @return its curve geometry, or null when it has none
     */
    private static CurveGeometry curvesOf(GeometryBundle bundle) {
        return bundle != null && bundle.slots().get(CurveGeometry.SLOT) instanceof CurveGeometry curves
                && curves.curveCount() > 0 ? curves : null;
    }
}
