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
    public JsonObject endpointHandler(HttpExchange exchange) throws IOException {
        JsonObject result = new JsonObject();
        runtime.replayEngine().resume();
        result.addProperty("ok", true);
        result.addProperty("paused", runtime.replayEngine().isPaused());
        return writeJson(exchange, result);

    }
}
