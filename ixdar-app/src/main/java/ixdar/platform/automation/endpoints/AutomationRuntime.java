package ixdar.platform.automation.endpoints;


import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ixdar.audio.AudioSystem;
import ixdar.canvas.Canvas3D;
import ixdar.canvas.IxdarWindow;
import ixdar.game.City;
import ixdar.geometry.mesh.MeshCanonicalFingerprint;
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshDistance;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.data.MeshSkeletonComparator;
import ixdar.geometry.mesh.data.MeshSkeletonExtractor;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.point.IrregularQuadGrid;
import ixdar.graphics.render.text.HyperString;
import ixdar.graphics.render.text.HyperWord;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.gui.ui.menu.MenuItem;
import ixdar.gui.ui.tools.RoutePlanningTool;
import ixdar.platform.Platforms;
import ixdar.platform.automation.AutomationApiServer;
import ixdar.platform.automation.AutomationRecorder;
import ixdar.platform.automation.AutomationReplayEngine;
import ixdar.platform.automation.AutomationReplayEngine.ReplayMode;
import ixdar.platform.automation.endpoints.input.InjectKey;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.platform.input.TradeMouseTrap;
import ixdar.scenes.anatomy.IrregularGridScene;
import ixdar.scenes.main.MainScene;
import ixdar.scenes.mesh.MeshNodeViewerScene;
import ixdar.scenes.trade.TradeScene;
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

public class AutomationRuntime {
    public static final String KEY = "key";
    public static final String SCANCODE = "scancode";
    public static final String ACTION = "action";
    public static final String MODS = "mods";
    public static final String CODEPOINT = "codepoint";
    public static final String CHAR = "char";
    public static final String BUTTON = "button";
    public static final String XPX = "xPx";
    public static final String YPX = "yPx";
    public static final String MOUSE_BUTTON = "mouse_button";
    public static final String MOUSE_MOVE = "mouse_move";
    public static final String YOFFSET = "yOffset";
    public static final String SCROLL = "scroll";
    public static final String ERROR = "error";
    public static final String TYPE = "type";
    public static final String X = "x";
    public static final String Y = "y";
    public static final String XNORM = "xNorm";
    public static final String YNORM = "yNorm";
    public static final int NUM_47832 = 47832;
    public static final int NUM_3 = 3;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_1e_8 = 1e-8f;

    public static final long MAIN_THREAD_WAIT_MS = 60000L;
    public static final AutomationRuntime INSTANCE = new AutomationRuntime();

    public final AutomationRecorder recorder = new AutomationRecorder();
    public final AutomationReplayEngine replayEngine = new AutomationReplayEngine(this);
    public final Queue<PendingMainThreadAction> pendingMainThreadActions = new ConcurrentLinkedQueue<>();
    public AutomationApiServer server;
    public boolean started;
    public Canvas3D canvas;
    public volatile long renderThreadId = -1;

    /**
     * TODO: document {@code get}.
     *
     * @return TODO: describe
     */
    public static AutomationRuntime get() {
        return INSTANCE;
    }

    /**
     * TODO: document {@code start}.
     *
     * @param canvas3D TODO: describe
     */
    public synchronized void start(Canvas3D canvas3D) {
        if (started) {
            if (canvas == null) {
                canvas = canvas3D;
            }
            return;
        }
        this.canvas = canvas3D;
        int port = Integer.getInteger("ixdar.automation.port", NUM_47832);
        try {
            server = new AutomationApiServer(this, port);
            server.start();
            started = true;
            String message = "[Automation] Listening on http://127.0.0.1:" + port;
            Platforms.get().log(message);
        } catch (IOException e) {
            Platforms.get().log(
                    "Automation server failed to start: " + e.getMessage());
        }
    }

    /**
     * TODO: document {@code stop}.
     */
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

    /**
     * TODO: document {@code recorder}.
     *
     * @return TODO: describe
     */
    public AutomationRecorder recorder() {
        return recorder;
    }

    /**
     * TODO: document {@code replayEngine}.
     *
     * @return TODO: describe
     */
    public AutomationReplayEngine replayEngine() {
        return replayEngine;
    }

    /**
     * TODO: document {@code recordRawKey}.
     *
     * @param key TODO: describe
     * @param scancode TODO: describe
     * @param action TODO: describe
     * @param mods TODO: describe
     */
    public void recordRawKey(int key, int scancode, int action, int mods) {
        JsonObject payload = new JsonObject();
        payload.addProperty(KEY, key);
        payload.addProperty(SCANCODE, scancode);
        payload.addProperty(ACTION, action);
        payload.addProperty(MODS, mods);
        recorder.recordRaw(KEY, payload);
    }

    /**
     * TODO: document {@code recordRawChar}.
     *
     * @param codepoint TODO: describe
     */
    public void recordRawChar(int codepoint) {
        JsonObject payload = new JsonObject();
        payload.addProperty(CODEPOINT, codepoint);
        recorder.recordRaw(CHAR, payload);
    }

    /**
     * TODO: document {@code recordRawMouseButton}.
     *
     * @param button TODO: describe
     * @param action TODO: describe
     * @param mods TODO: describe
     * @param x TODO: describe
     * @param y TODO: describe
     */
    public void recordRawMouseButton(
            int button,
            int action,
            int mods,
            float x,
            float y) {
        JsonObject payload = new JsonObject();
        payload.addProperty(BUTTON, button);
        payload.addProperty(ACTION, action);
        payload.addProperty(MODS, mods);
        payload.addProperty(XPX, x);
        payload.addProperty(YPX, y);
        // payload.addProperty("xNorm", normalizeX(x));
        // payload.addProperty("yNorm", normalizeY(y));
        recorder.recordRaw(MOUSE_BUTTON, payload);
    }

    /**
     * TODO: document {@code recordRawMouseMove}.
     *
     * @param x TODO: describe
     * @param y TODO: describe
     */
    public void recordRawMouseMove(float x, float y) {
        JsonObject payload = new JsonObject();
        payload.addProperty(XPX, x);
        payload.addProperty(YPX, y);
        // payload.addProperty("xNorm", normalizeX(x));
        // payload.addProperty("yNorm", normalizeY(y));
        recorder.recordRaw(MOUSE_MOVE, payload);
    }

    /**
     * TODO: document {@code recordRawScroll}.
     *
     * @param yOffset TODO: describe
     */
    public void recordRawScroll(double yOffset) {
        JsonObject payload = new JsonObject();
        payload.addProperty(YOFFSET, yOffset);
        recorder.recordRaw(SCROLL, payload);
    }

    /**
     * TODO: document {@code recordAbstractAction}.
     *
     * @param type TODO: describe
     * @param payload TODO: describe
     */
    public void recordAbstractAction(String type, JsonObject payload) {
        recorder.recordAbstract(type, payload);
    }

    /**
     * Map-based variant for callers that can't reference Gson directly (e.g.
     * TeaVM-compiled code).
     *
     * @param type TODO: describe
     * @param payload TODO: describe
     */
    public void recordAbstractActionMap(
            String type,
            Map<String, Object> payload) {
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

    /**
     * Centers a mesh to the origin and scales it so its bounding box diagonal is
     * 1.0. Modifies vertex positions in place.
     *
     * @param mesh TODO: describe
     */
    public static void normalizeMeshPositions(
            ixdar.geometry.mesh.data.ArrayMesh mesh) {
        int n = mesh.vertexCount();
        if (n == 0)
            return;

        float[] pos = mesh.copyPositions();
        float minX = Float.MAX_VALUE,
                minY = Float.MAX_VALUE,
                minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE,
                maxY = -Float.MAX_VALUE,
                maxZ = -Float.MAX_VALUE;

        for (int i = 0; i < n; i++) {
            int o = i * NUM_3;
            minX = Math.min(minX, pos[o]);
            maxX = Math.max(maxX, pos[o]);
            minY = Math.min(minY, pos[o + 1]);
            maxY = Math.max(maxY, pos[o + 1]);
            minZ = Math.min(minZ, pos[o + 2]);
            maxZ = Math.max(maxZ, pos[o + 2]);
        }

        float cx = (minX + maxX) * NUM_0_5;
        float cy = (minY + maxY) * NUM_0_5;
        float cz = (minZ + maxZ) * NUM_0_5;

        float dx = maxX - minX,
                dy = maxY - minY,
                dz = maxZ - minZ;
        float diagonal = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float invDiag = diagonal > NUM_1e_8 ? 1.0f / diagonal : 1.0f;

        for (int i = 0; i < n; i++) {
            mesh.setVertexPosition(
                    i,
                    (pos[i * NUM_3] - cx) * invDiag,
                    (pos[i * NUM_3 + 1] - cy) * invDiag,
                    (pos[i * NUM_3 + 2] - cz) * invDiag);
        }
    }

    /**
     * TODO: document {@code appendTiming}.
     *
     * @param mvs TODO: describe
     * @param result TODO: describe
     */
    public static void appendTiming(
            ixdar.scenes.mesh.MeshNodeViewerScene mvs,
            JsonObject result) {
        var runtime = mvs.getLastGraphRuntime();
        if (runtime == null)
            return;
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


    /**
     * TODO: document {@code parseDistanceType}.
     *
     * @param str TODO: describe
     * @return TODO: describe
     */
    public static MeshDistance.DistanceType parseDistanceType(
            String str) {
        try {
            return MeshDistance.DistanceType.valueOf(
                    str.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MeshDistance.DistanceType.HAUSDORFF;
        }
    }

    

    /**
     * Extract skeleton from a mesh OBJ file via TEASAR algorithm. Pure CPU — no GL
     * context needed.
     *
     * @param meshPath TODO: describe
     * @param resolution TODO: describe
     * @throws IOException TODO: describe
     * @return TODO: describe
     */
    public JsonObject meshSkeleton(String meshPath, int resolution)
            throws IOException {
        if (meshPath == null || meshPath.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty(ERROR, "meshPath is required");
            return err;
        }
        File f = new File(meshPath);
        if (!f.isAbsolute())
            f = new File(
                    System.getProperty("user.dir"),
                    meshPath);
        if (!f.exists()) {
            JsonObject err = new JsonObject();
            err.addProperty(ERROR, "File not found: " + f.getAbsolutePath());
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
     * TODO: document {@code hyperStringElement}.
     *
     * @param type TODO: describe
     * @param region TODO: describe
     * @param value TODO: describe
     * @param scrollOffsetY TODO: describe
     * @return TODO: describe
     */
    public JsonObject hyperStringElement(
            String type,
            String region,
            HyperString value,
            float scrollOffsetY) {
        JsonObject element = new JsonObject();
        element.addProperty(TYPE, type);
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
                word.addProperty(X, w.xScreenOffset);
                word.addProperty(Y, w.yScreenOffset);
                word.addProperty("width", w.width);
                word.addProperty("height", w.rowHeight);
                words.add(word);
            }
        }
        element.add("lines", lines);
        element.add("words", words);
        return element;
    }

    /**
     * TODO: document {@code hyperStringLines}.
     *
     * @param value TODO: describe
     * @return TODO: describe
     */
    public List<String> hyperStringLines(HyperString value) {
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

    /**
     * TODO: document {@code vector3Array}.
     *
     * @param value TODO: describe
     * @return TODO: describe
     */
    public JsonArray vector3Array(Vector3f value) {
        JsonArray array = new JsonArray();
        array.add(value.x);
        array.add(value.y);
        array.add(value.z);
        return array;
    }

    /**
     * TODO: document {@code executeReplayEvent}.
     *
     * @param mode TODO: describe
     * @param type TODO: describe
     * @param payload TODO: describe
     */
    public void executeReplayEvent(
            AutomationReplayEngine.ReplayMode mode,
            String type,
            JsonObject payload) {
        if (mode == AutomationReplayEngine.ReplayMode.RAW) {
            if (MOUSE_MOVE.equals(type)) {
                float x = payload.has(XPX)
                        ? payload.get(XPX).getAsFloat()
                        : payload.get(X).getAsFloat();
                float y = payload.has(YPX)
                        ? payload.get(YPX).getAsFloat()
                        : payload.get(Y).getAsFloat();
                activeMouse().moveOrDrag(0L, x, y);
            } else if (MOUSE_BUTTON.equals(type)) {
                activeMouse().mouseButton(
                        payload.get(BUTTON).getAsInt(),
                        payload.get(ACTION).getAsInt(),
                        payload.get(MODS).getAsInt());
            } else if (SCROLL.equals(type)) {
                activeMouse().scrollCallback(
                        payload.get(YOFFSET).getAsDouble());
            } else if (KEY.equals(type)) {
                activeKeys().keyCallback(
                        0L,
                        payload.get(KEY).getAsInt(),
                        payload.get(SCANCODE).getAsInt(),
                        payload.get(ACTION).getAsInt(),
                        payload.get(MODS).getAsInt());
            } else if (CHAR.equals(type)) {
                activeKeys().charCallback(
                        0L,
                        payload.get(CODEPOINT).getAsInt());
            }
            return;
        }
        if ("click".equals(type)) {
            float xNorm = payload.has(XNORM)
                    ? payload.get(XNORM).getAsFloat()
                    : payload.get("xNormalized").getAsFloat();
            float yNorm = payload.has(YNORM)
                    ? payload.get(YNORM).getAsFloat()
                    : payload.get("yNormalized").getAsFloat();
            // injectClick(xNorm, yNorm, true, payload.get("button").getAsInt());
        } else if (TYPE.equals(type)) {
            // injectType(payload.get("text").getAsString());
        } else if (SCROLL.equals(type)) {
            // injectScroll(payload.get("delta").getAsDouble());
        } else if (KEY.equals(type)) {
            // InjectKey.endpointHandler(
            //         payload.get("key").getAsInt(),
            //         payload.get("action").getAsInt(),
            //         payload.get("mods").getAsInt(),
            //         payload.get("scancode").getAsInt());
        }
    }

    /**
     * TODO: document {@code activeKeys}.
     *
     * @return TODO: describe
     */
    public KeyGuy activeKeys() {
        if (TradeScene.active && TradeScene.instance != null) {
            return TradeScene.instance.getKeys();
        }
        if (MainScene.active && MainScene.keys != null) {
            return MainScene.keys;
        }
        return canvas == null ? null : canvas.keys;
    }

    /**
     * TODO: document {@code activeMouse}.
     *
     * @return TODO: describe
     */
    public MouseTrap activeMouse() {
        if (TradeScene.active && TradeScene.instance != null) {
            return TradeScene.instance.getMouse();
        }
        if (MainScene.active && MainScene.mouse != null) {
            return MainScene.mouse;
        }
        return canvas == null ? null : canvas.mouse;
    }

    /**
     * TODO: document {@code processMainThreadCommands}.
     */
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

    /**
     * TODO: document {@code runOnMainThread}.
     *
     * @param action TODO: describe
     * @throws Exception TODO: describe
     * @throws IllegalStateException TODO: describe
     * @return TODO: describe
     */
    public JsonObject runOnMainThread(Callable<JsonObject> action)
            throws Exception {
        if (renderThreadId != -1 &&
                Thread.currentThread().getId() == renderThreadId) {
            return action.call();
        }
        PendingMainThreadAction pending = new PendingMainThreadAction();
        pending.action = action;
        pendingMainThreadActions.offer(pending);
        boolean completed = pending.latch.await(
                MAIN_THREAD_WAIT_MS,
                TimeUnit.MILLISECONDS);
        if (!completed) {
            throw new IllegalStateException("Main-thread action timed out");
        }
        if (pending.error != null) {
            throw pending.error;
        }
        return pending.result == null ? new JsonObject() : pending.result;
    }

    private static class PendingMainThreadAction {

        Callable<JsonObject> action;
        CountDownLatch latch = new CountDownLatch(1);
        JsonObject result;
        Exception error;
    }
}
