package ixdar.nodegraph.nodes.picture;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * Several reference pictures' outlines fitted to one shared world scale and placed in the cells of
 * one orthographic multiview template, as curves in the world and as the template SVG
 * silhouette_hull reads.
 */
@MeshNodeAnnotation(id = FitReferencesNode.NODE_ID, category = NodeCategory.CURVE_OPERATIONS)
public class FitReferencesNode implements MeshNode {

    public static final String NODE_ID = "fit_references";

    public static final float DEFAULT_HEIGHT = 2f;

    public static final float DEFAULT_FILL = 0.45f;

    public static final int DEFAULT_CELL_SIZE = 1280;

    public static final float DEFAULT_SIMPLIFY = 1f;

    public static final float FIELD_OF_VIEW = 45f;

    public static final int COLUMNS = 4;

    public static final String SVG_END = "</svg>";

    public static final InputPort OUTLINES = new InputPort("outlines", PortType.GEOMETRY_BUNDLE, null);

    public static final InputPort HEIGHT = new InputPort("height", PortType.FLOAT, DEFAULT_HEIGHT, 0.01f, 100f);

    public static final InputPort FILL = new InputPort("fill", PortType.FLOAT, DEFAULT_FILL, 0.05f, 1f);

    public static final InputPort CELL_SIZE = new InputPort("cell_size", PortType.INT, DEFAULT_CELL_SIZE, 64f, 4096f);

    public static final InputPort SIMPLIFY = new InputPort("simplify", PortType.FLOAT, DEFAULT_SIMPLIFY, 0f, 20f);

    public static final OutputPort GEOMETRY = new OutputPort("geometry", PortType.GEOMETRY_BUNDLE);

    public static final OutputPort SVG = new OutputPort("svg", PortType.STRING);

    @Override
    public List<InputPort> inputs() {
        return List.of(OUTLINES, HEIGHT, FILL, CELL_SIZE, SIMPLIFY);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY, SVG);
    }

    @Override
    public String description() {
        return "Fits several reference pictures' outlines (trace_outline, merged with merge_curves) to one shared "
                + "scale: the views measuring one world axis agree (height from front, back and sides, width from "
                + "front and top, depth from side and top), the subject's height is `height`, and a 3/4 view is "
                + "fitted to the silhouette the axis views carve. Each outline is centred in its cell of an "
                + "orthographic multiview template and placed in the world on the plane through the template's "
                + "centre facing its view.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                OUTLINES.name, "One closed outline per picture, each tagged with its view (trace_outline's view "
                        + "input); two pictures of one view are refused.",
                HEIGHT.name, "The subject's world height.",
                FILL.name, "Share of a cell's height the subject's height fills.",
                CELL_SIZE.name, "Cell width and height in pixels.",
                SIMPLIFY.name, "Largest distance in cell pixels a dropped outline point may lie from the kept one.",
                GEOMETRY.name, "The placed outlines as closed world curves with their " + PictureCurves.VIEW
                        + " attribute, the fit for reference_strokes, and the template for silhouette_hull's "
                        + "outlines input.",
                SVG.name, "The template SVG with one silhouette polyline per picture in its cell, the form "
                        + "silhouette_hull's svg input and outline-compare read.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle input = ctx.getInput(OUTLINES.name, GeometryBundle.class);
        CurveGeometry outlines = input == null ? null
                : input.slots().get(CurveGeometry.SLOT) instanceof CurveGeometry curves ? curves : null;
        if (outlines == null || outlines.curveCount() == 0) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            ctx.setOutput(SVG.name, "");
            return;
        }
        float height = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, HEIGHT.name, HEIGHT.defaultValue), DEFAULT_HEIGHT);
        float fill = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, FILL.name, FILL.defaultValue), DEFAULT_FILL);
        int cellSize = FieldBroadcast.intScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, CELL_SIZE.name, CELL_SIZE.defaultValue), DEFAULT_CELL_SIZE);
        float simplify = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, SIMPLIFY.name, SIMPLIFY.defaultValue), DEFAULT_SIMPLIFY);
        float[][] angles = ReferenceOutlines.VIEW_AZIMUTH_ELEVATION_RADIANS;
        String[] labels = ReferenceOutlines.VIEW_LABELS;
        ReferenceOutlines traced = new ReferenceOutlines();
        traced.cellWidth = cellSize;
        traced.cellHeight = cellSize;
        double halfView = Math.tan(Math.toRadians(FIELD_OF_VIEW) / 2.0);
        float distance = height / (fill * 2f * (float) halfView);
        traced.worldPerCellPixel = 2f * distance * (float) halfView / traced.cellHeight;
        for (int curve = 0; curve < outlines.curveCount(); curve++) {
            int cell = (int) PictureCurves.value(outlines, PictureCurves.VIEW, curve);
            if (cell < 0 || cell >= angles.length) {
                throw new IllegalArgumentException(NODE_ID + ": outline " + curve + " names no view; set "
                        + "trace_outline's view to the cell its picture shows");
            }
            for (int earlier = 0; earlier < traced.views; earlier++) {
                if (traced.cellByView[earlier] == cell) {
                    throw new IllegalArgumentException(NODE_ID + ": two pictures for cell " + labels[cell]);
                }
            }
            traced.add(PictureCurves.subject(outlines, curve), cell, angles[cell][0], angles[cell][1]);
        }
        String unfitted = traced.fitAxisViews(height);
        if (!unfitted.isEmpty()) {
            throw new IllegalArgumentException(NODE_ID + ": " + unfitted);
        }
        int uncarved = traced.fitObliqueViews();
        if (uncarved >= 0) {
            throw new IllegalArgumentException(NODE_ID + ": the axis views carve no common volume to fit the "
                    + labels[traced.cellByView[uncarved]] + " picture to");
        }

        // The template: an orthographic multiview root and cell list, then one silhouette per picture.
        int cellWidth = traced.cellWidth;
        int cellHeight = traced.cellHeight;
        int rows = (angles.length + COLUMNS - 1) / COLUMNS;
        String root = StrokeSketch.svgRoot(COLUMNS * cellWidth, rows * cellHeight, COLUMNS * cellWidth,
                rows * cellHeight, new float[3], distance, FIELD_OF_VIEW, true, cellWidth, cellHeight, COLUMNS,
                StrokeSketch.angleViews(angles))
                + String.format(Locale.ROOT, "<!-- Outlines placed by fit_references at one shared scale, subject "
                        + "height %s. Cells:%s%n-->%n", height,
                        StrokeSketch.cellLayout(labels, COLUMNS, cellWidth, cellHeight));
        StrokeSketch template = StrokeSketch.parse(root + SVG_END, NODE_ID);
        StringBuilder svg = new StringBuilder(root);
        float[] world = new float[0];
        int[] offsets = new int[traced.views + 1];
        boolean[] closed = new boolean[traced.views];
        float[] viewByCurve = new float[traced.views];
        Vector3f point = new Vector3f();
        for (int view = 0; view < traced.views; view++) {
            int cell = traced.cellByView[view];
            int left = cell % COLUMNS * cellWidth;
            int top = cell / COLUMNS * cellHeight;
            float[] outline = traced.placedOutline(view);
            for (int index = 0; index < outline.length; index += 2) {
                outline[index] = Math.max(left, Math.min(left + cellWidth - 1, outline[index] + left));
                outline[index + 1] = Math.max(top, Math.min(top + cellHeight - 1, outline[index + 1] + top));
            }
            CurveGeometry kept = CurveGeometry.planarLoop(outline).simplified(simplify);
            float[] drawn = kept.drawnPlanar(0);
            svg.append(String.format(Locale.ROOT, "<!-- fit_references: the %s picture into cell %d, %.6f world "
                    + "units per picture pixel -->%n<polyline data-kind=\"silhouette\" fill=\"none\" stroke=\"blue\" "
                    + "points=\"", labels[cell], cell, traced.scaleByView[view]));
            for (int index = 0; index < drawn.length; index += 2) {
                svg.append(index == 0 ? "" : " ").append(String.format(Locale.ROOT, "%.1f,%.1f", drawn[index],
                        drawn[index + 1]));
            }
            svg.append("\"/>\n");
            Camera3D camera = template.cellCamera(cell);
            int points = kept.pointCount();
            world = Arrays.copyOf(world, world.length + points * CurveGeometry.XYZ);
            for (int at = 0; at < points; at++) {
                template.cellPlanePoint(camera, kept.positions[at * CurveGeometry.XYZ] - left,
                        kept.positions[at * CurveGeometry.XYZ + 1] - top, point);
                int base = (offsets[view] + at) * CurveGeometry.XYZ;
                world[base] = point.x;
                world[base + 1] = point.y;
                world[base + 2] = point.z;
            }
            offsets[view + 1] = offsets[view] + points;
            closed[view] = true;
            viewByCurve[view] = cell;
        }
        svg.append(SVG_END).append('\n');
        CurveGeometry placed = new CurveGeometry(world, offsets, closed, Map.of(),
                Map.of(PictureCurves.VIEW, viewByCurve));
        ctx.setOutput(GEOMETRY.name, GeometryBundle.empty().withSlot(CurveGeometry.SLOT, placed)
                .withSlot(ReferenceOutlines.SLOT, traced).withSlot(StrokeSketch.TEMPLATE_SLOT, root + SVG_END));
        ctx.setOutput(SVG.name, svg.toString());
    }
}
