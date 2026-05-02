package ixdar.platform.automation.endpoints.replay;

import java.io.IOException;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(path = "replay/pause", method = APIMethod.POST)
public class Pause extends AutomationEndpoint implements AutomationRoute {

    public JsonObject endpointHandler(HttpExchange exchange) throws IOException {
        try {
            JsonObject result = new JsonObject();
            runtime.replayEngine().pause();
            result.addProperty("ok", true);
            result.addProperty("paused", true);
            return writeJson(exchange, result);
        } catch (Exception e) {
            return writeError(exchange, 500, e.getMessage());
        }
    }
}
