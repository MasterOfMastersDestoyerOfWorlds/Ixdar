package ixdar.automation.endpoints.mesh;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.automation.AutomationPortFile;
import ixdar.automation.endpoints.ui.MultiviewScreenshot;
import ixdar.geometry.grid2d.PolylineSimplify;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.graphics.image.PixelImage;
import ixdar.graphics.image.ReferenceOutlines;
import ixdar.graphics.image.SubjectOutline;

/**
 * Writes several reference pictures' outlines into one orthographic outlines template at one shared
 * world scale, the form silhouette_hull reads, with no scene camera involved.
 */
@AutomationRouteAnnotation(path = "/mesh/trace-outlines", method = APIMethod.POST)
public class TraceOutlines extends AutomationEndpoint implements AutomationRoute {

    public static final String REFERENCE = "reference";

    public static final String OUT = "out";

    public static final String TEMPLATE = "template";

    public static final String HEIGHT = "height";

    public static final String FILL = "fill";

    public static final String CELL_SIZE = "cell_size";

    public static final String SIMPLIFY = "simplify";

    public static final String TOLERANCE = "tolerance";

    public static final String PICTURE_SEPARATOR = "=";

    public static final String EXAMPLE_OUTLINES = "tmp/skull/outlines.svg";

    public static final String DEFAULT_SIMPLIFY = "1.0";

    public static final float DEFAULT_HEIGHT = 2f;

    public static final float DEFAULT_FILL = 0.45f;

    public static final int DEFAULT_CELL_SIZE = 1280;

    public static final float DEFAULT_FIELD_OF_VIEW = 45f;

    public static final int DEFAULT_TOLERANCE = 40;

    public static final int COLUMNS = 4;

    /**
     * {@code POST /mesh/trace-outlines}: trace every picture, fit their scales, place each outline in
     * its cell and write the template.
     *
     * @param body the trace-outlines flags, {@code reference} a list of picture=cell
     * @return {@code {ok, svg, outlines:[{picture, cell, view, worldPerPicturePixel, points, aligned}],
     *         extents, distance}}, or a failure naming the bad input
     * @throws Exception when a file cannot be read or written
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws Exception {
        Path root = AutomationPortFile.checkoutRoot();
        float height = body.has(HEIGHT) ? body.get(HEIGHT).getAsFloat() : DEFAULT_HEIGHT;
        float[][] angles = MultiviewScreenshot.VIEW_AZIMUTH_ELEVATION_RADIANS;
        String[] labels = MultiviewScreenshot.VIEW_LABELS;
        String templateText = "";
        ReferenceOutlines traced = new ReferenceOutlines();
        traced.cellWidth = body.has(CELL_SIZE) ? body.get(CELL_SIZE).getAsInt() : DEFAULT_CELL_SIZE;
        traced.cellHeight = traced.cellWidth;
        int columns = COLUMNS;
        float fieldOfView = DEFAULT_FIELD_OF_VIEW;
        float unitsPerPixel = 1f;
        float fill = body.has(FILL) ? body.get(FILL).getAsFloat() : DEFAULT_FILL;
        float distance = height / (fill * 2f * (float) Math.tan(Math.toRadians(fieldOfView) / 2.0));
        if (body.has(TEMPLATE) && !body.get(TEMPLATE).getAsString().isEmpty()) {
            Path templatePath = existingFile("--template", body.get(TEMPLATE).getAsString());
            templateText = new String(Files.readAllBytes(templatePath), StandardCharsets.UTF_8);
            StrokeSketch template = StrokeSketch.parse(templateText, templatePath.toString());
            if (!template.orthographic) {
                return failure(templatePath + " is not orthographic; outlines at one shared scale need an "
                        + "orthographic template");
            }
            traced.cellWidth = template.cellWidth;
            traced.cellHeight = template.cellHeight;
            columns = template.columns;
            angles = new float[template.viewCameras.length][];
            for (int cell = 0; cell < angles.length; cell++) {
                angles[cell] = new float[] { template.viewCameras[cell][StrokeSketch.VIEW_AZIMUTH],
                    template.viewCameras[cell][StrokeSketch.VIEW_ELEVATION] };
            }
            distance = template.distance;
            fieldOfView = template.fieldOfViewDegrees;
            unitsPerPixel = 1f / template.svgScaleX;
        }
        traced.worldPerCellPixel = 2f * distance * (float) Math.tan(Math.toRadians(fieldOfView) / 2.0)
                / traced.cellHeight;

        JsonArray references = body.getAsJsonArray(REFERENCE);
        String[] pictures = new String[references.size()];
        int tolerance = body.has(TOLERANCE) ? body.get(TOLERANCE).getAsInt() : DEFAULT_TOLERANCE;
        for (int view = 0; view < pictures.length; view++) {
            String pair = references.get(view).getAsString();
            int split = pair.lastIndexOf(PICTURE_SEPARATOR);
            String cellName = pair.substring(split + 1);
            pictures[view] = split > 0 ? pair.substring(0, split) : "";
            int cell = -1;
            for (int candidate = 0; candidate < Math.min(labels.length, angles.length); candidate++) {
                cell = labels[candidate].equalsIgnoreCase(cellName) || String.valueOf(candidate).equals(cellName)
                        ? candidate : cell;
            }
            if (pictures[view].isEmpty() || cell < 0) {
                return failure("--reference " + pair + " is not picture=cell");
            }
            for (int earlier = 0; earlier < view; earlier++) {
                if (traced.cellByView[earlier] == cell) {
                    return failure("two pictures for cell " + labels[cell]);
                }
            }
            PixelImage picture = SubjectOutline.read(existingFile("--reference", pictures[view]));
            SubjectOutline subject = SubjectOutline.of(picture, SubjectOutline.borderMedian(picture), tolerance, 0f);
            if (subject == null) {
                return failure("no subject stands out in " + pictures[view]);
            }
            traced.add(subject, cell, angles[cell][0], angles[cell][1]);
        }
        String unfitted = traced.fitAxisViews(height);
        if (!unfitted.isEmpty()) {
            return failure(unfitted);
        }
        int uncarved = traced.fitObliqueViews();
        if (uncarved >= 0) {
            return failure("the axis views carve no common volume to fit " + pictures[uncarved] + " to");
        }

        // Write: the template's root and cell list, then one silhouette polyline per picture.
        int cellWidth = traced.cellWidth;
        int cellHeight = traced.cellHeight;
        StringBuilder svg = new StringBuilder();
        if (!templateText.isEmpty()) {
            svg.append(templateText.substring(0, templateText.lastIndexOf(TraceReference.SVG_END))
                    .replaceAll("<polyline\\b[^>]*/>\\n?|<!-- trace-(reference|outlines):.*?-->\\n?", ""));
        } else {
            StringBuilder layout = new StringBuilder();
            StringBuilder views = new StringBuilder();
            for (int cell = 0; cell < angles.length; cell++) {
                layout.append(String.format(Locale.ROOT, "%n  cell %d (column %d, row %d, x %d-%d, y %d-%d): %s", cell,
                        cell % columns, cell / columns, cell % columns * cellWidth, (cell % columns + 1) * cellWidth,
                        cell / columns * cellHeight, (cell / columns + 1) * cellHeight, labels[cell]));
                views.append(cell == 0 ? "" : StrokeSketch.VIEW_SEPARATOR).append(String.format(Locale.ROOT, "%.6f %.6f",
                        angles[cell][0], angles[cell][1]));
            }
            int rows = (angles.length + columns - 1) / columns;
            svg.append(String.format(Locale.ROOT, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" "
                    + "viewBox=\"0 0 %d %d\"%n     data-center=\"0.000000 0.000000 0.000000\" data-distance=\"%.6f\" "
                    + "data-fov=\"%.4f\" data-projection=\"orthographic\"%n     data-cell-width=\"%d\" "
                    + "data-cell-height=\"%d\" data-columns=\"%d\"%n     data-views=\"%s\">%n<!-- Outlines written by "
                    + "ixdar-cli trace-outlines at one shared scale, subject height %s. Cells:%s%n-->%n",
                    columns * cellWidth, rows * cellHeight, columns * cellWidth, rows * cellHeight, distance, fieldOfView,
                    cellWidth, cellHeight, columns, views, height, layout));
        }
        float simplify = body.has(SIMPLIFY) ? body.get(SIMPLIFY).getAsFloat() : Float.parseFloat(DEFAULT_SIMPLIFY);
        JsonArray placedRows = new JsonArray();
        for (int view = 0; view < traced.views; view++) {
            int cell = traced.cellByView[view];
            int left = cell % columns * cellWidth;
            int top = cell / columns * cellHeight;
            float[] outline = traced.placedOutline(view);
            for (int index = 0; index < outline.length; index += 2) {
                outline[index] = Math.max(left, Math.min(left + cellWidth - 1, outline[index] + left)) * unitsPerPixel;
                outline[index + 1] = Math.max(top, Math.min(top + cellHeight - 1, outline[index + 1] + top))
                        * unitsPerPixel;
            }
            float[] closed = PolylineSimplify.douglasPeucker(outline, simplify);
            svg.append(String.format(Locale.ROOT, "<!-- trace-outlines: %s into cell %d (%s), %.6f world units per "
                    + "picture pixel -->%n<polyline data-kind=\"silhouette\" fill=\"none\" stroke=\"blue\" points=\"",
                    Path.of(pictures[view]).getFileName(), cell, labels[cell], traced.scaleByView[view]));
            for (int index = 0; index < closed.length; index += 2) {
                svg.append(index == 0 ? "" : " ").append(String.format(Locale.ROOT, TraceReference.POINT_FORMAT,
                        closed[index], closed[index + 1]));
            }
            svg.append("\"/>\n");
            JsonObject row = new JsonObject();
            row.addProperty("picture", pictures[view]);
            row.addProperty("cell", cell);
            row.addProperty("view", labels[cell]);
            row.addProperty("worldPerPicturePixel", Math.round(traced.scaleByView[view] * 1e6) / 1e6);
            row.addProperty("points", closed.length / 2 - 1);
            row.addProperty("aligned", traced.measuredByView[view] != null);
            placedRows.add(row);
        }
        Path outPath = root.resolve(body.get(OUT).getAsString());
        if (outPath.getParent() != null) {
            Files.createDirectories(outPath.getParent());
        }
        Files.write(outPath, svg.append(TraceReference.SVG_END).append('\n').toString().getBytes(StandardCharsets.UTF_8));
        JsonObject result = success();
        result.addProperty("svg", outPath.toString());
        result.add("outlines", placedRows);
        JsonArray extentRow = new JsonArray();
        for (float extent : traced.extents) {
            extentRow.add(Math.round(extent * 1e4) / 1e4);
        }
        result.add("extents", extentRow);
        result.addProperty("distance", Math.round(distance * 1e6) / 1e6);
        return result;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("trace-outlines")
                .description("Write several reference pictures' outlines into one orthographic outlines template at "
                        + "one shared scale: the scales are fitted so the axis two views share agrees (height from "
                        + "front/back/side, width from front/top, depth from side/top), the subject's height being "
                        + "--height, and each outline is centred on its cell; a 3/4 view is fitted to the silhouette "
                        + "the axis views carve. Without --template the template is written too.")
                .param(REFERENCE, RouteParamType.STRING_LIST, true, "",
                        "Picture and cell as picture=cell, cell by name (Front, Right, 3/4 Front-R, ...) or index; "
                                + "repeat per view.", "tmp/references/ref_01_front.png=Front")
                .param(OUT, RouteParamType.STRING, true, "",
                        "SVG written: the template's root and cell list, then one silhouette polyline per picture.",
                        EXAMPLE_OUTLINES)
                .param(TEMPLATE, RouteParamType.STRING, false, "",
                        "Existing orthographic outlines template whose root (cameras, cell size) to keep; default a "
                                + "new orthographic one.", EXAMPLE_OUTLINES)
                .param(HEIGHT, RouteParamType.FLOAT, false, String.valueOf(DEFAULT_HEIGHT),
                        "World height the subject's outlines are scaled to.", "2.0")
                .param(FILL, RouteParamType.FLOAT, false, String.valueOf(DEFAULT_FILL),
                        "Share of a new template's cell height the subject's height fills.", "0.45")
                .param(CELL_SIZE, RouteParamType.INT, false, String.valueOf(DEFAULT_CELL_SIZE),
                        "Cell width and height in pixels of a new template.", "1280")
                .param(SIMPLIFY, RouteParamType.FLOAT, false, DEFAULT_SIMPLIFY,
                        "Largest distance, in template units, a dropped outline point may lie from the kept polyline.",
                        DEFAULT_SIMPLIFY)
                .param(TOLERANCE, RouteParamType.INT, false, String.valueOf(DEFAULT_TOLERANCE),
                        "Largest summed RGB difference from a picture's border colour still counted as background.",
                        "40")
                .responseHint("{ok, svg, outlines:[{picture, cell, view, worldPerPicturePixel, points, aligned}], "
                        + "extents, distance}")
                .build();
    }
}
