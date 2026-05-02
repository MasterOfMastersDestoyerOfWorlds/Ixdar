package ixdar.platform.automation.endpoints.input;

import java.io.IOException;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.input.MouseTrap;

@AutomationRouteAnnotation(path = "input/scroll", method = APIMethod.POST)
public class InjectScroll extends AutomationEndpoint implements AutomationRoute {
    public JsonObject endpointHandler(HttpExchange exchange) throws IOException {
        try {
            JsonObject body = readBodyJson(exchange);
            double delta = body.has("delta")
                    ? body.get("delta").getAsDouble()
                    : 0;
            return runtime.runOnMainThread(() -> {
                MouseTrap mouse = runtime.activeMouse();
                JsonObject result = new JsonObject();
                if (mouse == null) {
                    result.addProperty("ok", false);
                    result.addProperty("error", "No active mouse handler");
                    return result;
                }
                mouse.scrollCallback(delta);
                JsonObject payload = new JsonObject();
                payload.addProperty("delta", delta);
                runtime.recorder.recordAbstract("scroll", payload);
                result.addProperty("ok", true);
                return result;
            });
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("ok", false);
            error.addProperty("error", e.getMessage());
            return error;
        }
    }
}
