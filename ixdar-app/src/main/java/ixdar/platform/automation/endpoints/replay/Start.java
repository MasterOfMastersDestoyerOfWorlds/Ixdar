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
     * TODO: document {@code endpointHandler}.
     *
     * @param body TODO: describe
     * @throws IOException TODO: describe
     * @return TODO: describe
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
