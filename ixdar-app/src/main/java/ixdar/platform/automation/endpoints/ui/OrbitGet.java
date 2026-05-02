package ixdar.platform.automation.endpoints.ui;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "ui/orbit", method = APIMethod.GET)
public class OrbitGet extends AutomationEndpoint implements AutomationRoute {
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        JsonObject result = new JsonObject();
        if (!(runtime.canvas instanceof MeshNodeViewerScene mvs)) {
            result.addProperty("ok", false);
            result.addProperty("error", "MeshNodeViewerScene is not active");
            return result;
        }
        OrbitMouseTrap orbit = mvs.getOrbitMouse();
        result.addProperty("ok", true);
        result.addProperty("azimuth", orbit.getAzimuth());
        result.addProperty("elevation", orbit.getElevation());
        result.addProperty("distance", orbit.getDistance());
        if (mvs.getMesh() != null) {
            result.addProperty("mesh_radius", mvs.getMeshRadius());
        }
        return result;
    }
}
