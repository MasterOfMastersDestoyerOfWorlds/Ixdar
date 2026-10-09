package ixdar.platform.automation.endpoints.ui;

import java.io.File;
import java.util.Base64;

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
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.automation.AutomationPortFile;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "ui/multiview", method = APIMethod.POST)
public class MultiviewScreenshot extends AutomationEndpoint implements AutomationRoute {
    public static final String PATH = "path";
    public static final String INLINE = "inline";
    public static final String ERROR = "error";
    public static final String OK = "ok";
    public static final float THREE_QUARTER_ELEVATION_RAD = 0.4f;
    public static final int VIEW_COUNT = 8;
    public static final int QUARTER_TURN_DIVISOR = 4;
    public static final int THREE_QUARTER_TURN_NUMERATOR = 3;
    public static final int GRID_COLUMNS = 4;
    public static final int RGBA_BYTES_PER_PIXEL = 4;
    public static final int SAVED_ORBIT_FIELDS = 4;
    public static final int VIEW_DIST_INDEX = 3;

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
                : OrbitMouseTrap.DEFAULT_FRAME_PADDING;
        try {
            float[][] views = {
                    { (float) (Math.PI / 2), 0 }, // Front
                    { 0, 0 }, // Right
                    { (float) (-Math.PI / 2), 0 }, // Back
                    { (float) Math.PI, 0 }, // Left
                    { (float) (Math.PI / 2), (float) (Math.PI / 2) }, // Top
                    { (float) (Math.PI / 2), (float) (-Math.PI / 2) }, // Bottom
                    { (float) (Math.PI / QUARTER_TURN_DIVISOR), THREE_QUARTER_ELEVATION_RAD }, // 3/4 Front-R
                    { (float) ((THREE_QUARTER_TURN_NUMERATOR * Math.PI) / QUARTER_TURN_DIVISOR), THREE_QUARTER_ELEVATION_RAD }, // 3/4 Front-L
            };
            String[] labels = {
                    "Front",
                    "Right",
                    "Back",
                    "Left",
                    "Top",
                    "Bottom",
                    "3/4 Front-R",
                    "3/4 Front-L",
            };

            // Save the orbit, then aim it at the selection and fit its distance, on the render
            // thread; every view below keeps that centre and distance.
            float[] saved = new float[SAVED_ORBIT_FIELDS]; // az, el, dist, viewDist
            Vector3f savedTarget = new Vector3f();
            JsonObject framing = runtime.runOnMainThread(() -> {
                JsonObject framed = new JsonObject();
                if (!(runtime.canvas instanceof MeshNodeViewerScene mvs)) {
                    framed.addProperty(ERROR, "MeshNodeViewerScene is not active");
                    return framed;
                }
                SelectionBounds selected = new SelectionBounds();
                if (selection == null || !selected.resolve(mvs, selection)) {
                    framed.addProperty(ERROR, selection == null ? "a point needs a radius"
                            : selected.error);
                    return framed;
                }
                OrbitMouseTrap orbit = mvs.getOrbitMouse();
                saved[0] = orbit.getAzimuth();
                saved[1] = orbit.getElevation();
                saved[2] = orbit.getDistance();
                orbit.getTarget(savedTarget);
                Vector3f center = new Vector3f();
                float radius = selected.sphere(center);
                saved[VIEW_DIST_INDEX] = orbit.frame(center, radius, padding,
                        mvs.camera.aspectRatio());
                framed.addProperty(Frame.SELECTION, selected.name.isEmpty() ? selected.kind
                        : selected.kind + ":" + selected.name);
                framed.add("center", runtime.vector3Array(center));
                framed.addProperty(Frame.RADIUS, radius);
                framed.addProperty(Frame.DISTANCE, saved[VIEW_DIST_INDEX]);
                return framed;
            });
            if (framing.has(ERROR)) {
                framing.addProperty(OK, false);
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
                        mvs.getOrbitMouse().setOrbit(az, el, dist);
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
                    mvs.getOrbitMouse().moveTarget(savedTarget);
                    mvs.getOrbitMouse().setOrbit(saved[0], saved[1], saved[2]);
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
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            err.addProperty(ERROR, e.getMessage());
            return err;
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
                        "Also return the composite PNG as base64 in the response.", "true"))
                .responseHint("{ok, selection, center, radius, distance, path, width, height, views, "
                        + "blankViews, cellWidth, cellHeight, viewOrder, sha256, error?, base64?}")
                .build();
    }
}
