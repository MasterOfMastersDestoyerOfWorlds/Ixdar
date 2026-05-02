package ixdar.platform.automation.endpoints.replay;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(path = "replay/pause", method = APIMethod.POST)
public class Pause extends AutomationEndpoint implements AutomationRoute {

    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            JsonObject result = new JsonObject();
            runtime.replayEngine().pause();
            result.addProperty("ok", true);
            result.addProperty("paused", true);
            return result;
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", e.getMessage());
            return err;
        }
    }
}
