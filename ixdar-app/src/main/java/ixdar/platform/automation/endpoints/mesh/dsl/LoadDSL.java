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
    public static final String NAME = "name";
    public static final String NODE = "node";
    public static final String PORT = "port";
    public static final String GEOMETRY = "geometry";
    public static final String OK = "ok";
    public static final String ERROR = "error";

    @Override
    public JsonObject endpointHandler(JsonObject body)
            throws IOException {
        String dslName = body.has(NAME) ? body.get(NAME).getAsString() : "";
        String node = body.has(NODE) ? body.get(NODE).getAsString() : "";
        String port = body.has(PORT)
                ? body.get(PORT).getAsString()
                : GEOMETRY;

        if (dslName.isEmpty()) {
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            err.addProperty(ERROR, "Missing required field: name");
            return err;

        }

        try {
            return runtime.runOnMainThread(() -> {
                JsonObject result = new JsonObject();
                if (!(runtime.canvas instanceof MeshNodeViewerScene)) {
                    result.addProperty(OK, false);
                    result.addProperty(
                            ERROR,
                            "MeshNodeViewerScene is not active");
                    return result;
                }
                MeshNodeViewerScene mvs = (MeshNodeViewerScene) runtime.canvas;
                mvs.loadDsl(dslName, node, port);
                result.addProperty(OK, true);
                result.addProperty("dsl", dslName);
                result.addProperty(NODE, node != null ? node : "");
                result.addProperty(PORT, port != null ? port : GEOMETRY);
                result.addProperty("vertices", mvs.getMeshVertexCount());
                result.addProperty("faces", mvs.getMeshFaceCount());
                AutomationRuntime.appendTiming(mvs, result);
                return result;
            });
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            StringBuilder msg = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (msg.length() > 0)
                    msg.append(" <- ");
                msg
                        .append(t.getClass().getSimpleName())
                        .append(": ")
                        .append(t.getMessage());
            }
            err.addProperty(ERROR, msg.toString());
            return err;
        }
    }
}
