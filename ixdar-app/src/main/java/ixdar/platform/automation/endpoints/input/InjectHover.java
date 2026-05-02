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

@AutomationRouteAnnotation(path = "input/hover", method = APIMethod.POST)
public class InjectHover extends AutomationEndpoint implements AutomationRoute {
    public JsonObject endpointHandler(HttpExchange exchange) throws IOException {
        try {
            JsonObject body = readBodyJson(exchange);
            float x = body.has("x") ? body.get("x").getAsFloat() : 0f;
            float y = body.has("y") ? body.get("y").getAsFloat() : 0f;
            boolean normalized = body.has("normalized") && body.get("normalized").getAsBoolean();
            boolean persistent = !body.has("persistent") ||
                    body.get("persistent").getAsBoolean();
            try {
                return runtime.runOnMainThread(() -> {
                    MouseTrap mouse = runtime.activeMouse();
                    JsonObject result = new JsonObject();
                    if (mouse == null) {
                        result.addProperty("ok", false);
                        result.addProperty("error", "No active mouse handler");
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
                    payload.addProperty("persistent", persistent);
                    runtime.recorder.recordAbstract("hover", payload);
                    result.addProperty("ok", true);
                    result.add("event", payload);
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
