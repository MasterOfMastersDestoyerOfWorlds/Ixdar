package ixdar.platform.automation.endpoints.ui;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "ui/orbit", method = APIMethod.GET)
public class OrbitGet extends AutomationEndpoint implements AutomationRoute {
    public static final String OK = "ok";
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        JsonObject result = new JsonObject();
        if (!(runtime.canvas instanceof MeshNodeViewerScene mvs)) {
            result.addProperty(OK, false);
            result.addProperty("error", "MeshNodeViewerScene is not active");
            return result;
        }
        OrbitMouseTrap orbit = mvs.getOrbitMouse();
        result.addProperty(OK, true);
        result.addProperty("azimuth", orbit.getAzimuth());
        result.addProperty("elevation", orbit.getElevation());
        result.addProperty("distance", orbit.getDistance());
        if (mvs.getMesh() != null) {
            result.addProperty("mesh_radius", mvs.getMeshRadius());
        }
        return result;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("orbit-get")
                .description("Report the active mesh viewer's current camera orbit and mesh radius.")
                .responseHint("{ok, azimuth, elevation, distance, mesh_radius?}")
                .build();
    }
}
