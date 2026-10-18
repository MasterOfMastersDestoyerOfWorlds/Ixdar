package ixdar.automation.endpoints.input;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.platform.input.MouseTrap;

@AutomationRouteAnnotation(path = "input/scroll", method = APIMethod.POST)
public class InjectScroll extends AutomationEndpoint implements AutomationRoute {
    public static final String DELTA = "delta";
    public static final String DELTA_X = "deltaX";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String COMMAND = "scroll";
    public static final String NO_SCROLL = "0";
    /**
     * {@code POST /input/scroll}: deliver a synthesized two-axis scroll event to the active
     * mouse handler, as the platform's scroll callback does. Recorded as an abstract
     * {@code "scroll"} action.
     *
     * @param body JSON body with {@code delta} (double, default 0), the vertical scroll delta,
     *             and {@code deltaX} (double, default 0), the horizontal one, positive toward the
     *             left as GLFW reports it
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": true}} on success, or an error object when no mouse
     *         handler is active
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            double delta = body.has(DELTA)
                    ? body.get(DELTA).getAsDouble()
                    : 0;
            double deltaX = body.has(DELTA_X)
                    ? body.get(DELTA_X).getAsDouble()
                    : 0;
            return runtime.runOnMainThread(() -> {
                MouseTrap mouse = runtime.activeMouse();
                JsonObject result = new JsonObject();
                if (mouse == null) {
                    result.addProperty(OK, false);
                    result.addProperty(ERROR, "No active mouse handler");
                    return result;
                }
                mouse.scrollCallback(deltaX, delta);
                JsonObject payload = new JsonObject();
                payload.addProperty(DELTA, delta);
                payload.addProperty(DELTA_X, deltaX);
                runtime.recorder.recordAbstract(COMMAND, payload);
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

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName(COMMAND)
                .description("Deliver a synthesized two-axis scroll event to the active mouse handler.")
                .param(DELTA, RouteParamType.FLOAT, false, NO_SCROLL,
                        "Vertical scroll delta passed to the scroll callback.", "-1.5")
                .param(DELTA_X, RouteParamType.FLOAT, false, NO_SCROLL,
                        "Horizontal scroll delta, positive toward the left as GLFW reports it.", "0.5")
                .responseHint("{ok}")
                .build();
    }
}
