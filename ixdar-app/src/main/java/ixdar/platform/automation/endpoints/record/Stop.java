package ixdar.platform.automation.endpoints.record;

import java.io.IOException;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(path = "record/stop", method = APIMethod.POST)
public class Stop extends AutomationEndpoint implements AutomationRoute {
    public JsonObject endpointHandler(HttpExchange exchange) throws IOException {
        try {
            JsonObject body = readBodyJson(exchange);
            String path = body.has("path") ? body.get("path").getAsString() : "";
            return runtime.recorder().stop(path);
        } catch (Exception e) {
            return writeError(exchange, 500, e.getMessage());
        }
    }
}
