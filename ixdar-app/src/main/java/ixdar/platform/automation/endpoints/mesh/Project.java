package ixdar.platform.automation.endpoints.mesh;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.geometry.mesh.data.paths.SurfacePicker;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.platform.Platforms;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.mesh.MeshNodeViewerScene;

/**
 * Places a surface point in the screenshot: the pixel it lands on and whether the surface in
 * front of it hides it, the reverse of {@link Pick}, touching no tool.
 */
@AutomationRouteAnnotation(path = "/mesh/project", method = APIMethod.POST)
public class Project extends AutomationEndpoint implements AutomationRoute {

    public static final String OK = "ok";

    public static final String ERROR = "error";

    public static final String POINT = "point";

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        float[] point = SurfaceWaypoints.parse(body.has(POINT) ? body.get(POINT).getAsString()
                : "");
        try {
            return runtime.runOnMainThread(() -> {
                JsonObject result = new JsonObject();
                if (!(runtime.canvas instanceof MeshNodeViewerScene viewer)) {
                    result.addProperty(OK, false);
                    result.addProperty(ERROR, "no mesh viewer scene is active");
                    return result;
                }
                String refusal = point.length == SurfacePicker.COORDINATES_PER_POINT
                        ? viewer.pickRefusal()
                        : "point takes one point, \"x,y,z\"";
                if (!refusal.isEmpty()) {
                    result.addProperty(OK, false);
                    result.addProperty(ERROR, refusal);
                    return result;
                }
                float[] pixel = new float[2];
                SurfacePicker occluder = new SurfacePicker();
                boolean inFront = viewer.projectPoint(point[0], point[1], point[2], pixel,
                        occluder);
                boolean onScreen = inFront && pixel[0] > -MeshNodeViewerScene.PIXEL_CENTRE
                        && pixel[1] > -MeshNodeViewerScene.PIXEL_CENTRE
                        && pixel[0] < Platforms.get().getFrameBufferWidth()
                                - MeshNodeViewerScene.PIXEL_CENTRE
                        && pixel[1] < Platforms.get().getFrameBufferHeight()
                                - MeshNodeViewerScene.PIXEL_CENTRE;
                boolean occluded = occluder.faceId >= 0;
                result.addProperty(OK, true);
                result.addProperty("inFront", inFront);
                if (!inFront) {
                    return result;
                }
                result.addProperty("x", pixel[0]);
                result.addProperty("y", pixel[1]);
                result.addProperty("onScreen", onScreen);
                result.addProperty("occluded", occluded);
                result.addProperty("visible", onScreen && !occluded);
                if (occluded) {
                    result.addProperty("occluderFaceId", occluder.faceId);
                    result.addProperty("occluderPoint", SurfaceWaypoints.format(new float[] {
                        occluder.pointX, occluder.pointY, occluder.pointZ }, 1));
                }
                return result;
            });
        } catch (Exception failure) {
            JsonObject error = new JsonObject();
            error.addProperty(OK, false);
            error.addProperty(ERROR, String.valueOf(failure.getMessage()));
            return error;
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("project")
                .description("Place a surface point in the screenshot: the pixel it lands on and "
                        + "whether the surface in front of it hides it, touching no tool.")
                .param(POINT, RouteParamType.STRING, true, "", "World point, \"x,y,z\".",
                        "-0.28,-0.066,0.052")
                .responseHint("{ok, inFront, x, y, onScreen, occluded, visible, occluderFaceId, "
                        + "occluderPoint:\"x,y,z\", error}; x and y are framebuffer pixels as "
                        + "pick takes them, whole numbers at pixel centres")
                .build();
    }
}
