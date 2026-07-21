package ixdar.platform.automation.endpoints.input;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.TradeMouseTrap;

@AutomationRouteAnnotation(path = "input/hover/clear", method = APIMethod.POST)
public class HoverClear extends AutomationEndpoint implements AutomationRoute {
    public static final String OK = "ok";
    /**
     * {@code POST /input/hover/clear}: release any persistent hover lock previously
     * set on the active {@link TradeMouseTrap}. No-op for other mouse handlers.
     *
     * @param body request body (unused)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": true}} on success, or an error object when main-thread
     *         dispatch fails
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            return runtime.runOnMainThread(() -> {
                MouseTrap mouse = runtime.activeMouse();
                JsonObject result = new JsonObject();
                if (mouse instanceof TradeMouseTrap) {
                    TradeMouseTrap tradeMouse = (TradeMouseTrap) mouse;
                    tradeMouse.beginAutomationInput();
                    try {
                        tradeMouse.clearAutomationHoverLock();
                    } finally {
                        tradeMouse.endAutomationInput();
                    }
                }
                result.addProperty(OK, true);
                return result;
            });
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            err.addProperty("error", e.getMessage());
            return err;
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("hover-clear")
                .description("Release the persistent automation hover lock on the active trade mouse handler.")
                .responseHint("{ok}")
                .build();
    }
}
