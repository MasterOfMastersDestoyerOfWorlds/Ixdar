package ixdar.platform.automation.endpoints.mesh;

import java.io.IOException;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "/mesh/overlay", method = APIMethod.POST)
public class Overlay extends AutomationEndpoint implements AutomationRoute {

    @Override
    public JsonObject endpointHandler(HttpExchange exchange) throws IOException {
        JsonObject body = readBodyJson(exchange);
        String objPath = body.has("path") ? body.get("path").getAsString() : "";
        boolean clear = body.has("clear") && body.get("clear").getAsBoolean();
        try {
            return runtime.runOnMainThread(() -> {
                JsonObject result = new JsonObject();
                if (!(runtime.canvas instanceof MeshNodeViewerScene)) {
                    result.addProperty("ok", false);
                    result.addProperty(
                            "error",
                            "MeshNodeViewerScene is not active");
                    return result;
                }
                MeshNodeViewerScene mvs = (MeshNodeViewerScene) runtime.canvas;
                if (clear) {
                    mvs.clearOverlay();
                    result.addProperty("ok", true);
                    result.addProperty("action", "cleared");
                } else {
                    mvs.loadOverlay(objPath);
                    result.addProperty("ok", true);
                    result.addProperty("action", "loaded");
                    result.addProperty("path", objPath);
                }
                return result;
            });
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty(
                    "error",
                    e.getMessage() == null ? "" : e.getMessage());
            return err;
        }
    }
}
