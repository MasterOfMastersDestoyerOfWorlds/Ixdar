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
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private boolean recording;
    private long startMillis;
    private String startedAtIso;
    private final List<JsonObject> rawEvents = new ArrayList<>();
    private final List<JsonObject> abstractActions = new ArrayList<>();
    private String lastSavedFile = "";

    public synchronized void start() {
        recording = true;
        startMillis = System.currentTimeMillis();
        startedAtIso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(startMillis));
        rawEvents.clear();
        abstractActions.clear();
        lastSavedFile = "";
    }

    public synchronized boolean isRecording() {
        return recording;
    }

    public synchronized JsonObject status() {
        JsonObject status = new JsonObject();
        status.addProperty("recording", recording);
        status.addProperty("rawEventCount", rawEvents.size());
        status.addProperty("abstractActionCount", abstractActions.size());
        status.addProperty("startedAtIso", startedAtIso == null ? "" : startedAtIso);
        status.addProperty("lastSavedFile", lastSavedFile);
        return status;
    }

    public synchronized void recordRaw(String type, JsonObject payload) {
        if (!recording) {
            return;
        }
        JsonObject item = new JsonObject();
        item.addProperty("timestampMs", System.currentTimeMillis() - startMillis);
        item.addProperty("type", type);
        item.add("payload", payload);
        rawEvents.add(item);
    }

    public synchronized void recordAbstract(String type, JsonObject payload) {
        if (!recording) {
            return;
        }
        JsonObject item = new JsonObject();
        item.addProperty("timestampMs", System.currentTimeMillis() - startMillis);
        item.addProperty("type", type);
        item.add("payload", payload);
        abstractActions.add(item);
    }

    public synchronized JsonObject stop(String outputPath) throws IOException {
        if (!recording) {
            JsonObject status = status();
            status.addProperty("saved", false);
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
        root.addProperty("startedAtIso", startedAtIso);
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
        status.addProperty("saved", true);
        status.addProperty("file", lastSavedFile);
        return status;
    }
}
