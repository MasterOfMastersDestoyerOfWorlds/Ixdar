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
     * {@code POST /input/scroll}: deliver a synthesized scroll event to the active
     * mouse handler. Recorded as an abstract {@code "scroll"} action.
     *
     * @param body JSON body with {@code delta} (double, default 0) — the vertical
     *             scroll delta passed to {@code scrollCallback}
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": true}} on success, or an error object when no mouse
     *         handler is active
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
