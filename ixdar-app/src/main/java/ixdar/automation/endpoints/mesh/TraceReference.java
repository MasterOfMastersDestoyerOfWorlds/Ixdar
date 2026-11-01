package ixdar.automation.endpoints.mesh;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.joml.Vector3f;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.automation.AutomationPortFile;
import ixdar.geometry.grid2d.PolygonScan;
import ixdar.geometry.grid2d.PolylineSimplify;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.image.PixelImage;
import ixdar.graphics.image.SubjectOutline;

/**
 * Writes a reference picture's silhouette into one cell of a {@code multiview --svg} drawing
 * template as silhouette polylines, placed from pixels alone: scaled to the sculpt's silhouette
 * height in that cell of the capture, or to a world height, and centred on the sculpt's silhouette.
 */
@AutomationRouteAnnotation(path = "/mesh/trace-reference", method = APIMethod.POST)
public class TraceReference extends AutomationEndpoint implements AutomationRoute {

    public static final String REFERENCE = "reference";

    public static final String TEMPLATE = "template";

    public static final String CELL = "cell";

    public static final String CAPTURE = "capture";

    public static final String OUT = "out";

    public static final String EDGES = "edges";

    public static final String RADIUS = "radius";

    public static final String PIECES = "pieces";

    public static final String SIMPLIFY = "simplify";

    public static final String TOLERANCE = "tolerance";

    public static final String HEIGHT = "height";

    public static final String SVG_END = "</svg>";

    public static final String POINT_FORMAT = "%.1f,%.1f";

    public static final Pattern CELL_LINE = Pattern.compile(
            "cell (\\d+) \\(column \\d+, row \\d+, x (\\d+)-(\\d+), y (\\d+)-(\\d+)\\): ([^\\n]+)");

    public static final Pattern DRAWN_OVER = Pattern.compile("Drawn over (\\S+?)\\.\\s");

    public static final int CAPTURE_TOLERANCE = 24;

    public static final int EDGE_MINIMUM_PIXELS = 25;

    public static final float EDGE_LOW = 30f;

    public static final float EDGE_HIGH = 90f;

    public static final int DEFAULT_PIECES = 4;

    public static final int DEFAULT_TOLERANCE = 40;

    public static final float DEFAULT_RADIUS = 0.15f;

    public static final float DEFAULT_SIMPLIFY = 2f;

    /**
     * {@code POST /mesh/trace-reference}: trace the picture's subject, place it in the cell, and write
     * the template with the polylines added, and the interior edges when asked.
     *
     * @param body the trace-reference flags
     * @return {@code {ok, svg, cell, view, capture, polylines, points, scale, sculptBox, referenceBox}},
     *         plus {@code edges, edgeLines} with edges, or a failure naming what is missing
     * @throws Exception when a file cannot be read or written
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws Exception {
        Path root = AutomationPortFile.checkoutRoot();
        Path templatePath = existingFile("--template", body.get(TEMPLATE).getAsString());
        String text = new String(Files.readAllBytes(templatePath), StandardCharsets.UTF_8);
        StrokeSketch sketch = StrokeSketch.parse(text, templatePath.toString());
        String cellName = body.get(CELL).getAsString();
        int[] rectangle = null;
        String label = "";
        JsonArray labels = new JsonArray();
        Matcher cells = CELL_LINE.matcher(text);
        while (cells.find()) {
            labels.add(cells.group(6).trim());
            if (rectangle == null && (cells.group(6).trim().equalsIgnoreCase(cellName) || cells.group(1).equals(cellName))) {
                rectangle = new int[] { Integer.parseInt(cells.group(1)), Integer.parseInt(cells.group(2)),
                    Integer.parseInt(cells.group(3)), Integer.parseInt(cells.group(4)), Integer.parseInt(cells.group(5)) };
                label = cells.group(6).trim();
            }
        }
        if (rectangle == null) {
            JsonObject missing = failure("no cell " + cellName + " in " + templatePath);
            missing.add("cells", labels);
            return missing;
        }
        int cell = rectangle[0];
        int left = rectangle[1];
        int top = rectangle[3];
        int cellWidth = rectangle[2] - left;
        int cellHeight = rectangle[4] - top;
        String capture = body.has(CAPTURE) ? body.get(CAPTURE).getAsString() : "";
        Matcher drawnOver = DRAWN_OVER.matcher(text);
        Path capturePath = !capture.isEmpty() ? MeshLoader.resolve(capture)
                : drawnOver.find() ? templatePath.resolveSibling(drawnOver.group(1)) : null;
        if (capturePath == null || !Files.isRegularFile(capturePath)) {
            return failure("no capture at " + capturePath + "; pass --capture, the multiview PNG the template was "
                    + "written with");
        }
        PixelImage cellPicture = SubjectOutline.read(capturePath).region(left, top, cellWidth, cellHeight);
        SubjectOutline sculpt = SubjectOutline.of(cellPicture, cellPicture.get(0, 0), CAPTURE_TOLERANCE, 0f);
        PixelImage picture = SubjectOutline.read(existingFile("--reference", body.get(REFERENCE).getAsString()));
        int tolerance = body.has(TOLERANCE) ? body.get(TOLERANCE).getAsInt() : DEFAULT_TOLERANCE;
        SubjectOutline subject = SubjectOutline.of(picture, SubjectOutline.borderMedian(picture), tolerance, 0f);
        if (sculpt == null || subject == null) {
            return failure("no subject stands out from the background in the "
                    + (sculpt == null ? "capture's cell" : REFERENCE));
        }

        // Fit: one scale matching heights (the sculpt's, or a world height at the cell's orbit
        // target), bounding-box centres together, in composite pixels.
        float height = body.has(HEIGHT) ? body.get(HEIGHT).getAsFloat() : 0f;
        Camera3D camera = sketch.cellCamera(cell);
        Vector3f target = camera.orbitTarget;
        float worldPerPixel = (float) camera.worldPerPixel(target.x, target.y, target.z, sketch.cellHeight);
        float targetHeight = height > 0f ? height / worldPerPixel : sculpt.box[3];
        float scale = targetHeight / subject.box[3];
        float offsetX = left + sculpt.box[0] + sculpt.box[2] / 2f - scale * (subject.box[0] + subject.box[2] / 2f);
        float offsetY = top + sculpt.box[1] + sculpt.box[3] / 2f - scale * (subject.box[1] + subject.box[3] / 2f);
        float unitsPerPixel = 1f / sketch.svgScaleX;
        float[] placed = new float[subject.outline.length];
        for (int index = 0; index < placed.length; index += 2) {
            placed[index] = Math.max(left, Math.min(left + cellWidth - 1, subject.outline[index] * scale + offsetX))
                    * unitsPerPixel;
            placed[index + 1] = Math.max(top, Math.min(top + cellHeight - 1, subject.outline[index + 1] * scale + offsetY))
                    * unitsPerPixel;
        }
        float simplify = body.has(SIMPLIFY) ? body.get(SIMPLIFY).getAsFloat() : DEFAULT_SIMPLIFY;
        float[] closed = PolylineSimplify.douglasPeucker(placed, simplify);
        int points = closed.length / 2;
        float[] lengths = new float[points];
        for (int point = 1; point < points; point++) {
            lengths[point] = lengths[point - 1] + (float) Math.hypot(closed[point * 2] - closed[point * 2 - 2],
                    closed[point * 2 + 1] - closed[point * 2 - 1]);
        }
        int pieces = Math.max(1, Math.min(body.has(PIECES) ? body.get(PIECES).getAsInt() : DEFAULT_PIECES, points - 1));
        float radius = body.has(RADIUS) ? body.get(RADIUS).getAsFloat() : DEFAULT_RADIUS;
        StringBuilder shapes = new StringBuilder(String.format(Locale.ROOT,
                "<!-- trace-reference: %s into cell %d (%s), scale %.4f, fitted to %s -->%n",
                Path.of(body.get(REFERENCE).getAsString()).getFileName(), cell, label, scale, height > 0f
                        ? String.format(Locale.ROOT, "the subject's height %s world units (%.0f px)", height, targetHeight)
                        : "the sculpt's silhouette height " + sculpt.box[3] + " px"));
        int start = 0;
        int polylines = 0;
        int written = 0;
        for (int piece = 1; piece <= pieces; piece++) {
            int end = start;
            while (end < points - 1 && (piece == pieces || lengths[end] < lengths[points - 1] * piece / pieces)) {
                end++;
            }
            if (end > start) {
                shapes.append(String.format(Locale.ROOT, "<polyline data-kind=\"silhouette\" data-radius=\"%s\" "
                        + "stroke-width=\"%.1f\" fill=\"none\" stroke=\"blue\" points=\"", radius,
                        radius / worldPerPixel * unitsPerPixel));
                for (int point = start; point <= end; point++) {
                    shapes.append(point == start ? "" : " ").append(String.format(Locale.ROOT, POINT_FORMAT,
                            closed[point * 2], closed[point * 2 + 1]));
                }
                shapes.append("\"/>\n");
                polylines++;
                written += end - start + 1;
            }
            start = end;
        }
        Path outPath = body.has(OUT) && !body.get(OUT).getAsString().isEmpty() ? root.resolve(body.get(OUT).getAsString())
                : templatePath;
        int svgEnd = text.lastIndexOf(SVG_END);
        Files.write(outPath, (text.substring(0, svgEnd) + shapes + text.substring(svgEnd)).getBytes(StandardCharsets.UTF_8));

        JsonObject result = success();
        result.addProperty("svg", outPath.toString());
        result.addProperty(CELL, cell);
        result.addProperty("view", label);
        result.addProperty(CAPTURE, capturePath.toString());
        result.addProperty("polylines", polylines);
        result.addProperty("points", written);
        result.addProperty("scale", Math.round(scale * 1e5) / 1e5);
        result.add("sculptBox", box(left + sculpt.box[0], top + sculpt.box[1], sculpt.box));
        result.add("referenceBox", box(subject.box[0], subject.box[1], subject.box));
        String edges = body.has(EDGES) ? body.get(EDGES).getAsString() : "";
        if (!edges.isEmpty()) {
            boolean[] inside = PolygonScan.covered(subject.outline, 0f, 0f, 1f, picture.width, picture.height);
            List<String> lines = new ArrayList<>();
            for (int[] edge : SubjectOutline.interiorEdges(picture, inside, EDGE_LOW, EDGE_HIGH)
                    .outlines(PixelImage.OPAQUE | PixelImage.RGB_MASK, 0, 0f, EDGE_MINIMUM_PIXELS)) {
                float[] line = new float[edge.length];
                for (int index = 0; index < edge.length; index += 2) {
                    line[index] = Math.max(left, Math.min(left + cellWidth - 1, edge[index] * scale + offsetX)) * unitsPerPixel;
                    line[index + 1] = Math.max(top, Math.min(top + cellHeight - 1, edge[index + 1] * scale + offsetY))
                            * unitsPerPixel;
                }
                StringBuilder polyline = new StringBuilder("<polyline fill=\"none\" stroke=\"#e08a1e\" stroke-width=\"2\" "
                        + "points=\"");
                float[] simplified = PolylineSimplify.douglasPeucker(line, simplify);
                for (int index = 0; index < simplified.length; index += 2) {
                    polyline.append(index == 0 ? "" : " ").append(String.format(Locale.ROOT, POINT_FORMAT,
                            simplified[index], simplified[index + 1]));
                }
                lines.add(polyline.append("\"/>\n").toString());
            }
            Path edgesPath = root.resolve(edges);
            Files.write(edgesPath, (text.substring(0, text.indexOf('>', text.indexOf("<svg")) + 1) + "\n"
                    + "<!-- interior edges, placed as in " + outPath.getFileName() + " -->\n" + String.join("", lines)
                    + SVG_END + "\n").getBytes(StandardCharsets.UTF_8));
            result.addProperty(EDGES, edgesPath.toString());
            result.addProperty("edgeLines", lines.size());
        }
        return result;
    }

    /**
     * A box as the {@code [left, top, right, bottom]} array the response carries.
     *
     * @param boxLeft left pixel
     * @param boxTop  top pixel
     * @param box     the subject's box, whose width and height are read
     * @return the array
     */
    private static JsonArray box(int boxLeft, int boxTop, int[] box) {
        JsonArray corners = new JsonArray();
        corners.add(boxLeft);
        corners.add(boxTop);
        corners.add(boxLeft + box[2]);
        corners.add(boxTop + box[3]);
        return corners;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("trace-reference")
                .description("Write a reference picture's silhouette into a template cell as silhouette polylines, "
                        + "fitted to the sculpt's silhouette in that cell of the capture, or with --height to a world "
                        + "height, so a size shortfall shows. The subject is whatever stands out from the picture's "
                        + "border colour; no camera of the reference is read.")
                .param(REFERENCE, RouteParamType.STRING, true, "", "Reference picture (PNG or JPEG) of the subject.",
                        "tmp/references/ref_04_right.png")
                .param(TEMPLATE, RouteParamType.STRING, true, "", "SVG drawing template written by multiview --svg.",
                        "tmp/shape.svg")
                .param(CELL, RouteParamType.STRING, true, "",
                        "Cell to trace into, by its view name (Front, Right, 3/4 Front-R, ...) or index.", "Right")
                .param(CAPTURE, RouteParamType.STRING, false, "",
                        "Multiview PNG the template was written with; default the PNG its comment names, next to it.",
                        "tmp/shape.png")
                .param(OUT, RouteParamType.STRING, false, "",
                        "SVG written; default the template itself, the polylines added before its end.", "tmp/out.svg")
                .param(EDGES, RouteParamType.STRING, false, "",
                        "Also write the subject's interior edges, placed the same way, to this SVG for viewing (it "
                                + "records no camera, so no node lifts it).", "tmp/edges.svg")
                .param(RADIUS, RouteParamType.FLOAT, false, String.valueOf(DEFAULT_RADIUS),
                        "data-radius of the silhouette polylines: how far, in world units, an outline is reached and "
                                + "dragged onto them.", "0.15")
                .param(PIECES, RouteParamType.INT, false, String.valueOf(DEFAULT_PIECES),
                        "Number of polylines the closed outline is split into.", "4")
                .param(SIMPLIFY, RouteParamType.FLOAT, false, String.valueOf(DEFAULT_SIMPLIFY),
                        "Largest distance, in template units, a dropped outline point may lie from the kept polyline.",
                        "2.0")
                .param(TOLERANCE, RouteParamType.INT, false, String.valueOf(DEFAULT_TOLERANCE),
                        "Largest summed RGB difference from the picture's border colour still counted as background.",
                        "40")
                .param(HEIGHT, RouteParamType.FLOAT, false, "0",
                        "World height of the subject in this view; 0 fits it to the sculpt's own silhouette height.",
                        "1.8")
                .responseHint("{ok, svg, cell, view, capture, polylines, points, scale, sculptBox, referenceBox, "
                        + "edges?, edgeLines?}")
                .build();
    }
}
