package ixdar.platform.automation;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.joml.Vector3f;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ixdar.audio.AudioSystem;
import ixdar.canvas.Canvas3D;
import ixdar.canvas.IxdarWindow;
import ixdar.game.City;
import ixdar.graphics.render.text.HyperString;
import ixdar.graphics.render.text.HyperWord;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.gui.ui.menu.MenuItem;
import ixdar.gui.ui.tools.RoutePlanningTool;
import ixdar.geometry.mesh.MeshCanonicalFingerprint;
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.data.MeshSkeletonComparator;
import ixdar.geometry.mesh.data.MeshSkeletonExtractor;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.point.IrregularQuadGrid;
import ixdar.platform.Platforms;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.platform.input.TradeMouseTrap;
import ixdar.scenes.anatomy.IrregularGridScene;
import ixdar.scenes.main.MainScene;
import ixdar.scenes.mesh.MeshNodeViewerScene;
import ixdar.scenes.trade.TradeScene;

public class AutomationRuntime {
    private static final long MAIN_THREAD_WAIT_MS = 60000L;
    private static final AutomationRuntime INSTANCE = new AutomationRuntime();

    private static class PendingMainThreadAction {
        Callable<JsonObject> action;
        CountDownLatch latch = new CountDownLatch(1);
        JsonObject result;
        Exception error;
    }

    private final AutomationRecorder recorder = new AutomationRecorder();
    private final AutomationReplayEngine replayEngine = new AutomationReplayEngine(this);
    private final Queue<PendingMainThreadAction> pendingMainThreadActions = new ConcurrentLinkedQueue<>();
    private AutomationApiServer server;
    private boolean started;
    private Canvas3D canvas;
    private volatile long renderThreadId = -1;

    public static AutomationRuntime get() {
        return INSTANCE;
    }

    public synchronized void start(Canvas3D canvas3D) {
        if (started) {
            if (canvas == null) {
                canvas = canvas3D;
            }
            return;
        }
        this.canvas = canvas3D;
        int port = Integer.getInteger("ixdar.automation.port", 47832);
        try {
            server = new AutomationApiServer(this, port);
            server.start();
            started = true;
            String message = "[Automation] Listening on http://127.0.0.1:" + port;
            Platforms.get().log(message);
        } catch (IOException e) {
            Platforms.get().log("Automation server failed to start: " + e.getMessage());
        }
    }

    public synchronized void stop() {
        if (!started) {
            return;
        }
        replayEngine.cancel();
        if (server != null) {
            server.stop();
            server = null;
        }
        started = false;
        canvas = null;
        renderThreadId = -1;
    }

    public AutomationRecorder recorder() {
        return recorder;
    }

    public AutomationReplayEngine replayEngine() {
        return replayEngine;
    }

    public JsonObject health() {
        JsonObject health = new JsonObject();
        health.addProperty("status", "ok");
        health.addProperty("timestamp", Instant.now().toString());
        health.addProperty("recording", recorder.isRecording());
        health.addProperty("replaying", replayEngine.isReplaying());
        health.addProperty("port", server == null ? -1 : server.port());
        return health;
    }

    public void recordRawKey(int key, int scancode, int action, int mods) {
        JsonObject payload = new JsonObject();
        payload.addProperty("key", key);
        payload.addProperty("scancode", scancode);
        payload.addProperty("action", action);
        payload.addProperty("mods", mods);
        recorder.recordRaw("key", payload);
    }

    public void recordRawChar(int codepoint) {
        JsonObject payload = new JsonObject();
        payload.addProperty("codepoint", codepoint);
        recorder.recordRaw("char", payload);
    }

    public void recordRawMouseButton(int button, int action, int mods, float x, float y) {
        JsonObject payload = new JsonObject();
        payload.addProperty("button", button);
        payload.addProperty("action", action);
        payload.addProperty("mods", mods);
        payload.addProperty("xPx", x);
        payload.addProperty("yPx", y);
        payload.addProperty("xNorm", normalizeX(x));
        payload.addProperty("yNorm", normalizeY(y));
        recorder.recordRaw("mouse_button", payload);
    }

    public void recordRawMouseMove(float x, float y) {
        JsonObject payload = new JsonObject();
        payload.addProperty("xPx", x);
        payload.addProperty("yPx", y);
        payload.addProperty("xNorm", normalizeX(x));
        payload.addProperty("yNorm", normalizeY(y));
        recorder.recordRaw("mouse_move", payload);
    }

    public void recordRawScroll(double yOffset) {
        JsonObject payload = new JsonObject();
        payload.addProperty("yOffset", yOffset);
        recorder.recordRaw("scroll", payload);
    }

    public void recordAbstractAction(String type, JsonObject payload) {
        recorder.recordAbstract(type, payload);
    }

    /** Map-based variant for callers that can't reference Gson directly (e.g. TeaVM-compiled code). */
    public void recordAbstractActionMap(String type, Map<String, Object> payload) {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Number) {
                json.addProperty(e.getKey(), (Number) v);
            } else if (v instanceof Boolean) {
                json.addProperty(e.getKey(), (Boolean) v);
            } else {
                json.addProperty(e.getKey(), String.valueOf(v));
            }
        }
        recorder.recordAbstract(type, json);
    }

    public JsonObject meshFingerprint() {
        try {
            return runOnMainThread(() -> {
                JsonObject result = new JsonObject();
                result.addProperty("algorithm", MeshCanonicalFingerprint.ALGORITHM_ID);
                if (!(canvas instanceof MeshNodeViewerScene)) {
                    result.addProperty("ok", false);
                    result.addProperty("error", "MeshNodeViewerScene is not active");
                    return result;
                }
                MeshNodeViewerScene mvs = (MeshNodeViewerScene) canvas;
                MeshTopology mesh = mvs.getMesh();
                if (mesh == null) {
                    result.addProperty("ok", false);
                    result.addProperty("error", "Mesh not loaded yet");
                    return result;
                }
                result.addProperty("ok", true);
                result.addProperty("sha256", MeshCanonicalFingerprint.sha256Hex(mesh));
                result.addProperty("vertexCount", mesh.vertexCount());
                result.addProperty("faceCount", mesh.faceCount());
                result.addProperty("triangleCount", MeshCanonicalFingerprint.triangleCount(mesh));
                return result;
            });
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", e.getMessage() == null ? "" : e.getMessage());
            return err;
        }
    }

    public JsonObject meshCompare(String referencePath, String distanceTypeStr, float scale, boolean normalize) {
        try {
            return runOnMainThread(() -> {
                JsonObject result = new JsonObject();
                result.addProperty("ok", false);

                if (!(canvas instanceof MeshNodeViewerScene)) {
                    result.addProperty("error", "MeshNodeViewerScene is not active");
                    return result;
                }
                MeshNodeViewerScene mvs = (MeshNodeViewerScene) canvas;
                MeshTopology currentMeshTopology = mvs.getMesh();
                if (currentMeshTopology == null) {
                    result.addProperty("error", "Mesh not loaded yet");
                    return result;
                }

                // Convert current mesh to ArrayMesh
                ixdar.geometry.mesh.data.ArrayMesh currentMesh;
                if (currentMeshTopology instanceof ixdar.geometry.mesh.data.ArrayMesh) {
                    currentMesh = (ixdar.geometry.mesh.data.ArrayMesh) currentMeshTopology;
                } else {
                    currentMesh = ixdar.geometry.mesh.data.ArrayMeshEngine.fromUniformMeshTopology(currentMeshTopology);
                }

                // Load reference mesh
                ixdar.geometry.mesh.data.ArrayMesh referenceMesh;
                try {
                    referenceMesh = ixdar.geometry.mesh.data.MeshLoader.load(referencePath);
                } catch (Exception e) {
                    result.addProperty("error", "Failed to load reference mesh: " + e.getMessage());
                    return result;
                }

                if (referenceMesh.vertexCount() == 0) {
                    result.addProperty("error", "Reference mesh is empty");
                    return result;
                }

                // Normalize: center both meshes to origin, scale to unit bounding box diagonal
                if (normalize) {
                    normalizeMeshPositions(currentMesh);
                    normalizeMeshPositions(referenceMesh);
                }

                // Compute distance metrics
                ixdar.geometry.mesh.data.MeshDistance.DistanceType distanceType = parseDistanceType(distanceTypeStr);

                float effectiveScale = (Float.isNaN(scale) || scale <= 0f) ? 1.0f : scale;
                ixdar.geometry.mesh.data.MeshDistance.MeshMetrics metrics =
                    ixdar.geometry.mesh.data.MeshDistance.computeAllMetrics(currentMesh, referenceMesh, effectiveScale);

                result.addProperty("ok", true);
                result.addProperty("current_vertices", currentMesh.vertexCount());
                result.addProperty("reference_vertices", referenceMesh.vertexCount());
                result.addProperty("hausdorff_distance", metrics.hausdorffDistance);
                result.addProperty("chamfer_distance", metrics.chamferDistance);
                result.addProperty("similarity_score", metrics.similarityScore);
                result.addProperty("distance_type", distanceTypeStr);
                result.addProperty("scale", effectiveScale);
                result.addProperty("normalized", normalize);

                return result;
            });
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", e.getMessage() == null ? "" : e.getMessage());
            return err;
        }
    }

    /**
     * Centers a mesh to the origin and scales it so its bounding box diagonal is 1.0.
     * Modifies vertex positions in place.
     */
    private static void normalizeMeshPositions(ixdar.geometry.mesh.data.ArrayMesh mesh) {
        int n = mesh.vertexCount();
        if (n == 0) return;

        float[] pos = mesh.copyPositions();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        for (int i = 0; i < n; i++) {
            int o = i * 3;
            minX = Math.min(minX, pos[o]);     maxX = Math.max(maxX, pos[o]);
            minY = Math.min(minY, pos[o + 1]); maxY = Math.max(maxY, pos[o + 1]);
            minZ = Math.min(minZ, pos[o + 2]); maxZ = Math.max(maxZ, pos[o + 2]);
        }

        float cx = (minX + maxX) * 0.5f;
        float cy = (minY + maxY) * 0.5f;
        float cz = (minZ + maxZ) * 0.5f;

        float dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        float diagonal = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float invDiag = diagonal > 1e-8f ? 1.0f / diagonal : 1.0f;

        for (int i = 0; i < n; i++) {
            mesh.setVertexPosition(i,
                    (pos[i * 3] - cx) * invDiag,
                    (pos[i * 3 + 1] - cy) * invDiag,
                    (pos[i * 3 + 2] - cz) * invDiag);
        }
    }

    public JsonObject meshOverlay(String objPath, boolean clear) {
        try {
            return runOnMainThread(() -> {
                JsonObject result = new JsonObject();
                if (!(canvas instanceof MeshNodeViewerScene)) {
                    result.addProperty("ok", false);
                    result.addProperty("error", "MeshNodeViewerScene is not active");
                    return result;
                }
                MeshNodeViewerScene mvs = (MeshNodeViewerScene) canvas;
                if (clear) {
                    mvs.clearOverlay();
                    result.addProperty("ok", true);
                    result.addProperty("action", "cleared");
                } else {
                    mvs.loadOverlay(objPath);
                    result.addProperty("ok", true);
                    result.addProperty("action", "loaded");
                    result.addProperty("path", objPath);
            }
            return result;
            });
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", e.getMessage() == null ? "" : e.getMessage());
            return err;
        }
    }

    public JsonObject loadDsl(String dslName, String node, String port) {
        try {
            return runOnMainThread(() -> {
                JsonObject result = new JsonObject();
                if (!(canvas instanceof MeshNodeViewerScene)) {
                    result.addProperty("ok", false);
                    result.addProperty("error", "MeshNodeViewerScene is not active");
                    return result;
                }
                MeshNodeViewerScene mvs = (MeshNodeViewerScene) canvas;
                mvs.loadDsl(dslName, node, port);
                result.addProperty("ok", true);
                result.addProperty("dsl", dslName);
                result.addProperty("node", node != null ? node : "");
                result.addProperty("port", port != null ? port : "geometry");
                result.addProperty("vertices", mvs.getMeshVertexCount());
                result.addProperty("faces", mvs.getMeshFaceCount());
                appendTiming(mvs, result);
                return result;
            });
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            StringBuilder msg = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (msg.length() > 0) msg.append(" <- ");
                msg.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            }
            err.addProperty("error", msg.toString());
            return err;
        }
    }

    private static void appendTiming(ixdar.scenes.mesh.MeshNodeViewerScene mvs, JsonObject result) {
        var runtime = mvs.getLastGraphRuntime();
        if (runtime == null) return;
        JsonObject timing = new JsonObject();
        timing.addProperty("total_ms", runtime.lastTotalMs());
        JsonArray nodes = new JsonArray();
        for (var entry : runtime.lastTimingMs().entrySet()) {
            if (entry.getValue() >= 1) {
                JsonObject n = new JsonObject();
                n.addProperty("node", entry.getKey());
                n.addProperty("ms", entry.getValue());
                nodes.add(n);
            }
        }
        timing.add("nodes", nodes);
        result.add("timing", timing);
    }

    public JsonObject getTiming() {
        JsonObject result = new JsonObject();
        if (!(canvas instanceof ixdar.scenes.mesh.MeshNodeViewerScene mvs)) {
            result.addProperty("ok", false);
            result.addProperty("error", "MeshNodeViewerScene is not active");
            return result;
        }
        var runtime = mvs.getLastGraphRuntime();
        if (runtime == null) {
            result.addProperty("ok", false);
            result.addProperty("error", "No DSL has been executed yet");
            return result;
        }
        result.addProperty("ok", true);
        result.addProperty("total_ms", runtime.lastTotalMs());
        JsonArray nodes = new JsonArray();
        for (var entry : runtime.lastTimingMs().entrySet()) {
            JsonObject n = new JsonObject();
            n.addProperty("node", entry.getKey());
            n.addProperty("ms", entry.getValue());
            nodes.add(n);
        }
        result.add("nodes", nodes);
        return result;
    }

    /**
     * Set the orbit camera position. Single runOnMainThread call.
     * Waits one frame for the new orbit to render before returning.
     */
    public JsonObject setOrbit(float azimuth, float elevation, float distance) {
        try {
            // First call: set orbit (runs at end of frame N)
            runOnMainThread(() -> {
                if (!(canvas instanceof MeshNodeViewerScene mvs)) {
                    JsonObject err = new JsonObject();
                    err.addProperty("ok", false);
                    err.addProperty("error", "MeshNodeViewerScene is not active");
                    return err;
                }
                OrbitMouseTrap orbit = mvs.getOrbitMouse();
                orbit.setOrbit(azimuth, elevation, distance);
                return null;
            });
            // Second call: just returns after the next frame renders with the new orbit
            return runOnMainThread(() -> {
                JsonObject result = new JsonObject();
                result.addProperty("ok", true);
                result.addProperty("azimuth", azimuth);
                result.addProperty("elevation", elevation);
                result.addProperty("distance", distance);
                return result;
            });
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", e.getMessage());
            return err;
        }
    }

    /** Get current orbit camera state. */
    public JsonObject getOrbit() {
        JsonObject result = new JsonObject();
        if (!(canvas instanceof MeshNodeViewerScene mvs)) {
            result.addProperty("ok", false);
            result.addProperty("error", "MeshNodeViewerScene is not active");
            return result;
        }
        OrbitMouseTrap orbit = mvs.getOrbitMouse();
        result.addProperty("ok", true);
        result.addProperty("azimuth", orbit.getAzimuth());
        result.addProperty("elevation", orbit.getElevation());
        result.addProperty("distance", orbit.getDistance());
        if (mvs.getMesh() != null) {
            result.addProperty("mesh_radius", mvs.getMeshRadius());
        }
        return result;
    }

    public JsonObject setProjection(boolean orthographic) {
        JsonObject result = new JsonObject();
        if (!(canvas instanceof MeshNodeViewerScene mvs)) {
            result.addProperty("ok", false);
            result.addProperty("error", "MeshNodeViewerScene is not active");
            return result;
        }
        mvs.setOrthographic(orthographic);
        result.addProperty("ok", true);
        result.addProperty("orthographic", mvs.isOrthographic());
        return result;
    }

    public JsonObject getProjection() {
        JsonObject result = new JsonObject();
        if (!(canvas instanceof MeshNodeViewerScene mvs)) {
            result.addProperty("ok", false);
            result.addProperty("error", "MeshNodeViewerScene is not active");
            return result;
        }
        result.addProperty("ok", true);
        result.addProperty("orthographic", mvs.isOrthographic());
        return result;
    }

    private static ixdar.geometry.mesh.data.MeshDistance.DistanceType parseDistanceType(String str) {
        try {
            return ixdar.geometry.mesh.data.MeshDistance.DistanceType.valueOf(str.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ixdar.geometry.mesh.data.MeshDistance.DistanceType.HAUSDORFF;
        }
    }

    public JsonObject captureScreenshot(String outputPath, boolean inlineBase64) throws Exception {
        return runOnMainThread(() -> {
            int width = Platforms.get().getFrameBufferWidth();
            int height = Platforms.get().getFrameBufferHeight();
            int[] pixels = Platforms.gl().readPixels(0, 0, width, height, Platforms.gl().RGBA(),
                    Platforms.gl().UNSIGNED_BYTE(),
                    width * height * 4);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int srcIndex = (height - 1 - y) * width + x;
                    image.setRGB(x, y, pixels[srcIndex]);
                }
            }
            File out;
            if (outputPath == null || outputPath.isBlank()) {
                String filename = "screenshot-" + System.currentTimeMillis() + ".png";
                out = new File("screenshots/automation", filename);
            } else {
                out = new File(outputPath);
                if (!out.isAbsolute()) {
                    out = new File(System.getProperty("user.dir"), outputPath);
                }
            }
            File parent = out.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            ImageIO.write(image, "PNG", out);
            byte[] pngBytes = imageBytes(image);
            JsonObject result = new JsonObject();
            result.addProperty("path", out.getAbsolutePath());
            result.addProperty("width", width);
            result.addProperty("height", height);
            result.addProperty("sha256", sha256(pngBytes));
            if (inlineBase64) {
                result.addProperty("base64", Base64.getEncoder().encodeToString(pngBytes));
            }
            result.addProperty("inlineBase64", inlineBase64);
            return result;
        });
    }

    /**
     * Capture 8 viewpoints (front/right/back/left/top/bottom/3-4 front-R/3-4 front-L)
     * and composite into a labeled 4x2 grid PNG.
     *
     * Uses separate runOnMainThread calls for each view: one to set orbit (scene re-renders
     * naturally on the next frame), then one to read pixels. This avoids re-entrantly calling
     * drawScene() from within processMainThreadCommands(), which causes a hang.
     */
    public JsonObject captureMultiview(String outputPath, boolean inlineBase64) throws Exception {
        float[][] views = {
            {(float)(Math.PI / 2), 0},            // Front
            {0, 0},                                // Right
            {(float)(-Math.PI / 2), 0},            // Back
            {(float) Math.PI, 0},                  // Left
            {(float)(Math.PI / 2), 1.45f},         // Top
            {(float)(Math.PI / 2), -1.45f},        // Bottom
            {(float)(Math.PI / 4), 0.4f},          // 3/4 Front-R
            {(float)(3 * Math.PI / 4), 0.4f},      // 3/4 Front-L
        };
        String[] labels = {"Front", "Right", "Back", "Left", "Top", "Bottom", "3/4 Front-R", "3/4 Front-L"};

        // Save original orbit and compute view distance on the render thread
        float[] saved = new float[4]; // az, el, dist, viewDist
        runOnMainThread(() -> {
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
            runOnMainThread(() -> {
                if (canvas instanceof MeshNodeViewerScene mvs) {
                    mvs.getOrbitMouse().setOrbit(az, el, dist);
                }
                return new JsonObject();
            });

            // Call 2: runs at end of frame N+1, AFTER drawScene() + shader flush.
            // Reads the freshly rendered pixels with new orbit applied.
            final int viewIndex = i;
            runOnMainThread(() -> {
                int w = Platforms.get().getFrameBufferWidth();
                int h = Platforms.get().getFrameBufferHeight();
                dims[0] = w;
                dims[1] = h;
                int[] pixels = Platforms.gl().readPixels(0, 0, w, h,
                        Platforms.gl().RGBA(), Platforms.gl().UNSIGNED_BYTE(), w * h * 4);
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
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
        runOnMainThread(() -> {
            if (canvas instanceof MeshNodeViewerScene mvs) {
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
        BufferedImage composite = new BufferedImage(4 * cellW, 2 * cellH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = composite.createGraphics();
        Font labelFont = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(14, cellH / 20));
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
            out = new File("screenshots/automation", "multiview-" + System.currentTimeMillis() + ".png");
        } else {
            out = new File(outputPath);
            if (!out.isAbsolute()) {
                out = new File(System.getProperty("user.dir"), outputPath);
            }
        }
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        ImageIO.write(composite, "PNG", out);

        byte[] pngBytes = imageBytes(composite);
        JsonObject result = new JsonObject();
        result.addProperty("path", out.getAbsolutePath());
        result.addProperty("width", 4 * cellW);
        result.addProperty("height", 2 * cellH);
        result.addProperty("views", 8);
        result.addProperty("sha256", sha256(pngBytes));
        if (inlineBase64) {
            result.addProperty("base64", Base64.getEncoder().encodeToString(pngBytes));
        }
        return result;
    }

    /**
     * Extract skeleton from a mesh OBJ file via TEASAR algorithm.
     * Pure CPU — no GL context needed.
     */
    public JsonObject meshSkeleton(String meshPath, int resolution) throws IOException {
        if (meshPath == null || meshPath.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "meshPath is required");
            return err;
        }
        File f = new File(meshPath);
        if (!f.isAbsolute()) f = new File(System.getProperty("user.dir"), meshPath);
        if (!f.exists()) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "File not found: " + f.getAbsolutePath());
            return err;
        }

        ArrayMesh mesh = MeshLoader.load(f.getAbsolutePath());
        MeshSkeletonExtractor.SkeletonResult skeleton = MeshSkeletonExtractor.extract(mesh, resolution);

        // Serialize to JsonObject via Gson
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(skeleton);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /**
     * Compare skeletons of two meshes: extract TEASAR from both, match branches,
     * compute errors, and return parameter recommendations.
     * Pure CPU — no GL context needed.
     */
    public JsonObject skeletonCompare(String generatedPath, String referencePath, int resolution) throws IOException {
        // Resolve and validate generated mesh path
        File genFile = new File(generatedPath);
        if (!genFile.isAbsolute()) genFile = new File(System.getProperty("user.dir"), generatedPath);
        if (!genFile.exists()) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", "Generated mesh not found: " + genFile.getAbsolutePath());
            return err;
        }

        // Resolve and validate reference mesh path
        File refFile = new File(referencePath);
        if (!refFile.isAbsolute()) refFile = new File(System.getProperty("user.dir"), referencePath);
        if (!refFile.exists()) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", "Reference mesh not found: " + refFile.getAbsolutePath());
            return err;
        }

        ArrayMesh genMesh = MeshLoader.load(genFile.getAbsolutePath());
        ArrayMesh refMesh = MeshLoader.load(refFile.getAbsolutePath());

        MeshSkeletonExtractor.SkeletonResult genSkel = MeshSkeletonExtractor.extract(genMesh, resolution);
        MeshSkeletonExtractor.SkeletonResult refSkel = MeshSkeletonExtractor.extract(refMesh, resolution);

        MeshSkeletonComparator.ComparisonResult comparison = MeshSkeletonComparator.compare(genSkel, refSkel);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject result = JsonParser.parseString(gson.toJson(comparison)).getAsJsonObject();
        result.addProperty("ok", true);
        result.addProperty("generated_path", genFile.getAbsolutePath());
        result.addProperty("reference_path", refFile.getAbsolutePath());
        result.addProperty("resolution", resolution);

        return result;
    }

    /**
     * Detailed skeleton comparison returning per-joint 3D position deltas.
     * Pure CPU — no GL context needed.
     */
    public JsonObject skeletonCompareDetailed(String generatedPath, String referencePath, int resolution) throws IOException {
        File genFile = new File(generatedPath);
        if (!genFile.isAbsolute()) genFile = new File(System.getProperty("user.dir"), generatedPath);
        if (!genFile.exists()) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", "Generated mesh not found: " + genFile.getAbsolutePath());
            return err;
        }
        File refFile = new File(referencePath);
        if (!refFile.isAbsolute()) refFile = new File(System.getProperty("user.dir"), referencePath);
        if (!refFile.exists()) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", "Reference mesh not found: " + refFile.getAbsolutePath());
            return err;
        }

        ArrayMesh genMesh = MeshLoader.load(genFile.getAbsolutePath());
        ArrayMesh refMesh = MeshLoader.load(refFile.getAbsolutePath());

        MeshSkeletonExtractor.SkeletonResult genSkel = MeshSkeletonExtractor.extract(genMesh, resolution);
        MeshSkeletonExtractor.SkeletonResult refSkel = MeshSkeletonExtractor.extract(refMesh, resolution);

        MeshSkeletonComparator.DetailedComparisonResult comparison =
                MeshSkeletonComparator.compareDetailed(genSkel, refSkel);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject result = JsonParser.parseString(gson.toJson(comparison)).getAsJsonObject();
        result.addProperty("ok", true);
        result.addProperty("generated_path", genFile.getAbsolutePath());
        result.addProperty("reference_path", refFile.getAbsolutePath());
        result.addProperty("resolution", resolution);

        return result;
    }

    /**
     * Compute skeleton sensitivity: Jacobian of joint positions w.r.t. DSL parameters.
     * Pure CPU — no GL context needed. May take 10-30 seconds depending on parameter count.
     */
    public JsonObject skeletonSensitivity(String dslName, String referencePath, int resolution, float epsilon) {
        try {
            // Resolve DSL file — try multiple locations
            String dslFileName = dslName;
            if (!dslFileName.endsWith(".dsl")) dslFileName += ".dsl";
            java.nio.file.Path dslPath = null;
            for (String base : new String[]{
                    "src/main/resources/dsl",
                    "ixdar-app/src/main/resources/dsl"}) {
                java.nio.file.Path candidate = java.nio.file.Path.of(
                        System.getProperty("user.dir"), base, dslFileName);
                if (java.nio.file.Files.exists(candidate)) {
                    dslPath = candidate;
                    break;
                }
            }
            if (dslPath == null) {
                JsonObject err = new JsonObject();
                err.addProperty("ok", false);
                err.addProperty("error", "DSL file not found: " + dslFileName
                        + " (searched from " + System.getProperty("user.dir") + ")");
                return err;
            }

            // Resolve reference mesh
            File refFile = new File(referencePath);
            if (!refFile.isAbsolute()) refFile = new File(System.getProperty("user.dir"), referencePath);
            if (!refFile.exists()) {
                JsonObject err = new JsonObject();
                err.addProperty("ok", false);
                err.addProperty("error", "Reference mesh not found: " + refFile.getAbsolutePath());
                return err;
            }

            String source = java.nio.file.Files.readString(dslPath);
            java.util.List<ixdar.parsing.python.PythonParser.ParsedNode> parsed =
                    new ixdar.parsing.python.PythonParser(new ixdar.parsing.python.PythonLexer(source)).parseGraph();

            ixdar.geometry.mesh.data.SkeletonSensitivityAnalyzer.SensitivityResult sensResult =
                    ixdar.geometry.mesh.data.SkeletonSensitivityAnalyzer.analyze(
                            parsed, refFile.getAbsolutePath(), resolution, epsilon);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("dsl", dslFileName);
            result.addProperty("reference", refFile.getAbsolutePath());
            result.addProperty("resolution", resolution);
            result.addProperty("baselineScore", sensResult.baselineScore());
            result.addProperty("projectedScore", sensResult.projectedScore());
            result.addProperty("parameterCount", sensResult.parameters().size());
            result.addProperty("jointCount", sensResult.jointIndices().size());

            // Suggested deltas
            JsonObject deltas = new JsonObject();
            for (var entry : sensResult.suggestedDeltas().entrySet()) {
                deltas.addProperty(entry.getKey(), entry.getValue());
            }
            result.add("suggestedDeltas", deltas);

            // Suggested new values
            JsonObject suggestedValues = new JsonObject();
            for (var param : sensResult.parameters()) {
                float base = param.kind() == ixdar.geometry.mesh.graph.InputParameterDescriptor.InputParameterKind.INT
                        ? (param.intDefault() != null ? param.intDefault() : 0)
                        : (param.floatDefault() != null ? param.floatDefault() : 0);
                float delta = sensResult.suggestedDeltas().getOrDefault(param.nodeId(), 0f);
                suggestedValues.addProperty(param.nodeId(), base + delta);
            }
            result.add("suggestedValues", suggestedValues);

            if (!sensResult.unstableParams().isEmpty()) {
                com.google.gson.JsonArray unstable = new com.google.gson.JsonArray();
                for (String p : sensResult.unstableParams()) unstable.add(p);
                result.add("unstableParams", unstable);
            }

            return result;
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", e.getMessage());
            return err;
        }
    }

    public JsonObject uiState() {
        JsonObject root = new JsonObject();
        root.addProperty("timestamp", Instant.now().toString());
        root.addProperty("windowWidth", Platforms.get().getWindowWidth());
        root.addProperty("windowHeight", Platforms.get().getWindowHeight());
        root.addProperty("framebufferWidth", Platforms.get().getFrameBufferWidth());
        root.addProperty("framebufferHeight", Platforms.get().getFrameBufferHeight());
        root.addProperty("menuVisible", MenuBox.menuVisible);
        String sceneId = IxdarWindow.getCanvasId();
        root.addProperty("sceneId", sceneId == null ? "" : sceneId);
        root.addProperty("sceneClass", canvas == null ? "" : canvas.getClass().getSimpleName());
        String mode = TradeScene.active ? "trade" : (MainScene.active ? "main" : "menu");
        root.addProperty("mode", mode);
        JsonObject trade = new JsonObject();
        if (TradeScene.active && TradeScene.instance != null) {
            trade.addProperty("active", true);
            if (TradeScene.instance.activeTool != null) {
                trade.addProperty("activeTool", TradeScene.instance.activeTool.displayName());
            }
            if (TradeScene.instance.activeTool instanceof RoutePlanningTool) {
                RoutePlanningTool routeTool = (RoutePlanningTool) TradeScene.instance.activeTool;
                trade.addProperty("routeMode", routeTool.getCurrentMode().name());
                trade.addProperty("routeState", routeTool.getOperationState().name());
                trade.addProperty("selectedCityA", routeTool.getSelectedCityAName());
                trade.addProperty("selectedCityB", routeTool.getSelectedCityBName());
                trade.addProperty("hasRoute", routeTool.getCurrentRoute() != null);
                trade.addProperty("routeSegmentCount",
                        routeTool.getCurrentRoute() == null ? 0 : routeTool.getCurrentRoute().manifoldSegments.size());
                trade.addProperty("canUndo", routeTool.canUndoOperation());
                trade.addProperty("canRedo", routeTool.canRedoOperation());
                trade.addProperty("inPreview",
                        routeTool.getOperationState() == RoutePlanningTool.OperationState.PREVIEW);
            }
            trade.addProperty("headquartersCity", TradeScene.instance.network.headquartersCity == null ? ""
                    : TradeScene.instance.network.headquartersCity.name);
            if (TradeScene.instance.network != null && TradeScene.instance.network.grid != null) {
                trade.addProperty("gridType", TradeScene.instance.network.grid.getClass().getSimpleName());
                if (TradeScene.instance.network.grid instanceof IrregularQuadGrid) {
                    IrregularQuadGrid irregularGrid = (IrregularQuadGrid) TradeScene.instance.network.grid;
                    trade.addProperty("gridSeed", irregularGrid.seed());
                    trade.addProperty("gridRelaxIterations", irregularGrid.relaxIterations());
                    trade.addProperty("gridRows", irregularGrid.rows());
                    trade.addProperty("gridCols", irregularGrid.cols());
                    trade.addProperty("gridAnchorCount", irregularGrid.anchorCount());
                }
            }
            JsonArray tradeCities = new JsonArray();
            if (TradeScene.instance.network != null && TradeScene.instance.network.cities != null
                    && TradeScene.camera != null) {
                for (City city : TradeScene.instance.network.cities) {
                    JsonObject cityJson = new JsonObject();
                    cityJson.addProperty("name", city.name);
                    cityJson.addProperty("xWorld", city.getX());
                    cityJson.addProperty("yWorld", city.getY());
                    cityJson.addProperty("xPx", TradeScene.camera.pointTransformX(city.getX()));
                    cityJson.addProperty("yPx", TradeScene.camera.pointTransformY(city.getY()));
                    tradeCities.add(cityJson);
                }
            }
            trade.add("cities", tradeCities);
        } else {
            trade.addProperty("active", false);
        }
        root.add("trade", trade);

        if (canvas instanceof IrregularGridScene) {
            IrregularGridScene irregularScene = (IrregularGridScene) canvas;
            JsonObject irregular = new JsonObject();
            irregular.addProperty("seed", irregularScene.getSeed());
            irregular.addProperty("relaxIterations", irregularScene.getRelaxIters());
            irregular.addProperty("jitter", irregularScene.getJitter());
            irregular.addProperty("primalPointCount", irregularScene.getPrimalPointCount());
            irregular.addProperty("dualPointCount", irregularScene.getDualPointCount());
            irregular.addProperty("edgeCount", irregularScene.getEdgeCount());
            irregular.addProperty("horizontalEdgeMean", irregularScene.getHorizontalEdgeMean());
            irregular.addProperty("verticalEdgeMean", irregularScene.getVerticalEdgeMean());
            irregular.addProperty("horizontalEdgeStdDev", irregularScene.getHorizontalEdgeStdDev());
            irregular.addProperty("verticalEdgeStdDev", irregularScene.getVerticalEdgeStdDev());
            root.add("irregularGrid", irregular);
        }

        if (canvas instanceof MeshNodeViewerScene) {
            MeshNodeViewerScene meshScene = (MeshNodeViewerScene) canvas;
            JsonObject mesh = new JsonObject();
            mesh.addProperty("vertexCount", meshScene.getMeshVertexCount());
            mesh.addProperty("edgeCount", meshScene.getMeshEdgeCount());
            mesh.addProperty("faceCount", meshScene.getMeshFaceCount());
            mesh.addProperty("boundaryEdgeCount", meshScene.getMeshBoundaryEdgeCount());
            mesh.addProperty("degenerateFaceCount", meshScene.getMeshDegenerateFaceCount());
            mesh.addProperty("eulerCharacteristic", meshScene.getMeshEulerCharacteristic());
            mesh.addProperty("closed", meshScene.isMeshClosed());
            mesh.addProperty("radius", meshScene.getMeshRadius());
            mesh.add("center", vector3Array(meshScene.getMeshCenter()));
            mesh.add("boundingBoxMin", vector3Array(meshScene.getBoundingBoxMin()));
            mesh.add("boundingBoxMax", vector3Array(meshScene.getBoundingBoxMax()));
            root.add("mesh", mesh);
        }

        JsonArray textElements = new JsonArray();
        if (MainScene.terminal != null) {
            textElements.add(hyperStringElement("terminal", "BOTTOM", MainScene.terminal.getCachedInfo(),
                    MainScene.terminal.scrollOffsetY));
        }
        if (MainScene.info != null) {
            textElements.add(hyperStringElement("info", "RIGHT_TOP", MainScene.info.getCachedInfo(),
                    MainScene.info.scrollOffsetY));
        }
        HyperString tooltip = MainScene.getToolTip();
        if (tooltip != null && MainScene.isToolTipVisible()) {
            textElements.add(hyperStringElement("tooltip", "TOOLTIP", tooltip, 0));
        }
        HyperString tradeTooltip = TradeScene.getToolTip();
        if (tradeTooltip != null && TradeScene.isToolTipVisible()) {
            textElements.add(hyperStringElement("trade_tooltip", "TOOLTIP", tradeTooltip, 0));
        }
        root.add("textElements", textElements);

        JsonArray menuItems = new JsonArray();
        if (canvas != null && canvas.menu != null) {
            for (MenuBox.MenuItemBounds itemBounds : canvas.menu.getMenuItemBounds()) {
                JsonObject menuItem = new JsonObject();
                menuItem.addProperty("label", itemBounds.label);
                JsonObject bounds = new JsonObject();
                bounds.addProperty("xPx", itemBounds.left);
                bounds.addProperty("yPx", itemBounds.bottom);
                bounds.addProperty("widthPx", itemBounds.width);
                bounds.addProperty("heightPx", itemBounds.height);
                bounds.addProperty("centerXPx", itemBounds.centerX);
                bounds.addProperty("centerYPx", itemBounds.centerY);
                menuItem.add("bounds", bounds);
                menuItems.add(menuItem);
            }
        } else if (MenuBox.menuItems != null) {
            for (MenuItem item : MenuBox.menuItems) {
                JsonObject menuItem = new JsonObject();
                menuItem.addProperty("label", item.getHeading());
                menuItems.add(menuItem);
            }
        }
        root.add("menuItems", menuItems);

        JsonObject audio = new JsonObject();
        AudioSystem audioSystem = AudioSystem.get();
        audio.addProperty("available", audioSystem.isAvailable());
        audio.addProperty("menuMusicPlaying", audioSystem.isMenuMusicPlaying());
        audio.addProperty("menuMusicSourceCount", audioSystem.getMenuMusicSourceCount());
        audio.addProperty("lastSfxPlayed", audioSystem.getLastSfxPlayed());
        JsonObject sfxCounts = new JsonObject();
        for (Map.Entry<String, Integer> entry : audioSystem.getSfxPlayCountSnapshot().entrySet()) {
            sfxCounts.addProperty(entry.getKey(), entry.getValue());
        }
        audio.add("sfxPlayCountById", sfxCounts);
        JsonArray audioEvents = new JsonArray();
        for (String event : audioSystem.getEventLogSnapshot()) {
            audioEvents.add(event);
        }
        audio.add("eventLog", audioEvents);
        root.add("audio", audio);
        return root;
    }

    private JsonObject hyperStringElement(String type, String region, HyperString value, float scrollOffsetY) {
        JsonObject element = new JsonObject();
        element.addProperty("type", type);
        element.addProperty("region", region);
        element.addProperty("scrollOffsetY", scrollOffsetY);
        JsonArray lines = new JsonArray();
        JsonArray words = new JsonArray();
        if (value != null) {
            for (String line : hyperStringLines(value)) {
                lines.add(line);
            }
            for (HyperWord w : value.words) {
                if (w.newLine) {
                    continue;
                }
                JsonObject word = new JsonObject();
                word.addProperty("text", w.toString());
                word.addProperty("x", w.xScreenOffset);
                word.addProperty("y", w.yScreenOffset);
                word.addProperty("width", w.width);
                word.addProperty("height", w.rowHeight);
                words.add(word);
            }
        }
        element.add("lines", lines);
        element.add("words", words);
        return element;
    }

    private List<String> hyperStringLines(HyperString value) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < value.lines; i++) {
            StringBuilder builder = new StringBuilder();
            List<HyperWord> words = value.getLine(i);
            for (HyperWord word : words) {
                if (!word.newLine) {
                    builder.append(word.toString());
                }
            }
            lines.add(builder.toString().stripTrailing());
        }
        return lines;
    }

    private JsonArray vector3Array(Vector3f value) {
        JsonArray array = new JsonArray();
        array.add(value.x);
        array.add(value.y);
        array.add(value.z);
        return array;
    }

    public JsonObject injectClick(float x, float y, boolean normalized, int button) {
        try {
            return runOnMainThread(() -> {
                MouseTrap mouse = activeMouse();
                JsonObject result = new JsonObject();
                if (mouse == null) {
                    result.addProperty("ok", false);
                    result.addProperty("error", "No active mouse handler");
                    return result;
                }
                float xPos = normalized ? denormalizeX(x) : x;
                float yPos = normalized ? denormalizeY(y) : y;
                if (mouse instanceof TradeMouseTrap) {
                    ((TradeMouseTrap) mouse).beginAutomationInput();
                }
                try {
                    mouse.mousePos(xPos, yPos);
                    mouse.mouseButton(button, ACTION_PRESS, 0);
                    mouse.mouseButton(button, ACTION_RELEASE, 0);
                } finally {
                    if (mouse instanceof TradeMouseTrap) {
                        ((TradeMouseTrap) mouse).endAutomationInput();
                    }
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("xPx", xPos);
                payload.addProperty("yPx", yPos);
                payload.addProperty("xNorm", normalizeX(xPos));
                payload.addProperty("yNorm", normalizeY(yPos));
                payload.addProperty("button", button);
                recorder.recordAbstract("click", payload);
                result.addProperty("ok", true);
                result.add("event", payload);
                return result;
            });
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("ok", false);
            error.addProperty("error", e.getMessage());
            return error;
        }
    }

    public JsonObject injectHover(float x, float y, boolean normalized, boolean persistent) {
        try {
            return runOnMainThread(() -> {
                MouseTrap mouse = activeMouse();
                JsonObject result = new JsonObject();
                if (mouse == null) {
                    result.addProperty("ok", false);
                    result.addProperty("error", "No active mouse handler");
                    return result;
                }
                float xPos = normalized ? denormalizeX(x) : x;
                float yPos = normalized ? denormalizeY(y) : y;
                if (mouse instanceof TradeMouseTrap) {
                    TradeMouseTrap tradeMouse = (TradeMouseTrap) mouse;
                    tradeMouse.beginAutomationInput();
                    try {
                        if (persistent) {
                            tradeMouse.setAutomationHoverLock(xPos, yPos);
                        } else {
                            tradeMouse.clearAutomationHoverLock();
                            tradeMouse.mousePos(xPos, yPos);
                        }
                    } finally {
                        tradeMouse.endAutomationInput();
                    }
                } else {
                    mouse.mousePos(xPos, yPos);
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("xPx", xPos);
                payload.addProperty("yPx", yPos);
                payload.addProperty("xNorm", normalizeX(xPos));
                payload.addProperty("yNorm", normalizeY(yPos));
                payload.addProperty("persistent", persistent);
                recorder.recordAbstract("hover", payload);
                result.addProperty("ok", true);
                result.add("event", payload);
                return result;
            });
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("ok", false);
            error.addProperty("error", e.getMessage());
            return error;
        }
    }

    public JsonObject clearHoverLock() {
        try {
            return runOnMainThread(() -> {
                MouseTrap mouse = activeMouse();
                JsonObject result = new JsonObject();
                if (mouse instanceof TradeMouseTrap) {
                    TradeMouseTrap tradeMouse = (TradeMouseTrap) mouse;
                    tradeMouse.beginAutomationInput();
                    try {
                        tradeMouse.clearAutomationHoverLock();
                    } finally {
                        tradeMouse.endAutomationInput();
                    }
                }
                result.addProperty("ok", true);
                return result;
            });
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("ok", false);
            error.addProperty("error", e.getMessage());
            return error;
        }
    }

    public JsonObject requestShutdown() {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("accepted", true);
        Thread shutdownThread = new Thread(() -> {
            try {
                Thread.sleep(150);
                runOnMainThread(() -> {
                    if (canvas != null) {
                        canvas.shutdown();
                    } else {
                        stop();
                    }
                    IxdarWindow.requestClose();
                    return new JsonObject();
                });
                Thread.sleep(1200);
                System.exit(0);
            } catch (Exception ignored) {
                // Shutdown is best-effort.
            }
        }, "automation-shutdown");
        shutdownThread.setDaemon(true);
        shutdownThread.start();
        return result;
    }

    public JsonObject injectScroll(double delta) {
        try {
            return runOnMainThread(() -> {
                MouseTrap mouse = activeMouse();
                JsonObject result = new JsonObject();
                if (mouse == null) {
                    result.addProperty("ok", false);
                    result.addProperty("error", "No active mouse handler");
                    return result;
                }
                mouse.scrollCallback(delta);
                JsonObject payload = new JsonObject();
                payload.addProperty("delta", delta);
                recorder.recordAbstract("scroll", payload);
                result.addProperty("ok", true);
                return result;
            });
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("ok", false);
            error.addProperty("error", e.getMessage());
            return error;
        }
    }

    public JsonObject injectKey(int key, int action, int mods, int scancode) {
        try {
            return runOnMainThread(() -> {
                KeyGuy keys = activeKeys();
                JsonObject result = new JsonObject();
                if (keys == null) {
                    result.addProperty("ok", false);
                    result.addProperty("error", "No active key handler");
                    return result;
                }
                keys.keyCallback(0L, key, scancode, action, mods);
                JsonObject payload = new JsonObject();
                payload.addProperty("key", key);
                payload.addProperty("action", action);
                payload.addProperty("mods", mods);
                payload.addProperty("scancode", scancode);
                recorder.recordAbstract("key", payload);
                result.addProperty("ok", true);
                return result;
            });
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("ok", false);
            error.addProperty("error", e.getMessage());
            return error;
        }
    }

    public JsonObject injectType(String text) {
        try {
            return runOnMainThread(() -> {
                KeyGuy keys = activeKeys();
                JsonObject result = new JsonObject();
                if (keys == null) {
                    result.addProperty("ok", false);
                    result.addProperty("error", "No active key handler");
                    return result;
                }
                for (int i = 0; i < text.length(); i++) {
                    keys.charCallback(0L, text.charAt(i));
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("text", text);
                recorder.recordAbstract("type", payload);
                result.addProperty("ok", true);
                return result;
            });
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("ok", false);
            error.addProperty("error", e.getMessage());
            return error;
        }
    }

    public void executeReplayEvent(AutomationReplayEngine.ReplayMode mode, String type, JsonObject payload) {
        if (mode == AutomationReplayEngine.ReplayMode.RAW) {
            if ("mouse_move".equals(type)) {
                float x = payload.has("xPx") ? payload.get("xPx").getAsFloat() : payload.get("x").getAsFloat();
                float y = payload.has("yPx") ? payload.get("yPx").getAsFloat() : payload.get("y").getAsFloat();
                activeMouse().moveOrDrag(0L, x, y);
            } else if ("mouse_button".equals(type)) {
                activeMouse().mouseButton(payload.get("button").getAsInt(), payload.get("action").getAsInt(),
                        payload.get("mods").getAsInt());
            } else if ("scroll".equals(type)) {
                activeMouse().scrollCallback(payload.get("yOffset").getAsDouble());
            } else if ("key".equals(type)) {
                activeKeys().keyCallback(0L, payload.get("key").getAsInt(), payload.get("scancode").getAsInt(),
                        payload.get("action").getAsInt(), payload.get("mods").getAsInt());
            } else if ("char".equals(type)) {
                activeKeys().charCallback(0L, payload.get("codepoint").getAsInt());
            }
            return;
        }
        if ("click".equals(type)) {
            float xNorm = payload.has("xNorm") ? payload.get("xNorm").getAsFloat()
                    : payload.get("xNormalized").getAsFloat();
            float yNorm = payload.has("yNorm") ? payload.get("yNorm").getAsFloat()
                    : payload.get("yNormalized").getAsFloat();
            injectClick(xNorm, yNorm, true,
                    payload.get("button").getAsInt());
        } else if ("type".equals(type)) {
            injectType(payload.get("text").getAsString());
        } else if ("scroll".equals(type)) {
            injectScroll(payload.get("delta").getAsDouble());
        } else if ("key".equals(type)) {
            injectKey(payload.get("key").getAsInt(), payload.get("action").getAsInt(), payload.get("mods").getAsInt(),
                    payload.get("scancode").getAsInt());
        }
    }

    private KeyGuy activeKeys() {
        if (TradeScene.active && TradeScene.instance != null) {
            return TradeScene.instance.getKeys();
        }
        if (MainScene.active && MainScene.keys != null) {
            return MainScene.keys;
        }
        return canvas == null ? null : canvas.keys;
    }

    private MouseTrap activeMouse() {
        if (TradeScene.active && TradeScene.instance != null) {
            return TradeScene.instance.getMouse();
        }
        if (MainScene.active && MainScene.mouse != null) {
            return MainScene.mouse;
        }
        return canvas == null ? null : canvas.mouse;
    }

    public void processMainThreadCommands() {
        if (renderThreadId == -1) {
            renderThreadId = Thread.currentThread().getId();
        }
        PendingMainThreadAction pending = pendingMainThreadActions.poll();
        while (pending != null) {
            try {
                pending.result = pending.action.call();
            } catch (Exception e) {
                pending.error = e;
            } finally {
                pending.latch.countDown();
            }
            pending = pendingMainThreadActions.poll();
        }
    }

    private JsonObject runOnMainThread(Callable<JsonObject> action) throws Exception {
        if (renderThreadId != -1 && Thread.currentThread().getId() == renderThreadId) {
            return action.call();
        }
        PendingMainThreadAction pending = new PendingMainThreadAction();
        pending.action = action;
        pendingMainThreadActions.offer(pending);
        boolean completed = pending.latch.await(MAIN_THREAD_WAIT_MS, TimeUnit.MILLISECONDS);
        if (!completed) {
            throw new IllegalStateException("Main-thread action timed out");
        }
        if (pending.error != null) {
            throw pending.error;
        }
        return pending.result == null ? new JsonObject() : pending.result;
    }

    private float normalizeX(float x) {
        int w = Math.max(1, Platforms.get().getWindowWidth());
        return x / w;
    }

    private float normalizeY(float y) {
        int h = Math.max(1, Platforms.get().getWindowHeight());
        return y / h;
    }

    private float denormalizeX(float x) {
        return x * Platforms.get().getWindowWidth();
    }

    private float denormalizeY(float y) {
        return y * Platforms.get().getWindowHeight();
    }

    private byte[] imageBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        return baos.toByteArray();
    }

    private String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte b : hash) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
