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
     * Begin a new recording session: clears any buffered events, captures the start
     * time (epoch and ISO-8601), and flips the recording flag on.
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
     * Whether a recording session is currently active.
     *
     * @return {@code true} between {@link #start()} and {@link #stop(String)}
     */
    public synchronized boolean isRecording() {
        return recording;
    }

    /**
     * Snapshot of the recorder state for the {@code record/status} endpoint.
     *
     * @return JSON object with {@code recording}, {@code rawEventCount},
     *         {@code abstractActionCount}, {@code startedAtIso}, and
     *         {@code lastSavedFile}
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
     * Append a raw input event to the session's raw-event log, tagged with the
     * elapsed milliseconds since {@link #start()}. No-op when not recording.
     *
     * @param type event type tag (e.g. {@code "key"}, {@code "mouse_move"})
     * @param payload event-specific fields
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
     * Append a high-level (abstract) action to the session log, tagged with the
     * elapsed milliseconds since {@link #start()}. No-op when not recording.
     *
     * @param type action type tag (e.g. {@code "click"}, {@code "hover"},
     *             {@code "scroll"})
     * @param payload action-specific fields
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
     * Finalize the session and write a pretty-printed JSON file containing the start
     * timestamp, current window/framebuffer dimensions, and both raw and abstract
     * event lists.
     *
     * @param outputPath target file path; relative paths resolve against
     *                   {@code user.dir} and a blank value falls back to
     *                   {@code recordings/automation/demo-<startMillis>.json}
     * @throws IOException if writing the recording file fails
     * @return status payload with {@code saved} flag and (on success) the absolute
     *         {@code file} path that was written
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
