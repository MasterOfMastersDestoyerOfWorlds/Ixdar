package ixdar.platform.automation.endpoints.mesh;

import java.io.IOException;

import org.joml.Vector3f;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.RingRegions;
import ixdar.geometry.mesh.data.paths.SurfacePicker;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.mesh.MeshNodeViewerScene;
import ixdar.scenes.ring.RingScene;

/**
 * Names the surface under a screenshot pixel: the point, its face, nearest vertex and normal, and
 * the ring region it lies in, without hovering, clicking or touching any tool.
 */
@AutomationRouteAnnotation(path = "/mesh/pick", method = APIMethod.POST)
public class Pick extends AutomationEndpoint implements AutomationRoute {

    public static final String OK = "ok";

    public static final String ERROR = "error";

    public static final String X = "x";

    public static final String Y = "y";

    public static final String NO_DEFAULT = "";

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        float pixelX = body.has(X) ? body.get(X).getAsFloat() : 0f;
        float pixelY = body.has(Y) ? body.get(Y).getAsFloat() : 0f;
        try {
            return runtime.runOnMainThread(() -> {
                JsonObject result = new JsonObject();
                if (!(runtime.canvas instanceof MeshNodeViewerScene viewer)) {
                    result.addProperty(OK, false);
                    result.addProperty(ERROR, "no mesh viewer scene is active");
                    return result;
                }
                String refusal = viewer.pickRefusal();
                if (!refusal.isEmpty()) {
                    result.addProperty(OK, false);
                    result.addProperty(ERROR, refusal);
                    return result;
                }
                MeshTopology surface = viewer.halfEdgeSurface();
                SurfacePicker picker = new SurfacePicker();
                boolean hit = viewer.pickPixel(pixelX, pixelY, picker);
                result.addProperty(OK, true);
                result.addProperty("hit", hit);
                if (!hit) {
                    return result;
                }
                float[] point = { picker.pointX, picker.pointY, picker.pointZ };
                result.addProperty("point", SurfaceWaypoints.format(point, 1));
                Vector3f normal = surface.faceNormal(picker.faceId, new Vector3f());
                JsonArray normalRow = new JsonArray();
                normalRow.add(normal.x);
                normalRow.add(normal.y);
                normalRow.add(normal.z);
                result.add("normal", normalRow);
                result.addProperty("faceId", picker.faceId);
                int vertexId = picker.nearestCornerVertexId(surface);
                Vector3f vertex = surface.vertexPosition(vertexId, new Vector3f());
                result.addProperty("vertexId", vertexId);
                result.addProperty("vertex", SurfaceWaypoints.format(
                        new float[] { vertex.x, vertex.y, vertex.z }, 1));
                if (viewer instanceof RingScene scene && scene.regionLayer.regions != null
                        && scene.regionLayer.regions.mesh == surface) {
                    RingRegions regions = scene.regionLayer.regions;
                    int region = regions.regionOfFace(picker.faceId);
                    result.addProperty("region", region);
                    result.addProperty("regionBoundedBy",
                            region < 0 ? "" : regions.boundingRingText(region));
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
                .commandName("pick")
                .description("Name the surface under a screenshot pixel: point, face, nearest "
                        + "vertex, normal and ring region, touching no tool and no hover.")
                .param(X, RouteParamType.FLOAT, true, NO_DEFAULT,
                        "Framebuffer x from the left, as in a screenshot; whole numbers are "
                                + "pixel centres.", "512")
                .param(Y, RouteParamType.FLOAT, true, NO_DEFAULT,
                        "Framebuffer y from the top, as in a screenshot.", "384")
                .responseHint("{ok, hit, point:\"x,y,z\", normal:[x,y,z], faceId, vertexId, "
                        + "vertex:\"x,y,z\", region, regionBoundedBy, error}; region is present "
                        + "once the editing scene's regions are built")
                .build();
    }
}
