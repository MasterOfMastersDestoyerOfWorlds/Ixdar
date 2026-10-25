package ixdar.automation.endpoints.ring;

import java.io.IOException;

import org.joml.Vector3f;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.geometry.mesh.data.labels.MeshLabels;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.geometry.mesh.data.paths.query.SurfacePicker;
import ixdar.geometry.mesh.data.paths.spline.SurfaceWaypoints;
import ixdar.automation.AutomationEndpoint;
import ixdar.scenes.mesh.MeshNodeViewerScene;
import ixdar.scenes.ring.RingScene;

/**
 * Names the surface under a screenshot pixel: the point, its face, nearest vertex and normal, and
 * the ring region it lies in with that region's part name, without touching any tool.
 */
@AutomationRouteAnnotation(path = "/mesh/pick", method = APIMethod.POST)
public class Pick extends AutomationEndpoint implements AutomationRoute {

    public static final String X = "x";

    public static final String Y = "y";

    public static final String NO_DEFAULT = "";

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        float pixelX = body.has(X) ? body.get(X).getAsFloat() : 0f;
        float pixelY = body.has(Y) ? body.get(Y).getAsFloat() : 0f;
        try {
            return runtime.runOnMainThread(() -> {
                if (!(runtime.canvas instanceof MeshNodeViewerScene viewer)) {
                    return failure("no mesh viewer scene is active");
                }
                String refusal = viewer.pickRefusal();
                if (!refusal.isEmpty()) {
                    return failure(refusal);
                }
                MeshTopology surface = viewer.halfEdgeSurface();
                SurfacePicker picker = new SurfacePicker();
                boolean hit = viewer.pickPixel(pixelX, pixelY, picker);
                JsonObject result = success();
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
                    int[] labelByRegion = scene.regionLayer.partLabels.labelByRegion;
                    int label = region < 0 || region >= labelByRegion.length
                            ? MeshLabels.UNLABELLED : labelByRegion[region];
                    result.addProperty("part", label == MeshLabels.UNLABELLED ? ""
                            : scene.regionLayer.partLabels.names.get(label));
                }
                return result;
            });
        } catch (Exception failure) {
            return failure(String.valueOf(failure.getMessage()));
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("pick")
                .description("Name the surface under a screenshot pixel: point, face, nearest "
                        + "vertex, normal, ring region and its part name, touching no tool and "
                        + "no hover.")
                .param(X, RouteParamType.FLOAT, true, NO_DEFAULT,
                        "Framebuffer x from the left, as in a screenshot; whole numbers are "
                                + "pixel centres.", "512")
                .param(Y, RouteParamType.FLOAT, true, NO_DEFAULT,
                        "Framebuffer y from the top, as in a screenshot.", "384")
                .responseHint("{ok, hit, point:\"x,y,z\", normal:[x,y,z], faceId, vertexId, "
                        + "vertex:\"x,y,z\", region, regionBoundedBy, part, error}; region and "
                        + "part (empty when unlabelled) are present once the editing scene's "
                        + "regions are built")
                .build();
    }
}
