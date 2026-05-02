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
public class ProjectionGet extends AutomationEndpoint implements AutomationRoute {
    public JsonObject endpointHandler(HttpExchange exchange) throws IOException {
        JsonObject result = new JsonObject();
        if (!(runtime.canvas instanceof MeshNodeViewerScene mvs)) {
            result.addProperty("ok", false);
            result.addProperty("error", "MeshNodeViewerScene is not active");
            return result;
        }
        result.addProperty("ok", true);
        result.addProperty("orthographic", mvs.isOrthographic());
        return result;
    }
}
