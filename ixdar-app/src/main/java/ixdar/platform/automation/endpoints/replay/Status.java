package ixdar.platform.automation.endpoints.replay;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(path = "replay/status", method = APIMethod.GET)
public class Status extends AutomationEndpoint implements AutomationRoute {
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
}
