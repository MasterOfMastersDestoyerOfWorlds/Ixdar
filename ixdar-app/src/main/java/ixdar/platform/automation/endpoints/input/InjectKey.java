package ixdar.platform.automation.endpoints.input;

import java.io.IOException;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.input.KeyGuy;

@AutomationRouteAnnotation(path = "input/key", method = APIMethod.POST)
public class InjectKey extends AutomationEndpoint implements AutomationRoute {
    public JsonObject endpointHandler(HttpExchange exchange) throws IOException {
        JsonObject body = readBodyJson(exchange);
        int key = body.has("key") ? body.get("key").getAsInt() : 0;
        int action = body.has("action") ? body.get("action").getAsInt() : 1;
        int mods = body.has("mods") ? body.get("mods").getAsInt() : 0;
        int scancode = body.has("scancode")
                ? body.get("scancode").getAsInt()
                : 0;
        try {
            return runtime.runOnMainThread(() -> {
                KeyGuy keys = runtime.activeKeys();
                JsonObject result = new JsonObject();
                if (keys == null) {
                    result.addProperty("ok", false);
                    result.addProperty("error", "No active key handler");
                    return result;
                }
                keys.keyCallback(0L, key, scancode, action, mods);
                JsonObject payload = new JsonObject();
                payload.addProperty("key", key);
                payload.addProperty("action", action);
                payload.addProperty("mods", mods);
                payload.addProperty("scancode", scancode);
                runtime.recorder.recordAbstract("key", payload);
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
