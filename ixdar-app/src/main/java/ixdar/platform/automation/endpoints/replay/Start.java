package ixdar.platform.automation.endpoints.replay;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.automation.AutomationReplayEngine;

@AutomationRouteAnnotation(path = "replay/start", method = APIMethod.POST)
public class Start extends AutomationEndpoint implements AutomationRoute {
    public static final String FILE = "file";
    public static final String MODE = "mode";
    public static final String OK = "ok";
    /**
     * {@code POST /replay/start}: launch a replay from a previously saved recording.
     *
     * @param body JSON body with {@code file} (path to the recording, required) and
     *             optional {@code mode} ({@code "raw"} or {@code "abstract"};
     *             defaults to {@code "abstract"})
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": <started>, "mode": <selected>}} where {@code ok} is
     *         {@code false} when a replay is already running, or an error object on
     *         unexpected failure
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            String file = body.has(FILE) ? body.get(FILE).getAsString() : "";
            String mode = body.has(MODE) ? body.get(MODE).getAsString() : "abstract";
            AutomationReplayEngine.ReplayMode replayMode = "raw".equalsIgnoreCase(mode)
                    ? AutomationReplayEngine.ReplayMode.RAW
                    : AutomationReplayEngine.ReplayMode.ABSTRACT;
            boolean started = runtime.replayEngine().startReplay(file, replayMode);
            JsonObject result = new JsonObject();
            result.addProperty(OK, started);
            result.addProperty(MODE, replayMode.name().toLowerCase());
            return result;
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            err.addProperty("error", e.getMessage());
            return err;
        }
    }
}
