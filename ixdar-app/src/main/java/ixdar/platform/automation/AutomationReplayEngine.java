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

public class AutomationReplayEngine {
    public enum ReplayMode {
        ABSTRACT,
        RAW
    }

    private static final Gson GSON = new Gson();

    private final AutomationRuntime runtime;
    private volatile boolean replaying;
    private volatile String lastReplayStatus = "idle";
    private volatile String lastReplayFile = "";

    public AutomationReplayEngine(AutomationRuntime runtime) {
        this.runtime = runtime;
    }

    public boolean isReplaying() {
        return replaying;
    }

    public String getLastReplayStatus() {
        return lastReplayStatus;
    }

    public String getLastReplayFile() {
        return lastReplayFile;
    }

    public synchronized boolean startReplay(String filePath, ReplayMode mode) {
        if (replaying) {
            return false;
        }
        replaying = true;
        lastReplayStatus = "running";
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
            JsonArray source = mode == ReplayMode.RAW ? root.getAsJsonArray("rawEvents") : root.getAsJsonArray("abstractActions");
            List<JsonObject> events = new ArrayList<>();
            for (JsonElement element : source) {
                events.add(element.getAsJsonObject());
            }
            events.sort(Comparator.comparingLong(o -> o.get("timestampMs").getAsLong()));
            long previousTime = 0L;
            for (JsonObject event : events) {
                long currentTime = event.get("timestampMs").getAsLong();
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
        }
    }
}
