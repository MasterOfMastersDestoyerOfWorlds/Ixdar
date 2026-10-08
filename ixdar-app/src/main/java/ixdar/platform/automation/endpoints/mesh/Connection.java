package ixdar.platform.automation.endpoints.mesh;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.geometry.mesh.data.paths.SurfaceConnection;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.connection.ConnectionTool;
import ixdar.scenes.ring.RingScene;

/**
 * Picks two surface positions in the editing scene's connection tool, as two clicks there would,
 * and reports the path joining them and its narrowest cross-section, the neck.
 */
@AutomationRouteAnnotation(path = "/mesh/connection", method = APIMethod.POST)
public class Connection extends AutomationEndpoint implements AutomationRoute {

    public static final String OK = "ok";

    public static final String ERROR = "error";

    public static final String FROM = "from";

    public static final String TO = "to";

    public static final String THROUGH_RINGS = "through_rings";

    public static final String CONFIRM = "confirm";

    public static final String TRUE_TEXT = "true";

    public static final String FALSE_TEXT = "false";

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        float[] from = SurfaceWaypoints.parse(body.has(FROM) ? body.get(FROM).getAsString() : "");
        float[] to = SurfaceWaypoints.parse(body.has(TO) ? body.get(TO).getAsString() : "");
        boolean walls = !body.has(THROUGH_RINGS) || !body.get(THROUGH_RINGS).getAsBoolean();
        boolean confirm = body.has(CONFIRM) && body.get(CONFIRM).getAsBoolean();
        try {
            return runtime.runOnMainThread(() -> {
                JsonObject result = new JsonObject();
                if (!(runtime.canvas instanceof RingScene scene)) {
                    result.addProperty(OK, false);
                    result.addProperty(ERROR, "the ring-tool editing scene is not active");
                    return result;
                }
                if (from.length != SurfaceConnection.COORDINATES_PER_POINT
                        || to.length != SurfaceConnection.COORDINATES_PER_POINT) {
                    result.addProperty(OK, false);
                    result.addProperty(ERROR, "from and to each take one point, \"x,y,z\"");
                    return result;
                }
                ConnectionTool tool = scene.connectionTool;
                scene.switchTool(tool);
                tool.ringsAreWalls = walls;
                boolean found = tool.connectPoints(from, to);
                SurfaceConnection connection = tool.connection;
                result.addProperty(OK, found);
                result.addProperty("connected", connection != null && connection.connected);
                if (connection != null && connection.connected) {
                    result.addProperty("pathLength", connection.pathLength);
                    result.addProperty("pathFaces", connection.pathActiveFace.length);
                    result.addProperty("pathFilledFaces", connection.pathFilledFaceCount);
                    result.addProperty("narrowestGirth", connection.narrowestGirth);
                    result.addProperty("narrowestPoint",
                            ConnectionTool.xyzText(connection.narrowestPoint));
                    result.addProperty("widestGirth", connection.widestGirth);
                    result.addProperty("neckEdges", tool.neckLoopVertexId.length);
                    result.addProperty("girthsCrossingRings", connection.girthsCrossingWalls);
                    result.addProperty("pathMillis", connection.pathMillis);
                    result.addProperty("girthMillis", connection.girthMillis);
                }
                String error = tool.lastError;
                String lastRow = tool.lastRow;
                if (found && confirm) {
                    boolean opened = tool.confirmNeck();
                    result.addProperty("confirmedRing", tool.confirmedRingNumber);
                    result.addProperty("draftOpen", opened);
                    result.addProperty("activeTool", scene.activeTool.toolName());
                    result.addProperty(OK, opened);
                    error = tool.lastError;
                    lastRow = opened ? tool.lastRow : lastRow;
                }
                result.addProperty("lastRow", lastRow);                result.addProperty(ERROR, error);
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
                .commandName("connection")
                .description("Show how two surface points are joined in the editing scene's "
                        + "connection tool: the path and its narrowest cross-section, the neck, "
                        + "optionally confirmed as a ring opened in the ring tool.")
                .param(FROM, RouteParamType.STRING, true, "", "First surface point, \"x,y,z\".",
                        "-0.28,-0.066,0.052")
                .param(TO, RouteParamType.STRING, true, "", "Second surface point, \"x,y,z\".",
                        "-0.2,-0.05,0.04")
                .param(THROUGH_RINGS, RouteParamType.BOOL, false, FALSE_TEXT,
                        "Let the path and the cross-sections cross the rings; by default the "
                                + "rings are walls.", TRUE_TEXT)
                .param(CONFIRM, RouteParamType.BOOL, false, FALSE_TEXT,
                        "Confirm the neck as an unsaved ring and open it in the ring tool as "
                                + "the draft, as Enter does.", TRUE_TEXT)
                .responseHint("{ok, connected, pathLength, pathFaces, pathFilledFaces, "
                        + "narrowestGirth, narrowestPoint, widestGirth, neckEdges, "
                        + "girthsCrossingRings, pathMillis, girthMillis, confirmedRing, draftOpen, "
                        + "activeTool, lastRow, error}")
                .build();
    }
}
