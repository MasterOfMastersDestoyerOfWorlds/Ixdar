package ixdar.platform.automation.endpoints.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.TradeMouseTrap;

@AutomationRouteAnnotation(path = "input/click", method = APIMethod.POST)
public class InjectClick extends AutomationEndpoint implements AutomationRoute {

    public JsonObject endpointHandler(JsonObject body) throws IOException {
        float x = body.has("x") ? body.get("x").getAsFloat() : 0f;
        float y = body.has("y") ? body.get("y").getAsFloat() : 0f;
        boolean normalized = body.has("normalized") && body.get("normalized").getAsBoolean();
        int button = body.has("button") ? body.get("button").getAsInt() : 0;
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
                payload.addProperty("button", button);
                runtime.recordAbstractAction("click", payload);
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
    }
}
