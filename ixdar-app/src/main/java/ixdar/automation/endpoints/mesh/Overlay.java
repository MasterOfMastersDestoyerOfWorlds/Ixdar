package ixdar.automation.endpoints.mesh;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "/mesh/overlay", method = APIMethod.POST)
public class Overlay extends AutomationEndpoint implements AutomationRoute {
    public static final String PATH = "path";
    public static final String CLEAR = "clear";
    public static final String ACTION = "action";

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String objPath = body.has(PATH) ? body.get(PATH).getAsString() : "";
        boolean clear = body.has(CLEAR) && body.get(CLEAR).getAsBoolean();
        try {
            return runtime.runOnMainThread(() -> {
                if (!(runtime.canvas instanceof MeshNodeViewerScene)) {
                    return failure("MeshNodeViewerScene is not active");
                }
                MeshNodeViewerScene mvs = (MeshNodeViewerScene) runtime.canvas;
                JsonObject result = success();
                if (clear) {
                    mvs.clearOverlay();
                    result.addProperty(ACTION, "cleared");
                } else {
                    mvs.loadOverlay(objPath);
                    result.addProperty(ACTION, "loaded");
                    result.addProperty(PATH, objPath);
                }
                return result;
            });
        } catch (Exception e) {
            return failure(e.getMessage() == null ? "" : e.getMessage());
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
