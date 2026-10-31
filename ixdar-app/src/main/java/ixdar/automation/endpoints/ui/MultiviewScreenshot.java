package ixdar.automation.endpoints.ui;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.joml.Vector3f;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.graphics.image.PixelImage;
import ixdar.graphics.image.PngWriter;
import ixdar.platform.Platforms;
import ixdar.automation.AutomationEndpoint;
import ixdar.automation.AutomationPortFile;
import ixdar.automation.endpoints.scene.SwitchModel;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.model.SurfaceShading;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "ui/multiview", method = APIMethod.POST)
public class MultiviewScreenshot extends AutomationEndpoint implements AutomationRoute {
    public static final String PATH = "path";
    public static final String INLINE = "inline";
    public static final String SVG = "svg";
    public static final String CENTER = "center";
    public static final String FIELD_OF_VIEW = "fov";
    public static final String DSL = "dsl";
    public static final String OUTLINE = "outline";
    public static final int OUTLINE_COLOUR_TOLERANCE = 24;
    public static final float OUTLINE_SIMPLIFY_PIXELS = 1.5f;
    public static final int OUTLINE_MINIMUM_AREA = 64;
    public static final float THREE_QUARTER_ELEVATION_RAD = 0.4f;
    public static final int VIEW_COUNT = 8;
    public static final int QUARTER_TURN_DIVISOR = 4;
    public static final int THREE_QUARTER_TURN_NUMERATOR = 3;
    public static final int GRID_COLUMNS = 4;
    public static final int RGBA_BYTES_PER_PIXEL = 4;
    public static final int SAVED_ORBIT_FIELDS = 5;
    public static final int VIEW_ROLL_INDEX = 4;
    public static final int VIEW_DIST_INDEX = 3;
    public static final float[][] VIEW_AZIMUTH_ELEVATION_RADIANS = {
            { (float) (Math.PI / 2), 0 },
            { 0, 0 },
            { (float) (-Math.PI / 2), 0 },
            { (float) Math.PI, 0 },
            { (float) (Math.PI / 2), (float) (Math.PI / 2) },
            { (float) (Math.PI / 2), (float) (-Math.PI / 2) },
            { (float) (Math.PI / QUARTER_TURN_DIVISOR), THREE_QUARTER_ELEVATION_RAD },
            { (float) ((THREE_QUARTER_TURN_NUMERATOR * Math.PI) / QUARTER_TURN_DIVISOR),
                    THREE_QUARTER_ELEVATION_RAD },
    };
    public static final String[] VIEW_LABELS = {
            "Front",
            "Right",
            "Back",
            "Left",
            "Top",
            "Bottom",
            "3/4 Front-R",
            "3/4 Front-L",
    };

    /**
     * Capture 8 viewpoints framed on a selection into a 4x2 grid PNG, in {@code viewOrder}.
     * Each view sets the orbit, then reads the next frame in a second main-thread call: reading
     * in the same call re-enters {@code drawScene()} and hangs.
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws Exception {
        String outputPath = body.has(PATH) ? body.get(PATH).getAsString() : "";
        boolean inline = body.has(INLINE) && body.get(INLINE).getAsBoolean();
        String selection = Frame.selectionExpression(body);
        float padding = body.has(Frame.PADDING) ? body.get(Frame.PADDING).getAsFloat()
                : Camera3D.DEFAULT_FRAME_PADDING;
        SurfaceShading look;
        try {
            String name = body.has(ShadingSet.SHADING) ? body.get(ShadingSet.SHADING).getAsString() : "";
            look = name.isBlank() ? SurfaceShading.DEFAULT : SurfaceShading.named(name);
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }
        boolean headlight = body.has(ShadingSet.HEADLIGHT) && body.get(ShadingSet.HEADLIGHT).getAsBoolean();
        String dsl = body.has(DSL) ? body.get(DSL).getAsString().trim() : "";
        JsonObject reloaded = null;
        if (!dsl.isEmpty()) {
            // The model route's own load, so a DSL that fails here fails with the error ml reports.
            SwitchModel reload = new SwitchModel();
            reload.runtime = runtime;
            JsonObject request = new JsonObject();
            request.addProperty(SwitchModel.PATH, dsl);
            reloaded = reload.endpointHandler(request);
            if (!reloaded.get(OK).getAsBoolean()) {
                reloaded.addProperty(DSL, dsl);
                return reloaded;
            }
        }
        // The viewer's own look, put back once every view is read.
        SurfaceShading[] savedLook = new SurfaceShading[1];
        boolean[] savedHeadlight = new boolean[1];
        try {
            float[][] views = VIEW_AZIMUTH_ELEVATION_RADIANS;
            String[] labels = VIEW_LABELS;

            // Save the orbit, then aim it at the selection and fit its distance, on the render
            // thread; every view below keeps that centre and distance.
            float[] saved = new float[SAVED_ORBIT_FIELDS]; // az, el, dist, viewDist, roll
            Vector3f savedTarget = new Vector3f();
            JsonObject framing = runtime.runOnMainThread(() -> {
                if (!(runtime.canvas instanceof MeshNodeViewerScene mvs)) {
                    return failure("MeshNodeViewerScene is not active");
                }
                SelectionBounds selected = new SelectionBounds();
                if (selection == null || !selected.resolve(mvs, selection)) {
                    return failure(selection == null ? "a point needs a radius" : selected.error);
                }
                Camera3D orbit = mvs.camera;
                saved[0] = orbit.azimuth;
                saved[1] = orbit.elevation;
                saved[2] = orbit.distance;
                saved[VIEW_ROLL_INDEX] = orbit.roll;
                // The grid's views and the camera the drawing template records carry no roll.
                orbit.roll = 0f;
                savedTarget.set(orbit.orbitTarget);
                Vector3f center = new Vector3f();
                float radius = selected.sphere(center);
                saved[VIEW_DIST_INDEX] = orbit.frame(center, radius, padding,
                        mvs.camera.aspectRatio());
                JsonObject framed = new JsonObject();
                framed.addProperty(Frame.SELECTION, selected.name.isEmpty() ? selected.kind
                        : selected.kind + ":" + selected.name);
                framed.add(CENTER, runtime.vector3Array(center));
                framed.addProperty(Frame.RADIUS, radius);
                framed.addProperty(Frame.DISTANCE, saved[VIEW_DIST_INDEX]);
                framed.addProperty(ShadingSet.SHADING, look.name().toLowerCase(Locale.ROOT));
                framed.addProperty(FIELD_OF_VIEW, orbit.fov);
                framed.addProperty(StrokeSketch.ORTHOGRAPHIC, mvs.isOrthographic());
                savedLook[0] = mvs.surfaceShading;
                savedHeadlight[0] = mvs.headlight;
                mvs.setSurfaceLook(look, headlight);
                return framed;
            });
            if (framing.has(ERROR)) {
                return framing;
            }

            PixelImage[] captures = new PixelImage[VIEW_COUNT];
            int[] dims = new int[2];
            float viewDist = saved[VIEW_DIST_INDEX];

            for (int i = 0; i < VIEW_COUNT; i++) {
                final float az = views[i][0];
                final float el = views[i][1];
                final float dist = viewDist;

                // Call 1: set orbit — completes at end of frame N.
                // Frame N+1 will render with new orbit via SceneInputFrameUpdater.
                runtime.runOnMainThread(() -> {
                    if (runtime.canvas instanceof MeshNodeViewerScene mvs) {
                        mvs.camera.setOrbit(az, el, dist);
                    }
                    return new JsonObject();
                });

                // Call 2: runs at end of frame N+1, AFTER drawScene() + shader flush.
                // Reads the freshly rendered pixels with new orbit applied.
                final int viewIndex = i;
                runtime.runOnMainThread(() -> {
                    int w = Platforms.get().getFrameBufferWidth();
                    int h = Platforms.get().getFrameBufferHeight();
                    dims[0] = w;
                    dims[1] = h;
                    int[] pixels = Platforms.gl().readPixels(
                            0,
                            0,
                            w,
                            h,
                            Platforms.gl().RGBA(),
                            Platforms.gl().UNSIGNED_BYTE(),
                            w * h * RGBA_BYTES_PER_PIXEL);
                    PixelImage img = new PixelImage(w, h);
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            img.set(x, y, pixels[(h - 1 - y) * w + x] | PixelImage.OPAQUE);
                        }
                    }
                    captures[viewIndex] = img;
                    return new JsonObject();
                });
            }

            // Restore original orbit
            runtime.runOnMainThread(() -> {
                if (runtime.canvas instanceof MeshNodeViewerScene mvs) {
                    mvs.camera.moveTarget(savedTarget);
                    mvs.camera.roll = saved[VIEW_ROLL_INDEX];
                    mvs.camera.setOrbit(saved[0], saved[1], saved[2]);
                }
                return new JsonObject();
            });

            // Composite 4x2 grid on the HTTP thread (no GL needed)
            int cellW = dims[0];
            int cellH = dims[1];
            if (cellW == 0 || cellH == 0) {
                JsonObject err = new JsonObject();
                err.addProperty(ERROR, "Framebuffer dimensions are 0");
                return err;
            }
            PixelImage composite = new PixelImage(GRID_COLUMNS * cellW, 2 * cellH);
            int blankViews = 0;
            for (int i = 0; i < VIEW_COUNT; i++) {
                if (captures[i] == null) {
                    blankViews++;
                    continue;
                }
                composite.blit(captures[i], (i % GRID_COLUMNS) * cellW, (i / GRID_COLUMNS) * cellH);
                if (captures[i].isUniform()) {
                    blankViews++;
                }
            }

            // Write to disk
            File checkout = AutomationPortFile.checkoutRoot().toFile();
            File out;
            if (outputPath == null || outputPath.isBlank()) {
                out = new File(
                        new File(checkout, "screenshots/automation"),
                        "multiview-" + System.currentTimeMillis() + ".png");
            } else {
                out = new File(outputPath);
                if (!out.isAbsolute()) {
                    out = new File(checkout, outputPath);
                }
            }
            File parent = out.getParentFile();
            if (parent != null)
                parent.mkdirs();
            PngWriter.write(composite, out);

            byte[] pngBytes = imageBytes(composite);
            JsonObject result = framing;
            result.addProperty(OK, blankViews < VIEW_COUNT);
            result.addProperty(PATH, out.getAbsolutePath());
            result.addProperty("width", GRID_COLUMNS * cellW);
            result.addProperty("height", 2 * cellH);
            result.addProperty("views", VIEW_COUNT);
            result.addProperty("blankViews", blankViews);
            result.addProperty("cellWidth", cellW);
            result.addProperty("cellHeight", cellH);
            JsonArray viewOrder = new JsonArray();
            for (String label : labels) {
                viewOrder.add(label);
            }
            result.add("viewOrder", viewOrder);
            result.addProperty("sha256", sha256(pngBytes));
            if (reloaded != null) {
                result.addProperty(DSL, dsl);
                result.add(SwitchModel.SECONDS, reloaded.get(SwitchModel.SECONDS));
            }
            if (body.has(OUTLINE) && body.get(OUTLINE).getAsBoolean()) {
                // Composite pixels are the template's coordinates: multiview --svg writes its
                // viewBox as the composite's size.
                JsonArray outlines = new JsonArray();
                for (int view = 0; view < VIEW_COUNT; view++) {
                    if (captures[view] == null) {
                        continue;
                    }
                    int left = (view % GRID_COLUMNS) * cellW;
                    int top = (view / GRID_COLUMNS) * cellH;
                    int[] box = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
                    JsonArray polylines = new JsonArray();
                    for (int[] outline : captures[view].outlines(captures[view].get(0, 0),
                            OUTLINE_COLOUR_TOLERANCE, OUTLINE_SIMPLIFY_PIXELS, OUTLINE_MINIMUM_AREA)) {
                        StringBuilder points = new StringBuilder();
                        for (int index = 0; index < outline.length; index += 2) {
                            int x = outline[index] + left;
                            int y = outline[index + 1] + top;
                            points.append(index == 0 ? "" : " ").append(x).append(',').append(y);
                            box[0] = Math.min(box[0], x);
                            box[1] = Math.min(box[1], y);
                            box[2] = Math.max(box[2], x);
                            box[3] = Math.max(box[3], y);
                        }
                        polylines.add(points.toString());
                    }
                    JsonObject cell = new JsonObject();
                    cell.addProperty("cell", view);
                    cell.addProperty("view", VIEW_LABELS[view]);
                    if (!polylines.isEmpty()) {
                        JsonArray bounds = new JsonArray();
                        for (int value : box) {
                            bounds.add(value);
                        }
                        cell.add("box", bounds);
                    }
                    cell.add("polylines", polylines);
                    outlines.add(cell);
                }
                result.add(OUTLINE, outlines);
            }
            String svgPath = body.has(SVG) ? body.get(SVG).getAsString() : "";
            if (!svgPath.isBlank()) {
                // A blank drawing template whose root records this capture's camera, which the
                // stroke_sculpt node lifts the strokes drawn on it through.
                JsonArray center = framing.getAsJsonArray(CENTER);
                StringBuilder viewTable = new StringBuilder();
                StringBuilder layout = new StringBuilder();
                for (int view = 0; view < VIEW_COUNT; view++) {
                    viewTable.append(view == 0 ? "" : StrokeSketch.VIEW_SEPARATOR).append(String.format(Locale.ROOT,
                            "%.6f %.6f", VIEW_AZIMUTH_ELEVATION_RADIANS[view][0], VIEW_AZIMUTH_ELEVATION_RADIANS[view][1]));
                    layout.append(String.format(Locale.ROOT, "%n  cell %d (column %d, row %d, x %d-%d, y %d-%d): %s",
                            view, view % GRID_COLUMNS, view / GRID_COLUMNS, (view % GRID_COLUMNS) * cellW,
                            (view % GRID_COLUMNS + 1) * cellW, (view / GRID_COLUMNS) * cellH,
                            (view / GRID_COLUMNS + 1) * cellH, VIEW_LABELS[view]));
                }
                String template = String.format(Locale.ROOT,
                        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\"%n"
                                + "     %s=\"%.6f %.6f %.6f\" %s=\"%.6f\" %s=\"%.4f\" %s=\"%s\"%n"
                                + "     %s=\"%d\" %s=\"%d\" %s=\"%d\"%n     %s=\"%s\">%n"
                                + "<!-- Drawn over %s. Add one <polyline points=\"x,y ...\"> or <polygon> per stroke, "
                                + "all points of a stroke inside one cell, with data-strength=\"-1..1\", stroke-width "
                                + "(falloff width in pixels), optional data-kind=\"silhouette|ridge|valley\", "
                                + "data-mirror=\"x\", data-op=\"smooth\". Cells:%s%n-->%n</svg>%n",
                        GRID_COLUMNS * cellW, 2 * cellH, GRID_COLUMNS * cellW, 2 * cellH,
                        StrokeSketch.CENTER_ATTRIBUTE, center.get(0).getAsFloat(), center.get(1).getAsFloat(),
                        center.get(2).getAsFloat(), StrokeSketch.DISTANCE_ATTRIBUTE, viewDist,
                        StrokeSketch.FIELD_OF_VIEW_ATTRIBUTE, framing.get(FIELD_OF_VIEW).getAsFloat(),
                        StrokeSketch.PROJECTION_ATTRIBUTE, framing.get(StrokeSketch.ORTHOGRAPHIC).getAsBoolean()
                                ? StrokeSketch.ORTHOGRAPHIC : StrokeSketch.PERSPECTIVE,
                        StrokeSketch.CELL_WIDTH_ATTRIBUTE, cellW, StrokeSketch.CELL_HEIGHT_ATTRIBUTE, cellH,
                        StrokeSketch.COLUMNS_ATTRIBUTE, GRID_COLUMNS, StrokeSketch.VIEWS_ATTRIBUTE, viewTable,
                        out.getName(), layout);
                File svgFile = new File(svgPath);
                if (!svgFile.isAbsolute()) {
                    svgFile = new File(checkout, svgPath);
                }
                if (svgFile.getParentFile() != null) {
                    svgFile.getParentFile().mkdirs();
                }
                Files.write(svgFile.toPath(), template.getBytes(StandardCharsets.UTF_8));
                result.addProperty(SVG, svgFile.getAbsolutePath());
            }
            if (blankViews == VIEW_COUNT) {
                result.addProperty(ERROR, "every view rendered blank; the scene drew nothing");
            }
            if (inline) {
                result.addProperty(
                        "base64",
                        Base64.getEncoder().encodeToString(pngBytes));
            }
            return result;

        } catch (Exception e) {
            return failure(e.getMessage());
        } finally {
            runtime.runOnMainThread(() -> {
                if (savedLook[0] != null && runtime.canvas instanceof MeshNodeViewerScene mvs) {
                    mvs.setSurfaceLook(savedLook[0], savedHeadlight[0]);
                }
                return new JsonObject();
            });
        }
    }

    @Override
    public RouteDoc describe() {
        return Frame.selectionParams(RouteDoc.builder()
                .commandName("multiview")
                .description("Capture 8 orbit viewpoints, each framed on a selection (the whole "
                        + "mesh by default), and composite them into a 4x2 grid PNG.")
                .paramAliased(PATH, "out", RouteParamType.STRING, false, "",
                        "Output file path; empty writes under screenshots/automation/.", "/tmp/multiview.png")
                .param(INLINE, RouteParamType.BOOL, false, "false",
                        "Also return the composite PNG as base64 in the response.", "true")
                .param(ShadingSet.SHADING, RouteParamType.STRING, false, "default",
                        "Surface look for the capture only: default, clay or curvature; the "
                                + "viewer's own look is restored after.", "curvature")
                .param(ShadingSet.HEADLIGHT, RouteParamType.BOOL, false, String.valueOf(false),
                        "Clay and curvature key light from each view's camera.", String.valueOf(true))
                .param(SVG, RouteParamType.STRING, false, "",
                        "Also write a blank SVG drawing template here, its root recording this "
                                + "capture's camera, for a stroke_sculpt pass to be drawn on.",
                        "src/main/resources/dsl/skull/pass1.svg")
                .param(OUTLINE, RouteParamType.BOOL, false, String.valueOf(false),
                        "Also return each cell's silhouette of the mesh as closed polylines in the "
                                + "--svg template's coordinates, with each cell's bounding box.",
                        String.valueOf(true))
                .param(DSL, RouteParamType.STRING, false, "",
                        "Reload this .dsl file, as model --path does, before capturing; when it fails "
                                + "to load, nothing is captured and the load's error is returned.",
                        "ixdar-app/src/main/resources/dsl/stroke_sculpt_head.dsl"))
                .responseHint("{ok, selection, center, radius, distance, fov, orthographic, shading, path, "
                        + "width, height, views, blankViews, cellWidth, cellHeight, viewOrder, sha256, svg?, "
                        + "dsl?, seconds?, outline?: [{cell, view, box, polylines}], error?, base64?}")
                .waitSeconds(SwitchModel.WAIT_SECONDS)
                .build();
    }
}
