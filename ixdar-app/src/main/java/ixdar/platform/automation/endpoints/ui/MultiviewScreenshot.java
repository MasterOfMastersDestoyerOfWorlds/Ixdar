package ixdar.platform.automation.endpoints.ui;

import java.io.File;
import java.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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
    public static final float NUM_1_45 = 1.45f;
    public static final int NUM_4 = 4;
    public static final float NUM_0_4 = 0.4f;
    public static final int NUM_3 = 3;
    public static final float NUM_2_5 = 2.5f;
    public static final int NUM_8 = 8;

    /**
     * Capture 8 viewpoints into a 4x2 grid PNG, filled in the order {@code viewOrder} names.
     *
     * <p>Each view needs two {@code runOnMainThread} calls — set the orbit, then read the next
     * frame — because reading in the same call re-enters {@code drawScene()} and hangs.
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws Exception {
        String outputPath = body.has(PATH) ? body.get(PATH).getAsString() : "";
        boolean inline = body.has(INLINE) && body.get(INLINE).getAsBoolean();
        try {
            float[][] views = {
                    { (float) (Math.PI / 2), 0 }, // Front
                    { 0, 0 }, // Right
                    { (float) (-Math.PI / 2), 0 }, // Back
                    { (float) Math.PI, 0 }, // Left
                    { (float) (Math.PI / 2), NUM_1_45 }, // Top
                    { (float) (Math.PI / 2), -NUM_1_45 }, // Bottom
                    { (float) (Math.PI / NUM_4), NUM_0_4 }, // 3/4 Front-R
                    { (float) ((NUM_3 * Math.PI) / NUM_4), NUM_0_4 }, // 3/4 Front-L
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

            // Save original orbit and compute view distance on the render thread
            float[] saved = new float[NUM_4]; // az, el, dist, viewDist
            runtime.runOnMainThread(() -> {
                if (!(runtime.canvas instanceof MeshNodeViewerScene mvs)) {
                    JsonObject err = new JsonObject();
                    err.addProperty(ERROR, "MeshNodeViewerScene is not active");
                    return err;
                }
                OrbitMouseTrap orbit = mvs.getOrbitMouse();
                saved[0] = orbit.getAzimuth();
                saved[1] = orbit.getElevation();
                saved[2] = orbit.getDistance();
                saved[NUM_3] = Math.max(mvs.getMeshRadius() * NUM_2_5, 1.0f);
                return new JsonObject();
            });

            PixelImage[] captures = new PixelImage[NUM_8];
            int[] dims = new int[2];
            float viewDist = saved[NUM_3];

            for (int i = 0; i < NUM_8; i++) {
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
                            w * h * NUM_4);
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
            PixelImage composite = new PixelImage(NUM_4 * cellW, 2 * cellH);
            int blankViews = 0;
            for (int i = 0; i < NUM_8; i++) {
                if (captures[i] == null) {
                    blankViews++;
                    continue;
                }
                composite.blit(captures[i], (i % NUM_4) * cellW, (i / NUM_4) * cellH);
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
            JsonObject result = new JsonObject();
            result.addProperty(OK, blankViews < NUM_8);
            result.addProperty(PATH, out.getAbsolutePath());
            result.addProperty("width", NUM_4 * cellW);
            result.addProperty("height", 2 * cellH);
            result.addProperty("views", NUM_8);
            result.addProperty("blankViews", blankViews);
            result.addProperty("cellWidth", cellW);
            result.addProperty("cellHeight", cellH);
            JsonArray viewOrder = new JsonArray();
            for (String label : labels) {
                viewOrder.add(label);
            }
            result.add("viewOrder", viewOrder);
            result.addProperty("sha256", sha256(pngBytes));
            if (blankViews == NUM_8) {
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
        return RouteDoc.builder()
                .commandName("multiview")
                .description("Capture 8 orbit viewpoints and composite them into a 4x2 grid PNG.")
                .paramAliased(PATH, "out", RouteParamType.STRING, false, "",
                        "Output file path; empty writes under screenshots/automation/.", "/tmp/multiview.png")
                .param(INLINE, RouteParamType.BOOL, false, "false",
                        "Also return the composite PNG as base64 in the response.", "true")
                .responseHint("{ok, path, width, height, views, blankViews, cellWidth, cellHeight, "
                        + "viewOrder, sha256, error?, base64?}")
                .build();
    }
}
