package ixdar.platform.automation.endpoints.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.TradeMouseTrap;

@AutomationRouteAnnotation(path = "input/click", method = APIMethod.POST)
public class InjectClick extends AutomationEndpoint implements AutomationRoute {
    public static final String X = "x";
    public static final String Y = "y";
    public static final String NORMALIZED = "normalized";
    public static final String BUTTON = "button";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String COMMAND = "click";

    /**
     * {@code POST /input/click}: move the cursor to a target position then issue a
     * press/release pair on the active mouse handler. Recorded as an abstract
     * {@code "click"} action.
     *
     * @param body JSON body with {@code x}, {@code y} (floats, default 0),
     *             {@code normalized} (boolean; when true, {@code x}/{@code y} are
     *             treated as fractions of window size), and {@code button}
     *             (GLFW button code, default 0)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": true, "event": {xPx, yPx, xNorm, yNorm, button}}} on
     *         success, or an error object when no mouse handler is active
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        float x = body.has(X) ? body.get(X).getAsFloat() : 0f;
        float y = body.has(Y) ? body.get(Y).getAsFloat() : 0f;
        boolean normalized = body.has(NORMALIZED) && body.get(NORMALIZED).getAsBoolean();
        int button = body.has(BUTTON) ? body.get(BUTTON).getAsInt() : 0;
        try {
            return runtime.runOnMainThread(() -> {
                MouseTrap mouse = runtime.activeMouse();
                JsonObject result = new JsonObject();
                if (mouse == null) {
                    result.addProperty(OK, false);
                    result.addProperty(ERROR, "No active mouse handler");
                    return result;
                }
                float xPos = normalized ? denormalizeX(x) : x;
                float yPos = normalized ? denormalizeY(y) : y;
                if (mouse instanceof TradeMouseTrap) {
                    ((TradeMouseTrap) mouse).beginAutomationInput();
                }
                try {
                    mouse.mousePos(xPos, yPos);
                    mouse.mouseButton(button, ACTION_PRESS, 0);
                    mouse.mouseButton(button, ACTION_RELEASE, 0);
                } finally {
                    if (mouse instanceof TradeMouseTrap) {
                        ((TradeMouseTrap) mouse).endAutomationInput();
                    }
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("xPx", xPos);
                payload.addProperty("yPx", yPos);
                payload.addProperty("xNorm", normalizeX(xPos));
                payload.addProperty("yNorm", normalizeY(yPos));
                payload.addProperty(BUTTON, button);
                runtime.recordAbstractAction(COMMAND, payload);
                result.addProperty(OK, true);
                result.add("event", payload);
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
                .description("Move the cursor to a point then issue a press/release click on the active mouse handler.")
                .param(X, RouteParamType.FLOAT, false, String.valueOf(0),
                        "Target X coordinate.", "0.5")
                .param(Y, RouteParamType.FLOAT, false, String.valueOf(0),
                        "Target Y coordinate.", "0.25")
                .param(NORMALIZED, RouteParamType.BOOL, false, "false",
                        "Treat X/Y as fractions of window size rather than pixels.", "true")
                .param(BUTTON, RouteParamType.INT, false, String.valueOf(0),
                        "GLFW mouse button code.", "1")
                .responseHint("{ok, event:{xPx, yPx, xNorm, yNorm, button}}")
                .build();
    }
}
