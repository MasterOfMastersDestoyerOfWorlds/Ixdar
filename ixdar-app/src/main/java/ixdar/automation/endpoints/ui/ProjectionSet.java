package ixdar.automation.endpoints.ui;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "ui/projection", method = APIMethod.POST)
public class ProjectionSet extends AutomationEndpoint implements AutomationRoute {
    public static final String ORTHOGRAPHIC = "orthographic";
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
            if (!(runtime.canvas instanceof MeshNodeViewerScene mvs)) {
                return failure("MeshNodeViewerScene is not active");
            }
            mvs.setOrthographic(ortho);
            JsonObject result = success();
            result.addProperty(ORTHOGRAPHIC, mvs.isOrthographic());
            return result;
        } catch (Exception e) {
            return failure(e.getMessage());
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("projection-set")
                .description("Toggle the active mesh viewer between orthographic and perspective projection.")
                .param(ORTHOGRAPHIC, RouteParamType.BOOL, false, "false",
                        "True selects orthographic projection; false selects perspective.", "true")
                .responseHint("{ok, orthographic}")
                .build();
    }
}
