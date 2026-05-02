package ixdar.platform.automation.endpoints.input;

import java.io.IOException;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.TradeMouseTrap;

@AutomationRouteAnnotation(path = "input/hover/clear", method = APIMethod.POST)
public class HoverClear extends AutomationEndpoint implements AutomationRoute {
    public JsonObject endpointHandler(HttpExchange exchange) throws IOException {
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
                result.addProperty("ok", true);
                return result;
            });
        } catch (Exception e) {
            return writeError(exchange, 500, e.getMessage());
        }
    }
}
