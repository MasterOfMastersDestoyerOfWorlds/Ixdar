package ixdar.nodegraph.nodes.picture;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.graphics.image.PictureCurves;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.field.FieldBroadcast;

/**
 * World curves drawn into the cells of a multiview template as SVG polylines, each through the
 * camera of the cell it is tagged with, as strokes stroke_sculpt lifts.
 */
@MeshNodeAnnotation(id = CurvesToCellsNode.NODE_ID, category = NodeCategory.CURVE_OPERATIONS)
public class CurvesToCellsNode implements MeshNode {

    public static final String NODE_ID = "curves_to_cells";

    public static final String SVG_END = "</svg>";

    public static final InputPort CURVES = new InputPort("curves", PortType.GEOMETRY_BUNDLE, null);

    public static final InputPort TEMPLATE = new InputPort("template", PortType.STRING, "");

    public static final InputPort KIND = new InputPort("kind", PortType.STRING, "");

    public static final InputPort RADIUS = new InputPort("radius", PortType.FLOAT, 0f, 0f, 10f);

    public static final InputPort PIECES = new InputPort("pieces", PortType.INT, 1, 1f, 64f);

    public static final OutputPort SVG = new OutputPort("svg", PortType.STRING);

    @Override
    public List<InputPort> inputs() {
        return List.of(CURVES, TEMPLATE, KIND, RADIUS, PIECES);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(SVG);
    }

    @Override
    public String description() {
        return "Draws world curves into a multiview template's cells as SVG polylines: each curve through the "
                + "camera of the cell its view attribute names, a closed one ending where it starts, split into "
                + "`pieces` polylines of equal length (a silhouette stroke drags the outline it reaches piece by "
                + "piece). The result goes to stroke_sculpt's or silhouette_hull's svg input.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                CURVES.name, "World curves tagged with their cell (" + PictureCurves.VIEW + "), from place_in_cell, "
                        + "fit_references or svg_to_curves.",
                TEMPLATE.name, "SVG text or path whose cells to draw in; empty uses the template the curves carry.",
                KIND.name, "data-kind written on every polyline (silhouette, ridge, valley), unless a curve's "
                        + StrokeSketch.SILHOUETTE_CURVE_ATTRIBUTE + " attribute says silhouette; empty writes none.",
                RADIUS.name, "data-radius in world units for curves without a " + StrokeSketch.RADIUS_CURVE_ATTRIBUTE
                        + " attribute; 0 writes none.",
                PIECES.name, "Polylines each curve is split into, by length.",
                SVG.name, "The template with the polylines added before its end; empty without curves.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = ctx.getInput(CURVES.name, GeometryBundle.class);
        CurveGeometry curves = bundle != null && bundle.slots().get(CurveGeometry.SLOT) instanceof CurveGeometry placed
                ? placed : null;
        String source = ctx.getInputValue(TEMPLATE.name) instanceof String text ? text.trim() : "";
        if (curves == null) {
            ctx.setOutput(SVG.name, "");
            return;
        }
        StrokeSketch template = source.isEmpty() ? StrokeSketch.templateOf(bundle, NODE_ID)
                : StrokeSketch.load(source, NODE_ID);
        String kind = ctx.getInputValue(KIND.name) instanceof String text ? text.trim() : "";
        float radius = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, RADIUS.name, RADIUS.defaultValue), 0f);
        int pieces = Math.max(1, FieldBroadcast.intScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, PIECES.name, PIECES.defaultValue), 1));
        StringBuilder shapes = new StringBuilder();
        for (int curve = 0; curve < curves.curveCount(); curve++) {
            int cell = (int) PictureCurves.value(curves, PictureCurves.VIEW, curve);
            if (cell < 0 || cell >= template.viewCameras.length || curves.pointCount(curve) < 2) {
                continue;
            }
            float[] pixels = template.cellPixels(cell, curves, curve);
            int points = pixels.length / 2 + (curves.closed[curve] ? 1 : 0);
            float[] drawn = Arrays.copyOf(pixels, points * 2);
            if (curves.closed[curve]) {
                drawn[points * 2 - 2] = pixels[0];
                drawn[points * 2 - 1] = pixels[1];
            }
            String curveKind = PictureCurves.value(curves, StrokeSketch.SILHOUETTE_CURVE_ATTRIBUTE, curve) > 0f
                    ? StrokeSketch.SILHOUETTE : kind;
            float curveRadius = PictureCurves.value(curves, StrokeSketch.RADIUS_CURVE_ATTRIBUTE, curve);
            curveRadius = curveRadius > 0f ? curveRadius : radius;
            float depth = PictureCurves.value(curves, StrokeSketch.DEPTH_CURVE_ATTRIBUTE, curve);
            String attributes = (curveKind.isEmpty() ? "" : "data-kind=\"" + curveKind + "\" ")
                    + (curveRadius > 0f ? String.format(Locale.ROOT, "data-radius=\"%s\" ", curveRadius) : "")
                    + (depth != 0f ? String.format(Locale.ROOT, "data-depth=\"%s\" ", depth) : "");
            float[] lengths = new float[points];
            for (int point = 1; point < points; point++) {
                lengths[point] = lengths[point - 1] + (float) Math.hypot(drawn[point * 2] - drawn[point * 2 - 2],
                        drawn[point * 2 + 1] - drawn[point * 2 - 1]);
            }
            int start = 0;
            int count = Math.min(pieces, points - 1);
            for (int piece = 1; piece <= count; piece++) {
                int end = start;
                while (end < points - 1 && (piece == count || lengths[end] < lengths[points - 1] * piece / count)) {
                    end++;
                }
                if (end > start) {
                    float[] span = Arrays.copyOfRange(drawn, start * 2, (end + 1) * 2);
                    shapes.append("<polyline ").append(attributes).append("fill=\"none\" stroke=\"blue\" points=\"")
                            .append(template.gridPoints(cell, span, end - start + 1)).append("\"/>\n");
                }
                start = end;
            }
        }
        String text = template.text;
        int end = text.lastIndexOf(SVG_END);
        ctx.setOutput(SVG.name, (end < 0 ? text : text.substring(0, end)) + shapes + SVG_END + "\n");
    }
}
