package ixdar.platform.automation.endpoints.input;

import java.io.IOException;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.input.KeyGuy;

@AutomationRouteAnnotation(path = "input/type", method = APIMethod.POST)
public class InjectStringTyping extends AutomationEndpoint implements AutomationRoute {
    public JsonObject endpointHandler(HttpExchange exchange) throws IOException {
        try {
            JsonObject body = readBodyJson(exchange);
            String text = body.has("text")
                    ? body.get("text").getAsString()
                    : "";
            try {
                return runtime.runOnMainThread(() -> {
                    KeyGuy keys = runtime.activeKeys();
                    JsonObject result = new JsonObject();
                    if (keys == null) {
                        result.addProperty("ok", false);
                        result.addProperty("error", "No active key handler");
                        return result;
                    }
                    for (int i = 0; i < text.length(); i++) {
                        keys.charCallback(0L, text.charAt(i));
                    }
                    JsonObject payload = new JsonObject();
                    payload.addProperty("text", text);
                    runtime.recorder.recordAbstract("type", payload);
                    result.addProperty("ok", true);
                    return result;
                });
            } catch (Exception e) {
                JsonObject error = new JsonObject();
                error.addProperty("ok", false);
                error.addProperty("error", e.getMessage());
                return error;
            }
        } catch (Exception e) {
            return writeError(exchange, 500, e.getMessage());
        }
    }
}
