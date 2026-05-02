package ixdar.platform.automation.endpoints.ui;

import java.io.IOException;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "ui/projection", method = APIMethod.POST)
public class ProjectionSet extends AutomationEndpoint implements AutomationRoute {
    public JsonObject endpointHandler(HttpExchange exchange) throws IOException {
        try {
            JsonObject body = readBodyJson(exchange);
            boolean ortho = body.has("orthographic") &&
                    body.get("orthographic").getAsBoolean();
            JsonObject result = new JsonObject();
            if (!(runtime.canvas instanceof MeshNodeViewerScene mvs)) {
                result.addProperty("ok", false);
                result.addProperty(
                        "error",
                        "MeshNodeViewerScene is not active");
                return writeJson(exchange, result);
            } else {
                mvs.setOrthographic(ortho);
                result.addProperty("ok", true);
                result.addProperty("orthographic", mvs.isOrthographic());
                return writeJson(exchange, result);
            }
        } catch (Exception e) {
            return writeError(exchange, 500, e.getMessage());
        }
    }
}
