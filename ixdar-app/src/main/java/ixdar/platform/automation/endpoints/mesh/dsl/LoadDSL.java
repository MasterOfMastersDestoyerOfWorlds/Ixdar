package ixdar.platform.automation.endpoints.mesh.dsl;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.automation.endpoints.AutomationRuntime;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "mesh/dsl", method = APIMethod.POST)
public class LoadDSL extends AutomationEndpoint implements AutomationRoute {

    @Override
    public JsonObject endpointHandler(JsonObject body)
            throws IOException {
        String dslName = body.has("name") ? body.get("name").getAsString() : "";
        String node = body.has("node") ? body.get("node").getAsString() : "";
        String port = body.has("port")
                ? body.get("port").getAsString()
                : "geometry";

        if (dslName.isEmpty()) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", "Missing required field: name");
            return err;

        }

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
                mvs.loadDsl(dslName, node, port);
                result.addProperty("ok", true);
                result.addProperty("dsl", dslName);
                result.addProperty("node", node != null ? node : "");
                result.addProperty("port", port != null ? port : "geometry");
                result.addProperty("vertices", mvs.getMeshVertexCount());
                result.addProperty("faces", mvs.getMeshFaceCount());
                AutomationRuntime.appendTiming(mvs, result);
                return result;
            });
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            StringBuilder msg = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (msg.length() > 0)
                    msg.append(" <- ");
                msg
                        .append(t.getClass().getSimpleName())
                        .append(": ")
                        .append(t.getMessage());
            }
            err.addProperty("error", msg.toString());
            return err;
        }
    }
}
