package ixdar.platform.automation.endpoints.replay;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(id = "ReplayStatus", path = "replay/status", method = APIMethod.GET)
public class Status extends AutomationEndpoint implements AutomationRoute {
    /**
     * {@code GET /replay/status}: snapshot of the replay engine.
     *
     * @param body request body (unused)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"replaying", "status", "file", "paused"}} with the engine's
     *         current flags and the path of the most recent recording
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("replaying", runtime.replayEngine().isReplaying());
        result.addProperty(
                "status",
                runtime.replayEngine().getLastReplayStatus());
        result.addProperty("file", runtime.replayEngine().getLastReplayFile());
        result.addProperty("paused", runtime.replayEngine().isPaused());
        return result;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Snapshot of the replay engine: running flag, status, current file, paused flag.")
                .responseHint("{replaying, status, file, paused}")
                .build();
    }
}
