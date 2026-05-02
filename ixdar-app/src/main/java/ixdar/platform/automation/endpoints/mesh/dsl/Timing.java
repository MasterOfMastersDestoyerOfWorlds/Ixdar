package ixdar.platform.automation.endpoints.mesh.dsl;

import java.io.IOException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "mesh/dsl/timing", method = APIMethod.GET)
public class Timing extends AutomationEndpoint implements AutomationRoute {

    public JsonObject endpointHandler(HttpExchange exchange) throws IOException {
        JsonObject result = new JsonObject();
        if (!(runtime.canvas instanceof MeshNodeViewerScene mvs)) {
            result.addProperty("ok", false);
            result.addProperty("error", "MeshNodeViewerScene is not active");
            return result;
        }
        var runtime = mvs.getLastGraphRuntime();
        if (runtime == null) {
            result.addProperty("ok", false);
            result.addProperty("error", "No DSL has been executed yet");
            return result;
        }
        result.addProperty("ok", true);
        result.addProperty("total_ms", runtime.lastTotalMs());
        JsonArray nodes = new JsonArray();
        for (var entry : runtime.lastTimingMs().entrySet()) {
            JsonObject n = new JsonObject();
            n.addProperty("node", entry.getKey());
            n.addProperty("ms", entry.getValue());
            nodes.add(n);
        }
        result.add("nodes", nodes);
        return result;
    }
}
