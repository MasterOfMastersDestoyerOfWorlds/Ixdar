package ixdar.automation.endpoints.ui;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.automation.AutomationEndpoint;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "ui/projection", method = APIMethod.GET)
public class ProjectionGet extends AutomationEndpoint implements AutomationRoute {
    /**
     * {@code GET /ui/projection}: report whether the active mesh-node viewer is
     * currently orthographic.
     *
     * @param body request body (unused)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": true, "orthographic": <bool>}} on success, or an error
     *         object when {@link MeshNodeViewerScene} is not the active canvas
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        if (!(runtime.canvas instanceof MeshNodeViewerScene mvs)) {
            return failure("MeshNodeViewerScene is not active");
        }
        JsonObject result = success();
        result.addProperty("orthographic", mvs.isOrthographic());
        return result;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("projection-get")
                .description("Report whether the active mesh viewer is using orthographic projection.")
                .responseHint("{ok, orthographic}")
                .build();
    }
}
