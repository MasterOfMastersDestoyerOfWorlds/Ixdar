package ixdar.platform.automation.endpoints.ui;

import java.io.IOException;

import com.google.gson.JsonObject;

import org.joml.Vector3f;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.scenes.model.ModelScene;

@AutomationRouteAnnotation(path = "ui/orbit", method = APIMethod.POST)
public class OrbitSet extends AutomationEndpoint implements AutomationRoute {
    public static final String AZIMUTH = "azimuth";
    public static final String ELEVATION = "elevation";
    public static final String DISTANCE = "distance";
    public static final String TARGET = "target";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final float NUM_0 = 0f;
    public static final float NUM_3_5 = 3.5f;
    public static final int NUM_3 = 3;
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
        String target = body.has(TARGET) && !body.get(TARGET).isJsonNull()
                ? body.get(TARGET).getAsString()
                : "";
        try {
            // First call: set orbit (runs at end of frame N)
            runtime.runOnMainThread(() -> {
                if (!(runtime.canvas instanceof ModelScene modelScene)) {
                    JsonObject err = new JsonObject();
                    err.addProperty(OK, false);
                    err.addProperty(
                            ERROR,
                            "no ModelScene is active");
                    return err;
                }
                OrbitMouseTrap orbit = modelScene.orbitMouse;
                String[] fields = target.trim().split("\\s*,\\s*");
                if (fields.length == NUM_3) {
                    orbit.setTarget(new Vector3f(
                            Float.parseFloat(fields[0]),
                            Float.parseFloat(fields[1]),
                            Float.parseFloat(fields[2])));
                }
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
                if (runtime.canvas instanceof ModelScene modelScene) {
                    result.add(TARGET, runtime.vector3Array(
                            modelScene.orbitMouse.getTarget(new Vector3f())));
                }
                return result;
            });
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            err.addProperty(ERROR, e.getMessage());
            return err;
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("orbit-set")
                .description("Set the active mesh viewer's camera orbit (azimuth, elevation, distance).")
                .param(AZIMUTH, RouteParamType.FLOAT, false, String.valueOf(NUM_0),
                        "Orbit azimuth angle in radians.", "1.5708")
                .param(ELEVATION, RouteParamType.FLOAT, false, String.valueOf(NUM_0),
                        "Orbit elevation angle in radians.", "0.6")
                .param(DISTANCE, RouteParamType.FLOAT, false, String.valueOf(NUM_3_5),
                        "Camera distance from the orbit target.", "5.0")
                .param(TARGET, RouteParamType.STRING, false, "",
                        "Point the orbit pivots around, as x,y,z; empty leaves the pivot where it is.",
                        "0.1,-0.4,0.2")
                .responseHint("{ok, azimuth, elevation, distance, target}")
                .build();
    }
}
