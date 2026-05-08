package ixdar.platform.automation.endpoints.replay;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(path = "replay/cancel", method = APIMethod.POST)
public class Cancel extends AutomationEndpoint implements AutomationRoute {
    /**
     * {@code POST /replay/cancel}: signal the active replay to abort at the next
     * event boundary. Always acknowledges; if no replay is running this is a no-op.
     *
     * @param body request body (unused)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": true}}
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        JsonObject result = new JsonObject();
        runtime.replayEngine().cancel();
        result.addProperty("ok", true);
        return result;
    }
}
