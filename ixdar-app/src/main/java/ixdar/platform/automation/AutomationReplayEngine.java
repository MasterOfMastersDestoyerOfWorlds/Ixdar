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
     * Build a replay engine that dispatches replayed events through {@code runtime}.
     *
     * @param runtime shared runtime used to resolve the active mouse/key handlers
     *                via {@link AutomationRuntime#executeReplayEvent}
     */
    public AutomationReplayEngine(AutomationRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Whether a replay thread is currently running.
     *
     * @return {@code true} between {@link #startReplay} and replay completion or
     *         cancellation
     */
    public boolean isReplaying() {
        return replaying;
    }

    /**
     * Whether the active replay is paused.
     *
     * @return {@code true} when {@link #pause()} has been called and {@link #resume()}
     *         has not
     */
    public boolean isPaused() {
        return paused;
    }

    /**
     * Status string of the most recent replay run.
     *
     * @return {@code "idle"}, {@code "running"}, {@code "paused"},
     *         {@code "completed"}, {@code "cancelled"}, or {@code "failed: <message>"}
     */
    public String getLastReplayStatus() {
        return lastReplayStatus;
    }

    /**
     * Path of the file driving the most recent replay run.
     *
     * @return file path passed to {@link #startReplay}, or empty string before any run
     */
    public String getLastReplayFile() {
        return lastReplayFile;
    }

    /**
     * Spawn a daemon thread that loads the recording at {@code filePath} and replays
     * its events with the original inter-event delays.
     *
     * @param filePath recording file; relative paths resolve against {@code user.dir}
     * @param mode {@link ReplayMode#RAW} to replay {@code rawEvents}, or
     *             {@link ReplayMode#ABSTRACT} to replay {@code abstractActions}
     * @return {@code true} if the replay thread was started, {@code false} if a
     *         replay was already running
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
     * Request that the active replay loop suspend before dispatching the next event.
     * No-op when no replay is running.
     */
    public void pause() {
        if (replaying) {
            paused = true;
            lastReplayStatus = "paused";
        }
    }

    /**
     * Resume a paused replay. No-op when no replay is running.
     */
    public void resume() {
        if (replaying) {
            paused = false;
            lastReplayStatus = RUNNING;
        }
    }

    /**
     * Signal the active replay to abort at the next event boundary. The replay
     * thread will mark its status as {@code "cancelled"} and exit. No-op when no
     * replay is running.
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
