package ixdar.automation.endpoints.ui;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.automation.AutomationEndpoint;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.cameras.Camera3D;
import ixdar.scenes.mesh.MeshNodeViewerScene;
import ixdar.scenes.model.ModelScene;

@AutomationRouteAnnotation(path = "ui/orbit", method = APIMethod.GET)
public class OrbitGet extends AutomationEndpoint implements AutomationRoute {
    public static final String OK = "ok";
    public static final String TARGET = "target";

    /**
     * Report the camera the active scene's mouse drives, its pose in 3D or its pan and zoom in 2D,
     * plus the orbit and mesh radius of a model scene.
     *
     * @param body unused
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {ok, camera, azimuth?, elevation?, distance?, target?, mesh_radius?}}, or an
     *         error object when no scene is bound
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        JsonObject result = new JsonObject();
        if (runtime.canvas == null || runtime.canvas.mouse == null) {
            result.addProperty(OK, false);
            result.addProperty("error", "no scene is active");
            return result;
        }
        result.addProperty(OK, true);
        JsonObject pose = new JsonObject();
        Camera camera = runtime.canvas.mouse.camera;
        if (camera instanceof Camera3D camera3D) {
            pose.add("position", runtime.vector3Array(camera3D.position));
            pose.add(TARGET, runtime.vector3Array(camera3D.target));
            pose.add("up", runtime.vector3Array(camera3D.up));
            pose.addProperty("yaw", camera3D.yaw);
            pose.addProperty("pitch", camera3D.pitch);
            pose.addProperty("fov", camera3D.fov);
        } else if (camera instanceof Camera2D camera2D) {
            pose.addProperty("scale", camera2D.ScaleFactor);
            pose.addProperty("panX", camera2D.PanX);
            pose.addProperty("panY", camera2D.PanY);
        }
        result.add("camera", pose);
        if (!(runtime.canvas instanceof ModelScene modelScene)) {
            return result;
        }
        Camera3D orbit = modelScene.camera;
        result.addProperty("azimuth", orbit.azimuth);
        result.addProperty("elevation", orbit.elevation);
        result.addProperty("distance", orbit.distance);
        result.add(TARGET, runtime.vector3Array(orbit.orbitTarget));
        if (modelScene instanceof MeshNodeViewerScene mvs && mvs.getMesh() != null) {
            result.addProperty("mesh_radius", mvs.getMeshRadius());
        }
        return result;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("orbit-get")
                .description("Report the active scene's camera, its pose in 3D or its pan and zoom in 2D, "
                        + "and a mesh viewer's orbit and mesh radius.")
                .responseHint("{ok, camera, azimuth?, elevation?, distance?, target?, mesh_radius?}")
                .build();
    }
}
