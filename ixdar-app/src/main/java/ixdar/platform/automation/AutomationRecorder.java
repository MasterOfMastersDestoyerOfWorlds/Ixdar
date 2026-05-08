package ixdar.platform.automation;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import ixdar.platform.Platforms;

public class AutomationRecorder {
    public static final String STARTEDATISO = "startedAtIso";
    public static final String TIMESTAMPMS = "timestampMs";
    public static final String TYPE = "type";
    public static final String PAYLOAD = "payload";
    public static final String SAVED = "saved";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private boolean recording;
    private long startMillis;
    private String startedAtIso;
    private final List<JsonObject> rawEvents = new ArrayList<>();
    private final List<JsonObject> abstractActions = new ArrayList<>();
    private String lastSavedFile = "";

    /**
     * TODO: document {@code start}.
     */
    public synchronized void start() {
        recording = true;
        startMillis = System.currentTimeMillis();
        startedAtIso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(startMillis));
        rawEvents.clear();
        abstractActions.clear();
        lastSavedFile = "";
    }

    /**
     * TODO: document {@code isRecording}.
     *
     * @return TODO: describe
     */
    public synchronized boolean isRecording() {
        return recording;
    }

    /**
     * TODO: document {@code status}.
     *
     * @return TODO: describe
     */
    public synchronized JsonObject status() {
        JsonObject status = new JsonObject();
        status.addProperty("recording", recording);
        status.addProperty("rawEventCount", rawEvents.size());
        status.addProperty("abstractActionCount", abstractActions.size());
        status.addProperty(STARTEDATISO, startedAtIso == null ? "" : startedAtIso);
        status.addProperty("lastSavedFile", lastSavedFile);
        return status;
    }

    /**
     * TODO: document {@code recordRaw}.
     *
     * @param type TODO: describe
     * @param payload TODO: describe
     */
    public synchronized void recordRaw(String type, JsonObject payload) {
        if (!recording) {
            return;
        }
        JsonObject item = new JsonObject();
        item.addProperty(TIMESTAMPMS, System.currentTimeMillis() - startMillis);
        item.addProperty(TYPE, type);
        item.add(PAYLOAD, payload);
        rawEvents.add(item);
    }

    /**
     * TODO: document {@code recordAbstract}.
     *
     * @param type TODO: describe
     * @param payload TODO: describe
     */
    public synchronized void recordAbstract(String type, JsonObject payload) {
        if (!recording) {
            return;
        }
        JsonObject item = new JsonObject();
        item.addProperty(TIMESTAMPMS, System.currentTimeMillis() - startMillis);
        item.addProperty(TYPE, type);
        item.add(PAYLOAD, payload);
        abstractActions.add(item);
    }

    /**
     * TODO: document {@code stop}.
     *
     * @param outputPath TODO: describe
     * @throws IOException TODO: describe
     * @return TODO: describe
     */
    public synchronized JsonObject stop(String outputPath) throws IOException {
        if (!recording) {
            JsonObject status = status();
            status.addProperty(SAVED, false);
            return status;
        }
        recording = false;
        String targetPath = outputPath;
        if (targetPath == null || targetPath.isBlank()) {
            targetPath = "recordings/automation/demo-" + startMillis + ".json";
        }
        File out = new File(targetPath);
        if (!out.isAbsolute()) {
            out = new File(System.getProperty("user.dir"), targetPath);
        }
        File parent = out.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty(STARTEDATISO, startedAtIso);
        root.addProperty("savedAtIso", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        int windowWidth = 0;
        int windowHeight = 0;
        int framebufferWidth = 0;
        int framebufferHeight = 0;
        try {
            windowWidth = Platforms.get().getWindowWidth();
            windowHeight = Platforms.get().getWindowHeight();
            framebufferWidth = Platforms.get().getFrameBufferWidth();
            framebufferHeight = Platforms.get().getFrameBufferHeight();
        } catch (Exception ignored) {
        }
        root.addProperty("windowWidth", windowWidth);
        root.addProperty("windowHeight", windowHeight);
        root.addProperty("framebufferWidth", framebufferWidth);
        root.addProperty("framebufferHeight", framebufferHeight);
        root.add("rawEvents", GSON.toJsonTree(rawEvents));
        root.add("abstractActions", GSON.toJsonTree(abstractActions));

        try (FileWriter writer = new FileWriter(out)) {
            GSON.toJson(root, writer);
        }
        lastSavedFile = out.getAbsolutePath();

        JsonObject status = status();
        status.addProperty(SAVED, true);
        status.addProperty("file", lastSavedFile);
        return status;
    }
}
