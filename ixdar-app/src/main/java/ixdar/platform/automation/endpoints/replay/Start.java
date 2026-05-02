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
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            String file = body.has("file") ? body.get("file").getAsString() : "";
            String mode = body.has("mode") ? body.get("mode").getAsString() : "abstract";
            AutomationReplayEngine.ReplayMode replayMode = "raw".equalsIgnoreCase(mode)
                    ? AutomationReplayEngine.ReplayMode.RAW
                    : AutomationReplayEngine.ReplayMode.ABSTRACT;
            boolean started = runtime.replayEngine().startReplay(file, replayMode);
            JsonObject result = new JsonObject();
            result.addProperty("ok", started);
            result.addProperty("mode", replayMode.name().toLowerCase());
            return result;
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", e.getMessage());
            return err;
        }
    }
}
