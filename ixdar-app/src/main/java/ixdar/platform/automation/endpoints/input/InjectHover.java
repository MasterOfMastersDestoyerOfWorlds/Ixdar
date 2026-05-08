package ixdar.platform.automation.endpoints.input;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.TradeMouseTrap;

@AutomationRouteAnnotation(path = "input/hover", method = APIMethod.POST)
public class InjectHover extends AutomationEndpoint implements AutomationRoute {
    public static final String X = "x";
    public static final String Y = "y";
    public static final String NORMALIZED = "normalized";
    public static final String PERSISTENT = "persistent";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    /**
     * {@code POST /input/hover}: move the cursor without clicking. For
     * {@link TradeMouseTrap}, optionally installs a persistent automation hover
     * lock so subsequent real mouse motion does not dislodge the hover. Recorded
     * as an abstract {@code "hover"} action.
     *
     * @param body JSON body with {@code x}, {@code y} (floats, default 0),
     *             {@code normalized} (boolean; when true, {@code x}/{@code y} are
     *             fractions of window size), and {@code persistent} (boolean,
     *             default true) controlling whether the trade hover lock is set or
     *             cleared
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": true, "event": {xPx, yPx, xNorm, yNorm, persistent}}} on
     *         success, or an error object when no mouse handler is active
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            float x = body.has(X) ? body.get(X).getAsFloat() : 0f;
            float y = body.has(Y) ? body.get(Y).getAsFloat() : 0f;
            boolean normalized = body.has(NORMALIZED) && body.get(NORMALIZED).getAsBoolean();
            boolean persistent = !body.has(PERSISTENT) ||
                    body.get(PERSISTENT).getAsBoolean();
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
                        TradeMouseTrap tradeMouse = (TradeMouseTrap) mouse;
                        tradeMouse.beginAutomationInput();
                        try {
                            if (persistent) {
                                tradeMouse.setAutomationHoverLock(xPos, yPos);
                            } else {
                                tradeMouse.clearAutomationHoverLock();
                                tradeMouse.mousePos(xPos, yPos);
                            }
                        } finally {
                            tradeMouse.endAutomationInput();
                        }
                    } else {
                        mouse.mousePos(xPos, yPos);
                    }
                    JsonObject payload = new JsonObject();
                    payload.addProperty("xPx", xPos);
                    payload.addProperty("yPx", yPos);
                    payload.addProperty("xNorm", normalizeX(xPos));
                    payload.addProperty("yNorm", normalizeY(yPos));
                    payload.addProperty(PERSISTENT, persistent);
                    runtime.recorder.recordAbstract("hover", payload);
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
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty(OK, false);
            error.addProperty(ERROR, e.getMessage());
            return error;
        }
    }
}
