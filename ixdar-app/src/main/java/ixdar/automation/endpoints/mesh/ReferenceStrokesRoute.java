package ixdar.automation.endpoints.mesh;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
import ixdar.geometry.grid2d.PolygonScan;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.sculpt.ReferenceStrokes;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.graphics.image.PixelImage;
import ixdar.graphics.image.ReferenceOutlines;
import ixdar.graphics.image.ShadingEdges;
import ixdar.graphics.image.SubjectOutline;

/**
 * Writes reference pictures' shading as a stroke SVG a sculpt starts from: the pictures fitted as
 * trace-outlines fits them, every shading edge and dark region placed onto the base and drawn
 * through the sculpt's camera.
 */
@AutomationRouteAnnotation(path = "/mesh/reference-strokes", method = APIMethod.POST)
public class ReferenceStrokesRoute extends AutomationEndpoint implements AutomationRoute {

    public static final String REFERENCE = "reference";

    public static final String MESH = "mesh";

    public static final String TEMPLATE = "template";

    public static final String OUT = "out";

    public static final String PICTURES = "pictures";

    public static final String HEIGHT = "height";

    public static final String TOLERANCE = "tolerance";

    public static final String PICTURE_SUFFIX = "_strokes.svg";

    /**
     * {@code POST /mesh/reference-strokes}: fit the pictures, read each one's shading, place it on
     * the base and write the stroke SVG, plus each picture's strokes in its own pixels when asked.
     *
     * @param body the reference-strokes flags, {@code reference} a list of pictures
     * @return {@code {ok, svg, views:[{picture, view, cell, ridges, valleys, fills, light, overlay}], strokes}},
     *         or a failure naming the bad input
     * @throws Exception when a file cannot be read or written
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws Exception {
        Path root = AutomationPortFile.checkoutRoot();
        float height = body.has(HEIGHT) ? body.get(HEIGHT).getAsFloat() : TraceOutlines.DEFAULT_HEIGHT;
        int tolerance = body.has(TOLERANCE) ? body.get(TOLERANCE).getAsInt() : TraceOutlines.DEFAULT_TOLERANCE;
        String[] labels = MultiviewScreenshot.VIEW_LABELS;
        float[][] angles = MultiviewScreenshot.VIEW_AZIMUTH_ELEVATION_RADIANS;
        ReferenceOutlines traced = new ReferenceOutlines();
        traced.cellWidth = TraceOutlines.DEFAULT_CELL_SIZE;
        traced.cellHeight = TraceOutlines.DEFAULT_CELL_SIZE;
        // trace-outlines' default template: the subject's height fills its share of the cell.
        traced.worldPerCellPixel = height / (TraceOutlines.DEFAULT_FILL * traced.cellHeight);
        JsonArray references = body.getAsJsonArray(REFERENCE);
        String[] pictures = new String[references.size()];
        String[] names = new String[pictures.length];
        ShadingEdges[] shading = new ShadingEdges[pictures.length];
        for (int view = 0; view < pictures.length; view++) {
            String reference = references.get(view).getAsString();
            pictures[view] = ReferenceOutlines.pictureNamed(reference);
            int cell = ReferenceOutlines.cellNamed(reference, labels);
            if (cell < 0) {
                return failure("--reference " + reference + " names no view: give picture=cell, or name the picture "
                        + "by its view (ref_01_front.png)");
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
            names[view] = ReferenceOutlines.viewName(labels[cell]);
            shading[view] = ShadingEdges.of(picture, PolygonScan.covered(subject.outline, 0f, 0f, 1f, picture.width,
                    picture.height));
        }
        String unfitted = traced.fitAxisViews(height);
        if (!unfitted.isEmpty()) {
            return failure(unfitted);
        }
        int uncarved = traced.fitObliqueViews();
        if (uncarved >= 0) {
            return failure("the axis views carve no common volume to fit " + pictures[uncarved] + " to");
        }
        Path templatePath = existingFile("--template", body.get(TEMPLATE).getAsString());
        StrokeSketch template = StrokeSketch.parse(new String(Files.readAllBytes(templatePath), StandardCharsets.UTF_8),
                templatePath.toString());
        ReferenceStrokes strokes = ReferenceStrokes.of(traced, shading, names,
                MeshLoader.load(existingFile("--mesh", body.get(MESH).getAsString()).toString()), template);
        Path outPath = root.resolve(body.get(OUT).getAsString());
        if (outPath.getParent() != null) {
            Files.createDirectories(outPath.getParent());
        }
        Files.write(outPath, strokes.svg.getBytes(StandardCharsets.UTF_8));
        String pictureFolder = body.has(PICTURES) ? body.get(PICTURES).getAsString() : "";
        JsonArray rows = new JsonArray();
        for (int view = 0; view < pictures.length; view++) {
            JsonObject row = new JsonObject();
            row.addProperty("picture", pictures[view]);
            row.addProperty("view", labels[traced.cellByView[view]]);
            row.addProperty("cell", strokes.templateCellByView[view]);
            row.addProperty("ridges", strokes.ridgesByView[view]);
            row.addProperty("valleys", strokes.valleysByView[view]);
            row.addProperty("fills", strokes.fillsByView[view]);
            row.addProperty("edgesFound", shading[view].lines.size());
            row.addProperty("darkRegionsFound", shading[view].regions.size());
            row.addProperty("light", String.format(Locale.ROOT,"%.3f,%.3f", shading[view].light[0],
                    shading[view].light[1]));
            if (!pictureFolder.isEmpty()) {
                Path overlay = root.resolve(pictureFolder).resolve(names[view] + PICTURE_SUFFIX);
                Files.createDirectories(overlay.getParent());
                Files.write(overlay, strokes.pictureSvgByView[view].getBytes(StandardCharsets.UTF_8));
                row.addProperty("overlay", overlay.toString());
            }
            rows.add(row);
        }
        JsonObject result = success();
        result.addProperty("svg", outPath.toString());
        result.add("views", rows);
        result.addProperty("strokes", Arrays.stream(strokes.ridgesByView).sum() + Arrays.stream(strokes.valleysByView)
                .sum() + Arrays.stream(strokes.fillsByView).sum());
        return result;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("reference-strokes")
                .description("Turn reference pictures into the stroke SVG a sculpt starts from: the pictures are "
                        + "fitted as trace-outlines fits them, every shading edge of enough contrast inside the subject "
                        + "becomes a ridge or valley chain (signed by the light the subject's rim shows, or, lit along "
                        + "the view, a thin dark band a valley and a bright band or plain step a ridge; radius from "
                        + "the edge's width, depth from its contrast, under the terrace limit) and every hollow a bowl "
                        + "fill: a dark patch ringed by lit surface, or a dark blob grown out to the edges walling it "
                        + "in (an eye socket), about as deep as its mouth is wide when round and dark. Each is cast "
                        + "along its view onto the base where the base faces it within 60 degrees, axis pictures "
                        + "first and strongest lines first, a point dropped where an earlier mark already lies, and "
                        + "drawn through the template's camera in a <g id=\"edges_<view>\"> group, ready for "
                        + "sculpt-sync.")
                .param(REFERENCE, RouteParamType.STRING_LIST, true, "",
                        "Every reference picture, as for trace-outlines: picture=cell or a picture named by its view; "
                                + "repeat per view (the fit needs them all).", "tmp/references/ref_01_front.png")
                .param(MESH, RouteParamType.STRING, true, "",
                        "The base mesh the strokes land on, in the world trace-outlines fits (the base built from its "
                                + "outlines).", "tmp/base.ply")
                .param(TEMPLATE, RouteParamType.STRING, true, "",
                        "The sculpt's SVG whose root camera the strokes are drawn through: a multiview --svg capture "
                                + "of the base.", "tmp/edges/template.svg")
                .param(OUT, RouteParamType.STRING, true, "",
                        "SVG written: the template with one edges_<view> group per picture.", "tmp/edges/edges.svg")
                .param(PICTURES, RouteParamType.STRING, false, "",
                        "Folder for each picture's strokes in its own pixels (<view>" + PICTURE_SUFFIX + ", groups "
                                + "ridges, valleys, fills; every mark found drawn faint, data-hidden, under the pieces "
                                + "placed from it) to draw over it with strokes_overlay.py.", "tmp/edges")
                .param(HEIGHT, RouteParamType.FLOAT, false, String.valueOf(TraceOutlines.DEFAULT_HEIGHT),
                        "The subject's world height, as trace-outlines was given it.", "2.0")
                .param(TOLERANCE, RouteParamType.INT, false, String.valueOf(TraceOutlines.DEFAULT_TOLERANCE),
                        "Largest summed RGB difference from a picture's border colour still counted as background.",
                        "40")
                .responseHint("{ok, svg, views:[{picture, view, cell, ridges, valleys, fills, light, overlay}], strokes}")
                .build();
    }
}
