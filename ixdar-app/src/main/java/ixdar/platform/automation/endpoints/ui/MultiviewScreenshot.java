package ixdar.platform.automation.endpoints.ui;

import java.io.File;
import java.util.Base64;

import javax.imageio.ImageIO;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.Font;
import ixdar.platform.Platforms;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.mesh.MeshNodeViewerScene;

import java.awt.image.BufferedImage;

@AutomationRouteAnnotation(path = "ui/multiview", method = APIMethod.POST)
public class MultiviewScreenshot extends AutomationEndpoint implements AutomationRoute {

    /**
     * Capture 8 viewpoints (front/right/back/left/top/bottom/3-4 front-R/3-4
     * front-L) and composite into a labeled 4x2 grid PNG.
     *
     * Uses separate runOnMainThread calls for each view: one to set orbit (scene
     * re-renders naturally on the next frame), then one to read pixels. This avoids
     * re-entrantly calling drawScene() from within processMainThreadCommands(),
     * which causes a hang.
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws Exception {
        String outputPath = body.has("path") ? body.get("path").getAsString() : "";
        boolean inline = body.has("inline") && body.get("inline").getAsBoolean();
        try {
            float[][] views = {
                    { (float) (Math.PI / 2), 0 }, // Front
                    { 0, 0 }, // Right
                    { (float) (-Math.PI / 2), 0 }, // Back
                    { (float) Math.PI, 0 }, // Left
                    { (float) (Math.PI / 2), 1.45f }, // Top
                    { (float) (Math.PI / 2), -1.45f }, // Bottom
                    { (float) (Math.PI / 4), 0.4f }, // 3/4 Front-R
                    { (float) ((3 * Math.PI) / 4), 0.4f }, // 3/4 Front-L
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
            float[] saved = new float[4]; // az, el, dist, viewDist
            runtime.runOnMainThread(() -> {
                if (!(canvas instanceof MeshNodeViewerScene mvs)) {
                    JsonObject err = new JsonObject();
                    err.addProperty("error", "MeshNodeViewerScene is not active");
                    return err;
                }
                OrbitMouseTrap orbit = mvs.getOrbitMouse();
                saved[0] = orbit.getAzimuth();
                saved[1] = orbit.getElevation();
                saved[2] = orbit.getDistance();
                saved[3] = Math.max(mvs.getMeshRadius() * 2.5f, 1.0f);
                return new JsonObject();
            });

            BufferedImage[] captures = new BufferedImage[8];
            int[] dims = new int[2];
            float viewDist = saved[3];

            for (int i = 0; i < 8; i++) {
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
                            w * h * 4);
                    BufferedImage img = new BufferedImage(
                            w,
                            h,
                            BufferedImage.TYPE_INT_RGB);
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            img.setRGB(x, y, pixels[(h - 1 - y) * w + x]);
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
                err.addProperty("error", "Framebuffer dimensions are 0");
                return err;
            }
            BufferedImage composite = new BufferedImage(
                    4 * cellW,
                    2 * cellH,
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = composite.createGraphics();
            Font labelFont = new Font(
                    Font.SANS_SERIF,
                    Font.BOLD,
                    Math.max(14, cellH / 20));
            g.setFont(labelFont);

            for (int i = 0; i < 8; i++) {
                int col = i % 4;
                int row = i / 4;
                int dx = col * cellW;
                int dy = row * cellH;
                if (captures[i] != null) {
                    g.drawImage(captures[i], dx, dy, null);
                }
                // Label with shadow
                int textX = dx + 8;
                int textY = dy + labelFont.getSize() + 4;
                g.setColor(new Color(0, 0, 0, 180));
                g.drawString(labels[i], textX + 1, textY + 1);
                g.setColor(Color.WHITE);
                g.drawString(labels[i], textX, textY);
            }
            g.dispose();

            // Write to disk
            File out;
            if (outputPath == null || outputPath.isBlank()) {
                out = new File(
                        "screenshots/automation",
                        "multiview-" + System.currentTimeMillis() + ".png");
            } else {
                out = new File(outputPath);
                if (!out.isAbsolute()) {
                    out = new File(System.getProperty("user.dir"), outputPath);
                }
            }
            File parent = out.getParentFile();
            if (parent != null)
                parent.mkdirs();
            ImageIO.write(composite, "PNG", out);

            byte[] pngBytes = imageBytes(composite);
            JsonObject result = new JsonObject();
            result.addProperty("path", out.getAbsolutePath());
            result.addProperty("width", 4 * cellW);
            result.addProperty("height", 2 * cellH);
            result.addProperty("views", 8);
            result.addProperty("sha256", sha256(pngBytes));
            if (inlineBase64) {
                result.addProperty(
                        "base64",
                        Base64.getEncoder().encodeToString(pngBytes));
            }
            return result;

        } catch (

        Exception e) {
            return writeError(exchange, 500, e.getMessage());
        }
    }
}
