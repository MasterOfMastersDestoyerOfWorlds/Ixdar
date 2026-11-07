package ixdar.nodegraph.nodes.picture;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import org.joml.Vector3f;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.graphics.cameras.Camera3D;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;

/**
 * A multiview SVG's drawn shapes as world curves: each shape's points placed on the plane through
 * its cell's orbit centre facing that cell's camera, its stroke attributes kept on the curve.
 */
@MeshNodeAnnotation(id = SvgToCurvesNode.NODE_ID, category = NodeCategory.CURVE_OPERATIONS)
public class SvgToCurvesNode implements MeshNode {

    public static final String NODE_ID = "svg_to_curves";

    public static final InputPort SVG = new InputPort("svg", PortType.STRING, "");

    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(SVG);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY);
    }

    @Override
    public String description() {
        return "Reads a multiview SVG template (multiview --svg, fit_references, or one drawn by hand) into world "
                + "curves: every polyline and polygon placed on the plane through its cell's orbit centre facing "
                + "that cell's camera, so drawn and traced outlines can be used in 3D or fed to silhouette_hull's "
                + "outlines.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                SVG.name, "SVG text, or a path resolved as stroke_sculpt resolves its svg.",
                GEOMETRY.name, "One world curve per shape, closed for a polygon or a polyline ending where it "
                        + "starts, with curve attributes " + StrokeSketch.VIEW_ATTRIBUTE + " (its cell), "
                        + StrokeSketch.DEPTH_CURVE_ATTRIBUTE + " and " + StrokeSketch.RADIUS_CURVE_ATTRIBUTE
                        + " (data-depth and data-radius, 0 when absent) and " + StrokeSketch.SILHOUETTE_CURVE_ATTRIBUTE
                        + " (1 for data-kind=\"silhouette\"), and the SVG as the template that sees them.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        String source = ctx.getInputValue(SVG.name) instanceof String text ? text.trim() : "";
        if (source.isEmpty()) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }
        StrokeSketch sketch = StrokeSketch.load(source, NODE_ID);
        float[] positions = new float[0];
        int[] offsets = { 0 };
        boolean[] closed = new boolean[0];
        float[][] attributes = new float[4][0];
        Vector3f point = new Vector3f();
        for (String shape : sketch.shapes) {
            int cell = sketch.shapeCell(shape);
            if (cell < 0) {
                continue;
            }
            float[] pixels = sketch.cellPixels(shape, cell);
            int points = pixels.length / 2;
            boolean repeated = points > 2 && pixels[0] == pixels[pixels.length - 2]
                    && pixels[1] == pixels[pixels.length - 1];
            boolean loop = repeated || shape.startsWith("<" + StrokeSketch.POLYGON);
            points -= repeated ? 1 : 0;
            Camera3D camera = sketch.cellCamera(cell);
            int start = offsets[offsets.length - 1];
            positions = Arrays.copyOf(positions, (start + points) * CurveGeometry.XYZ);
            for (int at = 0; at < points; at++) {
                sketch.cellPlanePoint(camera, pixels[at * 2], pixels[at * 2 + 1], point);
                positions[(start + at) * CurveGeometry.XYZ] = point.x;
                positions[(start + at) * CurveGeometry.XYZ + 1] = point.y;
                positions[(start + at) * CurveGeometry.XYZ + 2] = point.z;
            }
            offsets = Arrays.copyOf(offsets, offsets.length + 1);
            offsets[offsets.length - 1] = start + points;
            closed = Arrays.copyOf(closed, closed.length + 1);
            closed[closed.length - 1] = loop;
            Matcher kind = StrokeSketch.KIND_ATTRIBUTE.matcher(shape);
            float[] row = { cell, number(StrokeSketch.DEPTH_ATTRIBUTE.matcher(shape)),
                number(StrokeSketch.RADIUS_ATTRIBUTE.matcher(shape)),
                kind.find() && StrokeSketch.SILHOUETTE.equals(kind.group(1)) ? 1f : 0f };
            for (int field = 0; field < row.length; field++) {
                attributes[field] = Arrays.copyOf(attributes[field], closed.length);
                attributes[field][closed.length - 1] = row[field];
            }
        }
        if (closed.length == 0) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }
        CurveGeometry curves = new CurveGeometry(positions, offsets, closed, Map.of(), Map.of(
                StrokeSketch.VIEW_ATTRIBUTE, attributes[0], StrokeSketch.DEPTH_CURVE_ATTRIBUTE, attributes[1],
                StrokeSketch.RADIUS_CURVE_ATTRIBUTE, attributes[2], StrokeSketch.SILHOUETTE_CURVE_ATTRIBUTE,
                attributes[3]));
        ctx.setOutput(GEOMETRY.name, GeometryBundle.empty().withSlot(CurveGeometry.SLOT, curves)
                .withSlot(StrokeSketch.TEMPLATE_SLOT, sketch.text));
    }

    /**
     * An attribute's number, 0 when the shape does not carry it or it does not parse.
     *
     * @param attribute the attribute's matcher over the shape
     * @return the value
     */
    private static float number(Matcher attribute) {
        try {
            return attribute.find() ? Float.parseFloat(attribute.group(1)) : 0f;
        } catch (NumberFormatException unreadable) {
            return 0f;
        }
    }
}
