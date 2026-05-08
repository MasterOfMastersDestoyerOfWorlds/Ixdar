package ixdar.platform.automation.endpoints.input;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.input.MouseTrap;

@AutomationRouteAnnotation(path = "input/scroll", method = APIMethod.POST)
public class InjectScroll extends AutomationEndpoint implements AutomationRoute {
    public static final String DELTA = "delta";
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
            double delta = body.has(DELTA)
                    ? body.get(DELTA).getAsDouble()
                    : 0;
            return runtime.runOnMainThread(() -> {
                MouseTrap mouse = runtime.activeMouse();
                JsonObject result = new JsonObject();
                if (mouse == null) {
                    result.addProperty(OK, false);
                    result.addProperty(ERROR, "No active mouse handler");
                    return result;
                }
                mouse.scrollCallback(delta);
                JsonObject payload = new JsonObject();
                payload.addProperty(DELTA, delta);
                runtime.recorder.recordAbstract("scroll", payload);
                result.addProperty(OK, true);
                return result;
            });
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty(OK, false);
            error.addProperty(ERROR, e.getMessage());
            return error;
        }
    }
}
