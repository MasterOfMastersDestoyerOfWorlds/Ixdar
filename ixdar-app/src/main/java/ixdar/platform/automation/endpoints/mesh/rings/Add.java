package ixdar.platform.automation.endpoints.mesh.rings;

import java.io.IOException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.SurfaceRing;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.ring.RingScene;

/**
 * Adds a ring to the shown surface through authored coordinates, so an agent can loop a limb from
 * skeleton or patch positions with no click and no picking.
 */
@AutomationRouteAnnotation(path = "/mesh/rings/add", method = APIMethod.POST)
public class Add extends AutomationEndpoint implements AutomationRoute {

    /** Body key holding the ring's surface points. */
    public static final String POINTS = "points";

    /** Body key choosing whether the closed walk is tightened. */
    public static final String TIGHTEN = "tighten";

    /** Response key reporting success. */
    public static final String OK = "ok";

    /** Response key carrying the failure text when {@link #OK} is false. */
    public static final String ERROR = "error";

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String points = body.has(POINTS) ? body.get(POINTS).getAsString() : "";
        boolean tighten = !body.has(TIGHTEN) || body.get(TIGHTEN).getAsBoolean();
        try {
            return runtime.runOnMainThread(() -> ringRow(points, tighten));
        } catch (Exception failure) {
            return failed(failure.getMessage() == null ? failure.toString()
                    : failure.getMessage());
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("rings add")
                .description("Ring the shown surface through authored points and report the ring "
                        + "row: centroid, length and marked edge count.")
                .param(POINTS, RouteParamType.STRING, true, "",
                        "Surface points as \"x,y,z; x,y,z; ...\"; three or more bound a loop.",
                        "1.35,0,0; 0.45,0.3,0.69; 0.51,-0.3,-0.65")
                .param(TIGHTEN, RouteParamType.BOOL, false, "true",
                        "Tighten the closed walk into a geodesic with FlipOut.", "false")
                .responseHint("{ok, waypointCount, points, edgeCount, length, seedLength, "
                        + "centroid:[x,y,z], fingerprint}")
                .build();
    }

    private JsonObject ringRow(String points, boolean tighten) {
        if (!(runtime.canvas instanceof RingScene scene)) {
            return failed("RingScene is not active");
        }
        MeshTopology mesh = scene.halfEdgeSurface();
        if (mesh == null || mesh.faceCount() == 0) {
            return failed("no mesh is loaded to ring");
        }
        float[] packed = SurfaceWaypoints.parse(points);
        int waypointCount = packed.length / SurfaceWaypoints.COORDINATES_PER_WAYPOINT;
        scene.ringWaypointsXyz = packed;
        scene.ringWaypointCount = waypointCount;
        SurfaceRing ring = tighten ? scene.closeRing() : seedOnly(scene, mesh);

        JsonObject result = new JsonObject();
        result.addProperty(OK, true);
        result.addProperty("waypointCount", waypointCount);
        result.addProperty(POINTS, SurfaceWaypoints.format(packed, waypointCount));
        result.addProperty("edgeCount", ring.markedEdgeCount);
        result.addProperty("length", ring.length);
        result.addProperty("seedLength", ring.seedLength);
        JsonArray centroid = new JsonArray();
        centroid.add(ring.centroidX);
        centroid.add(ring.centroidY);
        centroid.add(ring.centroidZ);
        result.add("centroid", centroid);
        result.addProperty("fingerprint", EdgeMarks.fingerprint(mesh, ring.markedByEdgeId));
        return result;
    }

    private static SurfaceRing seedOnly(RingScene scene, MeshTopology mesh) {
        SurfaceRing ring = SurfaceRing.through(mesh, scene.ringWaypointsXyz,
                scene.ringWaypointCount, true, 0, 0);
        scene.ring = ring;
        scene.showRing(ring);
        return ring;
    }

    private static JsonObject failed(String message) {
        JsonObject error = new JsonObject();
        error.addProperty(OK, false);
        error.addProperty(ERROR, message);
        return error;
    }
}
