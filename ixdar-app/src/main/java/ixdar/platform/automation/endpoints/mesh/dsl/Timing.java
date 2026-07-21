package ixdar.platform.automation.endpoints.mesh.dsl;

import java.io.IOException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "mesh/dsl/timing", method = APIMethod.GET)
public class Timing extends AutomationEndpoint implements AutomationRoute {
    public static final String OK = "ok";
    public static final String ERROR = "error";

    /**
     * {@code GET /mesh/dsl/timing}: report per-node execution times for the most
     * recent DSL graph run on the active {@link MeshNodeViewerScene}.
     *
     * @param body request body (unused)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": true, "total_ms": <ms>, "nodes": [{node, ms}, ...]}} on
     *         success, or an error object when {@link MeshNodeViewerScene} is not
     *         active or no DSL has been executed yet
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        JsonObject result = new JsonObject();
        if (!(runtime.canvas instanceof MeshNodeViewerScene mvs)) {
            result.addProperty(OK, false);
            result.addProperty(ERROR, "MeshNodeViewerScene is not active");
            return result;
        }
        var runtime = mvs.getLastGraphRuntime();
        if (runtime == null) {
            result.addProperty(OK, false);
            result.addProperty(ERROR, "No DSL has been executed yet");
            return result;
        }
        result.addProperty(OK, true);
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

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Report per-node execution times from the most recent DSL graph run.")
                .responseHint("{ok, total_ms, nodes:[{node, ms}, ...]}")
                .build();
    }
}
