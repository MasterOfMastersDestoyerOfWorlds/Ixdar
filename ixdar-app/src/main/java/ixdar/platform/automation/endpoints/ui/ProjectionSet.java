package ixdar.platform.automation.endpoints.ui;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "ui/projection", method = APIMethod.POST)
public class ProjectionSet extends AutomationEndpoint implements AutomationRoute {
    public static final String ORTHOGRAPHIC = "orthographic";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    /**
     * {@code POST /ui/projection}: toggle the active mesh-node viewer between
     * orthographic and perspective projection.
     *
     * @param body JSON body containing {@code orthographic} (boolean, default false)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": true, "orthographic": <bool>}} on success, or an error
     *         object when {@link MeshNodeViewerScene} is not the active canvas
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            boolean ortho = body.has(ORTHOGRAPHIC) &&
                    body.get(ORTHOGRAPHIC).getAsBoolean();
            JsonObject result = new JsonObject();
            if (!(runtime.canvas instanceof MeshNodeViewerScene mvs)) {
                result.addProperty(OK, false);
                result.addProperty(
                        ERROR,
                        "MeshNodeViewerScene is not active");
                return result;
            } else {
                mvs.setOrthographic(ortho);
                result.addProperty(OK, true);
                result.addProperty(ORTHOGRAPHIC, mvs.isOrthographic());
                return result;
            }
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            err.addProperty(ERROR, e.getMessage());
            return err;
        }
    }
}
