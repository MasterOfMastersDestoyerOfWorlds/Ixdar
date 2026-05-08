package ixdar.platform.automation.endpoints.ui;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "ui/orbit", method = APIMethod.POST)
public class OrbitSet extends AutomationEndpoint implements AutomationRoute {
    public static final String AZIMUTH = "azimuth";
    public static final String ELEVATION = "elevation";
    public static final String DISTANCE = "distance";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final float NUM_0 = 0f;
    public static final float NUM_3_5 = 3.5f;
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        float azimuth = body.has(AZIMUTH)
                ? body.get(AZIMUTH).getAsFloat()
                : NUM_0;
        float elevation = body.has(ELEVATION)
                ? body.get(ELEVATION).getAsFloat()
                : NUM_0;
        float distance = body.has(DISTANCE)
                ? body.get(DISTANCE).getAsFloat()
                : NUM_3_5;
        try {
            // First call: set orbit (runs at end of frame N)
            runtime.runOnMainThread(() -> {
                if (!(runtime.canvas instanceof MeshNodeViewerScene mvs)) {
                    JsonObject err = new JsonObject();
                    err.addProperty(OK, false);
                    err.addProperty(
                            ERROR,
                            "MeshNodeViewerScene is not active");
                    return err;
                }
                OrbitMouseTrap orbit = mvs.getOrbitMouse();
                orbit.setOrbit(azimuth, elevation, distance);
                return null;
            });
            // Second call: just returns after the next frame renders with the new orbit
            return runtime.runOnMainThread(() -> {
                JsonObject result = new JsonObject();
                result.addProperty(OK, true);
                result.addProperty(AZIMUTH, azimuth);
                result.addProperty(ELEVATION, elevation);
                result.addProperty(DISTANCE, distance);
                return result;
            });
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            err.addProperty(ERROR, e.getMessage());
            return err;
        }
    }
}
