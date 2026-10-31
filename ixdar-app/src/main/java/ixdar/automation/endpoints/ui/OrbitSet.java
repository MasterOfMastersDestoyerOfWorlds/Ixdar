package ixdar.automation.endpoints.ui;

import java.io.IOException;

import com.google.gson.JsonObject;

import org.joml.Vector3f;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.graphics.cameras.Camera3D;
import ixdar.scenes.model.ModelScene;

@AutomationRouteAnnotation(path = "ui/orbit", method = APIMethod.POST)
public class OrbitSet extends AutomationEndpoint implements AutomationRoute {
    public static final String AZIMUTH = "azimuth";
    public static final String ELEVATION = "elevation";
    public static final String DISTANCE = "distance";
    public static final String TARGET = "target";
    public static final String FIELD_OF_VIEW = "fov";
    public static final String ROLL = "roll";
    public static final float DEFAULT_DISTANCE = 3.5f;
    public static final int TARGET_FIELD_COUNT = 3;
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        float azimuth = body.has(AZIMUTH)
                ? body.get(AZIMUTH).getAsFloat()
                : 0f;
        float elevation = body.has(ELEVATION)
                ? body.get(ELEVATION).getAsFloat()
                : 0f;
        float distance = body.has(DISTANCE)
                ? body.get(DISTANCE).getAsFloat()
                : DEFAULT_DISTANCE;
        String target = body.has(TARGET) && !body.get(TARGET).isJsonNull()
                ? body.get(TARGET).getAsString()
                : "";
        try {
            // First call: set orbit (runs at end of frame N)
            runtime.runOnMainThread(() -> {
                if (!(runtime.canvas instanceof ModelScene modelScene)) {
                    return failure("no ModelScene is active");
                }
                Camera3D orbit = modelScene.camera;
                String[] fields = target.trim().split("\\s*,\\s*");
                if (fields.length == TARGET_FIELD_COUNT) {
                    orbit.setTarget(new Vector3f(
                            Float.parseFloat(fields[0]),
                            Float.parseFloat(fields[1]),
                            Float.parseFloat(fields[2])));
                }
                if (body.has(FIELD_OF_VIEW) && !body.get(FIELD_OF_VIEW).isJsonNull()) {
                    orbit.fov = body.get(FIELD_OF_VIEW).getAsFloat();
                }
                if (body.has(ROLL) && !body.get(ROLL).isJsonNull()) {
                    orbit.roll = body.get(ROLL).getAsFloat();
                }
                orbit.setOrbit(azimuth, elevation, distance);
                return null;
            });
            // Second call: just returns after the next frame renders with the new orbit
            return runtime.runOnMainThread(() -> {
                JsonObject result = success();
                result.addProperty(AZIMUTH, azimuth);
                result.addProperty(ELEVATION, elevation);
                result.addProperty(DISTANCE, distance);
                if (runtime.canvas instanceof ModelScene modelScene) {
                    result.add(TARGET, runtime.vector3Array(modelScene.camera.orbitTarget));
                    result.addProperty(FIELD_OF_VIEW, modelScene.camera.fov);
                    result.addProperty(ROLL, modelScene.camera.roll);
                }
                return result;
            });
        } catch (Exception e) {
            return failure(e.getMessage());
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("orbit-set")
                .description("Set the active mesh viewer's camera orbit (azimuth, elevation, distance).")
                .param(AZIMUTH, RouteParamType.FLOAT, false, String.valueOf(0f),
                        "Orbit azimuth angle in radians.", "1.5708")
                .param(ELEVATION, RouteParamType.FLOAT, false, String.valueOf(0f),
                        "Orbit elevation angle in radians.", "0.6")
                .param(DISTANCE, RouteParamType.FLOAT, false, String.valueOf(DEFAULT_DISTANCE),
                        "Camera distance from the orbit target.", "5.0")
                .param(TARGET, RouteParamType.STRING, false, "",
                        "Point the orbit pivots around, as x,y,z; empty leaves the pivot where it is.",
                        "0.1,-0.4,0.2")
                .param(FIELD_OF_VIEW, RouteParamType.FLOAT, false, "",
                        "Vertical field of view in degrees; omitted keeps the current one.", "30")
                .param(ROLL, RouteParamType.FLOAT, false, "",
                        "Turn of the camera about its view direction in radians, positive turning "
                                + "the picture counter-clockwise; omitted keeps the current one.", "0.05")
                .responseHint("{ok, azimuth, elevation, distance, target, fov, roll}")
                .build();
    }
}
