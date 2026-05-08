package ixdar.platform.automation;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ixdar.platform.automation.endpoints.AutomationRuntime;

public class AutomationReplayEngine {
    public static final String RUNNING = "running";
    public static final String TIMESTAMPMS = "timestampMs";
    public static final String CANCELLED = "cancelled";
    public static final int NUM_50 = 50;

    private static final Gson GSON = new Gson();

    private final AutomationRuntime runtime;
    private volatile boolean replaying;
    private volatile boolean paused;
    private volatile boolean cancelRequested;
    private volatile String lastReplayStatus = "idle";
    private volatile String lastReplayFile = "";

    /**
     * TODO: document {@code AutomationReplayEngine}.
     *
     * @param runtime TODO: describe
     */
    public AutomationReplayEngine(AutomationRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * TODO: document {@code isReplaying}.
     *
     * @return TODO: describe
     */
    public boolean isReplaying() {
        return replaying;
    }

    /**
     * TODO: document {@code isPaused}.
     *
     * @return TODO: describe
     */
    public boolean isPaused() {
        return paused;
    }

    /**
     * TODO: document {@code getLastReplayStatus}.
     *
     * @return TODO: describe
     */
    public String getLastReplayStatus() {
        return lastReplayStatus;
    }

    /**
     * TODO: document {@code getLastReplayFile}.
     *
     * @return TODO: describe
     */
    public String getLastReplayFile() {
        return lastReplayFile;
    }

    /**
     * TODO: document {@code startReplay}.
     *
     * @param filePath TODO: describe
     * @param mode TODO: describe
     * @return TODO: describe
     */
    public synchronized boolean startReplay(String filePath, ReplayMode mode) {
        if (replaying) {
            return false;
        }
        replaying = true;
        paused = false;
        cancelRequested = false;
        lastReplayStatus = RUNNING;
        lastReplayFile = filePath;
        Thread thread = new Thread(() -> runReplay(filePath, mode), "ixdar-automation-replay");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private void runReplay(String filePath, ReplayMode mode) {
        try {
            File file = new File(filePath);
            if (!file.isAbsolute()) {
                file = new File(System.getProperty("user.dir"), filePath);
            }
            JsonObject root = GSON.fromJson(new FileReader(file), JsonObject.class);
            JsonArray source = mode == ReplayMode.RAW ? root.getAsJsonArray("rawEvents")
                    : root.getAsJsonArray("abstractActions");
            List<JsonObject> events = new ArrayList<>();
            for (JsonElement element : source) {
                events.add(element.getAsJsonObject());
            }
            events.sort(Comparator.comparingLong(o -> o.get(TIMESTAMPMS).getAsLong()));
            long previousTime = 0L;
            for (JsonObject event : events) {
                if (cancelRequested) {
                    lastReplayStatus = CANCELLED;
                    return;
                }
                while (paused && !cancelRequested) {
                    Thread.sleep(NUM_50);
                }
                if (cancelRequested) {
                    lastReplayStatus = CANCELLED;
                    return;
                }
                long currentTime = event.get(TIMESTAMPMS).getAsLong();
                long delay = Math.max(0, currentTime - previousTime);
                if (delay > 0) {
                    Thread.sleep(delay);
                }
                JsonObject payload = event.getAsJsonObject("payload");
                runtime.executeReplayEvent(mode, event.get("type").getAsString(), payload);
                previousTime = currentTime;
            }
            lastReplayStatus = "completed";
        } catch (Exception e) {
            lastReplayStatus = "failed: " + e.getMessage();
        } finally {
            replaying = false;
            paused = false;
        }
    }

    /**
     * TODO: document {@code pause}.
     */
    public void pause() {
        if (replaying) {
            paused = true;
            lastReplayStatus = "paused";
        }
    }

    /**
     * TODO: document {@code resume}.
     */
    public void resume() {
        if (replaying) {
            paused = false;
            lastReplayStatus = RUNNING;
        }
    }

    /**
     * TODO: document {@code cancel}.
     */
    public void cancel() {
        if (replaying) {
            cancelRequested = true;
        }
    }
    public enum ReplayMode {
        ABSTRACT,
        RAW
    }
}
