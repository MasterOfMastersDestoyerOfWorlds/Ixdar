package ixdar.platform.automation.endpoints.replay;

import java.io.IOException;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(path = "replay/resume", method = APIMethod.POST)
public class Resume extends AutomationEndpoint implements AutomationRoute {
    /**
     * {@code POST /replay/resume}: clear the paused flag on the replay engine.
     * No-op when no replay is running.
     *
     * @param body request body (unused)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": true, "paused": <bool>}} reflecting the post-call state
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        JsonObject result = new JsonObject();
        runtime.replayEngine().resume();
        result.addProperty("ok", true);
        result.addProperty("paused", runtime.replayEngine().isPaused());
        return result;

    }
}
