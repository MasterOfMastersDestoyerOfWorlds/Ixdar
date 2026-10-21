package ixdar.automation.endpoints.ring;

import java.io.IOException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.spline.SurfaceWaypoints;
import ixdar.automation.AutomationEndpoint;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.ring.RingTool;

/**
 * Confirms a ring through authored surface points in the editing scene's ring tool, as clicking
 * them and pressing Enter would, so an agent can ring a groove with no click and no picking.
 */
@AutomationRouteAnnotation(path = "/mesh/rings/add", method = APIMethod.POST)
public class Add extends AutomationEndpoint implements AutomationRoute {

    public static final String POINTS = "points";

    public static final String AUTHORED_POINTS = "authored points";

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        float[] packed = SurfaceWaypoints.parse(body.has(POINTS) ? body.get(POINTS).getAsString()
                : "");
        int pointCount = packed.length / SurfaceWaypoints.COORDINATES_PER_WAYPOINT;
        try {
            return runtime.runOnMainThread(() -> {
                if (!(runtime.canvas instanceof RingScene scene)) {
                    return failure("the ring-tool editing scene is not active");
                }
                RingTool tool = scene.ringTool;
                scene.switchTool(tool);
                int ring = tool.confirmPoints(packed, pointCount, AUTHORED_POINTS);
                if (ring < 0) {
                    return failure(tool.lastError);
                }
                SurfaceSpline spline = tool.confirmedRings.get(ring);
                JsonObject result = success();
                result.addProperty("ring", tool.drawnRingNumber(ring));
                result.addProperty("label", tool.liveLabel(ring));
                result.addProperty("pointCount", pointCount);
                result.addProperty("authoredAnchors", spline.authoredCount());
                result.addProperty("edgeCount", spline.marks.markedEdgeCount);
                result.addProperty("length", spline.path.length);
                JsonArray centroid = new JsonArray();
                for (double coordinate : spline.path.centroid) {
                    centroid.add((float) coordinate);
                }
                result.add("centroid", centroid);
                result.addProperty("unsavedRings", tool.unsavedRingCount());
                result.addProperty("fingerprint", EdgeMarks.fingerprint(scene.halfEdgeSurface(),
                        spline.marks.markedByEdgeId));
                result.addProperty("lastRow", tool.lastRow);
                return result;
            });
        } catch (Exception failure) {
            return failure(failure.getMessage() == null ? failure.toString()
                    : failure.getMessage());
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("rings add")
                .description("Confirm a ring through authored surface points in the ring tool, as "
                        + "clicking them and pressing Enter does: regions count it and Ctrl+S "
                        + "saves it.")
                .param(POINTS, RouteParamType.STRING, true, "",
                        "Surface points as \"x,y,z; x,y,z; ...\", three or more around the part; "
                                + "past the third each goes where it adds least.",
                        "1.35,0,0; 0.45,0.3,0.69; 0.51,-0.3,-0.65")
                .responseHint("{ok, ring, label, pointCount, authoredAnchors, edgeCount, length, "
                        + "centroid:[x,y,z], unsavedRings, fingerprint, lastRow} or {ok:false, "
                        + "error}")
                .build();
    }
}
