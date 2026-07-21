package ixdar.platform.automation.endpoints;

import java.io.IOException;
import java.time.Instant;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(path = "health", method = APIMethod.GET)
public class Health extends AutomationEndpoint implements AutomationRoute {

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        JsonObject health = new JsonObject();
        health.addProperty("status", "ok");
        health.addProperty("timestamp", Instant.now().toString());
        health.addProperty("recording", runtime.recorder.isRecording());
        health.addProperty("replaying", runtime.replayEngine.isReplaying());
        health.addProperty("sceneReady", runtime.canvas != null && runtime.canvas.sceneReady);
        health.addProperty(
                "port",
                runtime.server == null ? -1 : runtime.server.port());
        return health;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Liveness probe reporting server status, recording/replaying flags, and port.")
                .responseHint("{status, timestamp, recording, replaying, sceneReady, port}")
                .build();
    }
}
