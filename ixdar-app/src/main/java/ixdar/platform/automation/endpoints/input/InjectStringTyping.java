package ixdar.platform.automation.endpoints.input;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.input.KeyGuy;

@AutomationRouteAnnotation(path = "input/type", method = APIMethod.POST)
public class InjectStringTyping extends AutomationEndpoint implements AutomationRoute {
    public static final String TEXT = "text";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    /**
     * TODO: document {@code endpointHandler}.
     *
     * @param body TODO: describe
     * @throws IOException TODO: describe
     * @return TODO: describe
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            String text = body.has(TEXT)
                    ? body.get(TEXT).getAsString()
                    : "";
            try {
                return runtime.runOnMainThread(() -> {
                    KeyGuy keys = runtime.activeKeys();
                    JsonObject result = new JsonObject();
                    if (keys == null) {
                        result.addProperty(OK, false);
                        result.addProperty(ERROR, "No active key handler");
                        return result;
                    }
                    for (int i = 0; i < text.length(); i++) {
                        keys.charCallback(0L, text.charAt(i));
                    }
                    JsonObject payload = new JsonObject();
                    payload.addProperty(TEXT, text);
                    runtime.recorder.recordAbstract("type", payload);
                    result.addProperty(OK, true);
                    return result;
                });
            } catch (Exception e) {
                JsonObject error = new JsonObject();
                error.addProperty(OK, false);
                error.addProperty(ERROR, e.getMessage());
                return error;
            }
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty(OK, false);
            error.addProperty(ERROR, e.getMessage());
            return error;
        }
    }
}
