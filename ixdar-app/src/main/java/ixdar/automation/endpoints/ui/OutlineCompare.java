package ixdar.automation.endpoints.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.automation.AutomationPortFile;
import ixdar.geometry.grid2d.OutlineOverlap;
import ixdar.geometry.grid2d.PolygonScan;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.graphics.image.PixelImage;
import ixdar.graphics.image.PngWriter;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.color.Palettes;
import ixdar.scenes.mesh.MeshNodeViewerScene;

/**
 * Compares the shown mesh's silhouettes with an outlines template cell by cell: captures a multiview
 * through the template's camera, maps each cell's silhouette into the template's pixels, and
 * measures it against the traced outline there.
 */
@AutomationRouteAnnotation(path = "ui/outline-compare", method = APIMethod.POST)
public class OutlineCompare extends AutomationEndpoint implements AutomationRoute {

    public static final String OUTLINES = "outlines";

    public static final String OUT = "out";

    public static final String CLAY = "clay";

    public static final String CAPTURE_FILE = "capture.png";

    public static final String OVERLAY_FILE = "overlay.png";

    public static final String DEFAULT_OUT = "tmp/outline-compare";

    public static final float ROUNDING = 100f;

    public static final String CELL = "cell";

    public static final String VIEW = "view";

    public static final int BOTH = 0;

    public static final int SHORT = 1;

    public static final int OVER = 2;

    public static final int NEITHER = 3;

    /**
     * {@code POST /ui/outline-compare}: capture through the template's camera and measure every
     * traced cell.
     *
     * @param body {@code outlines} template path, optional {@code out} directory
     * @return {@code {ok, cells:[{cell, view, iou, meanPixels, maxPixels, shortPixels, overPixels}],
     *         sumMeanPixels, capture, overlay, cameraMismatch}}, or the capture's failure
     * @throws Exception when a file cannot be read or written
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws Exception {
        Path root = AutomationPortFile.checkoutRoot();
        Path outlinesPath = existingFile("--outlines", body.get(OUTLINES).getAsString());
        StrokeSketch template = StrokeSketch.read(outlinesPath.toString());
        Path outDirectory = root.resolve(body.has(OUT) ? body.get(OUT).getAsString() : DEFAULT_OUT);
        Files.createDirectories(outDirectory);
        float[][] traces = template.cellOutlines();
        int cellWidth = template.cellWidth;
        int cellHeight = template.cellHeight;

        // Capture through the template's camera, the viewer in its projection.
        boolean[] wasOrthographic = new boolean[1];
        JsonObject projected = runtime.runOnMainThread(() -> {
            if (!(runtime.canvas instanceof MeshNodeViewerScene viewer)) {
                return failure("MeshNodeViewerScene is not active");
            }
            wasOrthographic[0] = viewer.isOrthographic();
            viewer.setOrthographic(template.orthographic);
            return success();
        });
        if (!projected.get(OK).getAsBoolean()) {
            return projected;
        }
        double half = Math.toRadians(template.fieldOfViewDegrees) / 2.0;
        JsonObject request = new JsonObject();
        Path capture = outDirectory.resolve(CAPTURE_FILE);
        request.addProperty(MultiviewScreenshot.PATH, capture.toString());
        request.addProperty(ShadingSet.SHADING, CLAY);
        request.addProperty(MultiviewScreenshot.OUTLINE, true);
        request.addProperty(MultiviewScreenshot.CAMERA, outlinesPath.toString());
        MultiviewScreenshot multiview = new MultiviewScreenshot();
        multiview.runtime = runtime;
        JsonObject captured = multiview.endpointHandler(request);
        runtime.runOnMainThread(() -> {
            if (runtime.canvas instanceof MeshNodeViewerScene viewer) {
                viewer.setOrthographic(wasOrthographic[0]);
            }
            return success();
        });
        if (!captured.get(OK).getAsBoolean()) {
            return captured;
        }
        int captureWidth = captured.get("cellWidth").getAsInt();
        int captureHeight = captured.get("cellHeight").getAsInt();
        JsonArray capturedCentre = captured.getAsJsonArray(MultiviewScreenshot.CENTER);
        float[] centreShift = { capturedCentre.get(0).getAsFloat() - template.center[0],
            capturedCentre.get(1).getAsFloat() - template.center[1], capturedCentre.get(2).getAsFloat() - template.center[2] };
        float capturedDistance = captured.get(Frame.DISTANCE).getAsFloat();
        float capturedFieldOfView = captured.get(MultiviewScreenshot.FIELD_OF_VIEW).getAsFloat();
        double templateScale = 2.0 * template.distance * Math.tan(half) / cellHeight;
        double captureScale = 2.0 * capturedDistance * Math.tan(Math.toRadians(capturedFieldOfView) / 2.0) / captureHeight;

        // The overlay in the paper palette: agreement muted, short in its error, over in its accent.
        Palette paper = Palettes.PAPER;
        int[] overlayPixels = new int[NEITHER + 1];
        Color[] roles = { paper.mutedText, paper.error, paper.accent, paper.background };
        for (int role = 0; role < roles.length; role++) {
            Vector3f channels = roles[role].toVector3f();
            overlayPixels[role] = PixelImage.OPAQUE
                    | Math.round(channels.x * PixelImage.CHANNEL_MASK) << PixelImage.RED_SHIFT
                    | Math.round(channels.y * PixelImage.CHANNEL_MASK) << PixelImage.CHANNEL_BITS
                    | Math.round(channels.z * PixelImage.CHANNEL_MASK);
        }
        JsonArray cells = new JsonArray();
        PixelImage overlay = null;
        int overlays = 0;
        float sum = 0f;
        int traced = 0;
        for (int cell = 0; cell < traces.length; cell++) {
            traced += traces[cell].length >= 2 * 3 ? 1 : 0;
        }
        for (JsonElement element : captured.getAsJsonArray(MultiviewScreenshot.OUTLINE)) {
            JsonObject cellOutline = element.getAsJsonObject();
            int cell = cellOutline.get(CELL).getAsInt();
            if (cell >= traces.length || traces[cell].length < 2 * 3) {
                continue;
            }
            Matrix4f view = template.cellCamera(cell).view;
            float shiftX = (float) ((centreShift[0] * view.m00() + centreShift[1] * view.m10() + centreShift[2] * view.m20())
                    / templateScale);
            float shiftY = (float) (-(centreShift[0] * view.m01() + centreShift[1] * view.m11() + centreShift[2] * view.m21())
                    / templateScale);
            int left = cell % MultiviewScreenshot.GRID_COLUMNS * captureWidth;
            int top = cell / MultiviewScreenshot.GRID_COLUMNS * captureHeight;
            boolean[] shape = new boolean[cellWidth * cellHeight];
            for (JsonElement points : cellOutline.getAsJsonArray("polylines")) {
                String[] pairs = points.getAsString().trim().split("\\s+");
                float[] polygon = new float[pairs.length * 2];
                for (int point = 0; point < pairs.length; point++) {
                    String[] xy = pairs[point].split(",");
                    polygon[point * 2] = (float) ((Float.parseFloat(xy[0]) - left - captureWidth / 2.0) * captureScale
                            / templateScale + cellWidth / 2.0 + shiftX);
                    polygon[point * 2 + 1] = (float) ((Float.parseFloat(xy[1]) - top - captureHeight / 2.0) * captureScale
                            / templateScale + cellHeight / 2.0 + shiftY);
                }
                boolean[] covered = PolygonScan.covered(polygon, 0f, 0f, 1f, cellWidth, cellHeight);
                for (int pixel = 0; pixel < shape.length; pixel++) {
                    shape[pixel] |= covered[pixel];
                }
            }
            boolean[] trace = PolygonScan.covered(traces[cell], 0f, 0f, 1f, cellWidth, cellHeight);
            OutlineOverlap overlap = new OutlineOverlap().measure(trace, shape, cellWidth, cellHeight);
            JsonObject row = new JsonObject();
            row.addProperty(CELL, cell);
            row.addProperty(VIEW, cellOutline.has(VIEW) ? cellOutline.get(VIEW).getAsString() : String.valueOf(cell));
            row.addProperty("iou", Math.round(overlap.intersectionOverUnion * 1e4) / 1e4);
            row.addProperty("meanPixels", Math.round(overlap.meanPixels * ROUNDING) / ROUNDING);
            row.addProperty("maxPixels", Math.round(overlap.maxPixels * ROUNDING) / ROUNDING);
            row.addProperty("shortPixels", overlap.shortPixels);
            row.addProperty("overPixels", overlap.overPixels);
            cells.add(row);
            sum += overlap.meanPixels;
            overlay = overlay == null ? new PixelImage(cellWidth * traced, cellHeight) : overlay;
            for (int pixel = 0; pixel < trace.length; pixel++) {
                int shown = trace[pixel] && shape[pixel] ? BOTH : trace[pixel] ? SHORT : shape[pixel] ? OVER : NEITHER;
                overlay.set(overlays * cellWidth + pixel % cellWidth, pixel / cellWidth, overlayPixels[shown]);
            }
            overlays++;
        }
        Path overlayPath = outDirectory.resolve(OVERLAY_FILE);
        if (overlay != null) {
            PngWriter.write(overlay, new File(overlayPath.toString()));
        }
        JsonObject result = success();
        result.add("cells", cells);
        result.addProperty("sumMeanPixels", Math.round(sum * ROUNDING) / ROUNDING);
        result.addProperty("capture", capture.toString());
        result.addProperty("overlay", overlayPath.toString());
        JsonObject mismatch = new JsonObject();
        mismatch.addProperty(Frame.DISTANCE, capturedDistance - template.distance);
        mismatch.addProperty(MultiviewScreenshot.FIELD_OF_VIEW, capturedFieldOfView - template.fieldOfViewDegrees);
        result.add("cameraMismatch", mismatch);
        return result;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("outline-compare")
                .description("Capture the shown mesh through an outlines template's camera and report, per traced "
                        + "cell, IoU, the mean and largest distance in template pixels from the traced outline to the "
                        + "mesh's silhouette, and the area the mesh falls short of (inside the trace only) or overdraws, "
                        + "with an overlay PNG (short red, over blue).")
                .param(OUTLINES, RouteParamType.STRING, true, "",
                        "Outlines template SVG (trace-outlines or multiview --svg) with closed outlines drawn in the "
                                + "cells to compare, its root recording the camera.", "dsl/skull3/outlines.svg")
                .param(OUT, RouteParamType.STRING, false, DEFAULT_OUT,
                        "Directory for the capture and the overlay.", DEFAULT_OUT)
                .responseHint("{ok, cells:[{cell, view, iou, meanPixels, maxPixels, shortPixels, overPixels}], "
                        + "sumMeanPixels, capture, overlay, cameraMismatch:{distance, fov}}")
                .build();
    }
}
