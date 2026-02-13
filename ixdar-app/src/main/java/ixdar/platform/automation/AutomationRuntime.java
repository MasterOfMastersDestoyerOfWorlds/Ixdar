package ixdar.platform.automation;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.render.text.HyperString;
import ixdar.graphics.render.text.HyperWord;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.gui.ui.menu.MenuItem;
import ixdar.platform.Platforms;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.MouseTrap;
import ixdar.scenes.main.MainScene;
import ixdar.scenes.trade.TradeScene;

public class AutomationRuntime {
    private static final long MAIN_THREAD_WAIT_MS = 3000L;
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

    public JsonObject captureScreenshot(String outputPath, boolean inlineBase64) throws Exception {
        return runOnMainThread(() -> {
            int width = Platforms.get().getFrameBufferWidth();
            int height = Platforms.get().getFrameBufferHeight();
            int[] pixels = Platforms.gl().readPixels(0, 0, width, height, Platforms.gl().RGBA(), Platforms.gl().UNSIGNED_BYTE(),
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

    public JsonObject uiState() {
        JsonObject root = new JsonObject();
        root.addProperty("timestamp", Instant.now().toString());
        root.addProperty("windowWidth", Platforms.get().getWindowWidth());
        root.addProperty("windowHeight", Platforms.get().getWindowHeight());
        root.addProperty("framebufferWidth", Platforms.get().getFrameBufferWidth());
        root.addProperty("framebufferHeight", Platforms.get().getFrameBufferHeight());
        root.addProperty("menuVisible", MenuBox.menuVisible);
        root.addProperty("scene", TradeScene.active ? "trade" : (MainScene.active ? "main" : "menu"));

        JsonArray textElements = new JsonArray();
        if (MainScene.terminal != null) {
            textElements.add(hyperStringElement("terminal", "BOTTOM", MainScene.terminal.getCachedInfo(), MainScene.terminal.scrollOffsetY));
        }
        if (MainScene.info != null) {
            textElements.add(hyperStringElement("info", "RIGHT_TOP", MainScene.info.getCachedInfo(), MainScene.info.scrollOffsetY));
        }
        HyperString tooltip = MainScene.getToolTip();
        if (tooltip != null && MainScene.isToolTipVisible()) {
            textElements.add(hyperStringElement("tooltip", "TOOLTIP", tooltip, 0));
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
                mouse.mousePos(xPos, yPos);
                mouse.mouseButton(button, ACTION_PRESS, 0);
                mouse.mouseButton(button, ACTION_RELEASE, 0);
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
            float xNorm = payload.has("xNorm") ? payload.get("xNorm").getAsFloat() : payload.get("xNormalized").getAsFloat();
            float yNorm = payload.has("yNorm") ? payload.get("yNorm").getAsFloat() : payload.get("yNormalized").getAsFloat();
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
