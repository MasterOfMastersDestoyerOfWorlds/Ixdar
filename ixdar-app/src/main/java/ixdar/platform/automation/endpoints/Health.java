package ixdar.platform.automation.endpoints;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import ixdar.annotations.automation.APIMethod;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import java.io.IOException;
import java.time.Instant;

@AutomationRouteAnnotation(path = "health", method = APIMethod.GET)
public class Health extends AutomationEndpoint implements AutomationRoute {

    @Override
    public JsonObject endpointHandler(HttpExchange exchange) throws IOException {
        JsonObject health = new JsonObject();
        health.addProperty("status", "ok");
        health.addProperty("timestamp", Instant.now().toString());
        health.addProperty("recording", runtime.recorder.isRecording());
        health.addProperty("replaying", runtime.replayEngine.isReplaying());
        health.addProperty(
                "port",
                runtime.server == null ? -1 : runtime.server.port());
        return health;
    }
}
