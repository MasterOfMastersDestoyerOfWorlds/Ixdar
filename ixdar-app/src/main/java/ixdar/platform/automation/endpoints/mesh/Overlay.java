package ixdar.platform.automation.endpoints.mesh;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "/mesh/overlay", method = APIMethod.POST)
public class Overlay extends AutomationEndpoint implements AutomationRoute {
    public static final String PATH = "path";
    public static final String CLEAR = "clear";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String ACTION = "action";

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String objPath = body.has(PATH) ? body.get(PATH).getAsString() : "";
        boolean clear = body.has(CLEAR) && body.get(CLEAR).getAsBoolean();
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
                if (clear) {
                    mvs.clearOverlay();
                    result.addProperty(OK, true);
                    result.addProperty(ACTION, "cleared");
                } else {
                    mvs.loadOverlay(objPath);
                    result.addProperty(OK, true);
                    result.addProperty(ACTION, "loaded");
                    result.addProperty(PATH, objPath);
                }
                return result;
            });
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            err.addProperty(
                    ERROR,
                    e.getMessage() == null ? "" : e.getMessage());
            return err;
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Load an OBJ overlay onto the active viewer mesh, or clear the current overlay.")
                .param(PATH, RouteParamType.STRING, false, "",
                        "Path to an OBJ mesh to overlay; ignored when clearing.", "~/Blends/Hand/overlay.obj")
                .param(CLEAR, RouteParamType.BOOL, false, "false",
                        "Remove the existing overlay instead of loading one.", "true")
                .responseHint("{ok, action, path}")
                .build();
    }
}
