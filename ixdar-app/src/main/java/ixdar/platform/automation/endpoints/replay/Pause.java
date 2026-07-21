package ixdar.platform.automation.endpoints.replay;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(path = "replay/pause", method = APIMethod.POST)
public class Pause extends AutomationEndpoint implements AutomationRoute {
    public static final String OK = "ok";

    /**
     * {@code POST /replay/pause}: ask the replay engine to suspend before the next
     * event. No-op when no replay is running.
     *
     * @param body request body (unused)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": true, "paused": true}}, or {@code {"ok": false, "error":
     *         {@code <message>}}} on unexpected failure
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            JsonObject result = new JsonObject();
            runtime.replayEngine().pause();
            result.addProperty(OK, true);
            result.addProperty("paused", true);
            return result;
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            err.addProperty("error", e.getMessage());
            return err;
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Suspend the replay engine before the next event; no-op when nothing is running.")
                .responseHint("{ok, paused}")
                .build();
    }
}
