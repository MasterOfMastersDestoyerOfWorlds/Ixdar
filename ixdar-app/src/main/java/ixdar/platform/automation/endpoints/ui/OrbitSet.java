package ixdar.platform.automation.endpoints.ui;

import java.io.IOException;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "ui/orbit", method = APIMethod.POST)
public class OrbitSet extends AutomationEndpoint implements AutomationRoute {
    @Override
    public JsonObject endpointHandler(HttpExchange exchange) throws IOException {
        JsonObject body = readBodyJson(exchange);
        float azimuth = body.has("azimuth")
                ? body.get("azimuth").getAsFloat()
                : 0f;
        float elevation = body.has("elevation")
                ? body.get("elevation").getAsFloat()
                : 0f;
        float distance = body.has("distance")
                ? body.get("distance").getAsFloat()
                : 3.5f;
        try {
            // First call: set orbit (runs at end of frame N)
            runtime.runOnMainThread(() -> {
                if (!(runtime.canvas instanceof MeshNodeViewerScene mvs)) {
                    JsonObject err = new JsonObject();
                    err.addProperty("ok", false);
                    err.addProperty(
                            "error",
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
                result.addProperty("ok", true);
                result.addProperty("azimuth", azimuth);
                result.addProperty("elevation", elevation);
                result.addProperty("distance", distance);
                return result;
            });
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", e.getMessage());
            return err;
        }
    }
}
