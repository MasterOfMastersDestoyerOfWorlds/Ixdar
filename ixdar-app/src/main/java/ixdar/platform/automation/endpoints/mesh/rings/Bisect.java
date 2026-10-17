package ixdar.platform.automation.endpoints.mesh.rings;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.ring.RingScene;
import ixdar.scenes.ring.RingTool;

/**
 * Draws a bisector in the ring scene through points given as coordinates, as K and clicks do: the
 * first and last are its ends, snapped onto walls, the rest anchors placed where they add least.
 */
@AutomationRouteAnnotation(path = "/mesh/rings/bisect", method = APIMethod.POST)
public class Bisect extends AutomationEndpoint implements AutomationRoute {

    public static final String POINTS = "points";

    public static final String CONFIRM = "confirm";

    public static final String OK = "ok";

    public static final String ERROR = "error";

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String points = body.has(POINTS) ? body.get(POINTS).getAsString() : "";
        boolean confirm = body.has(CONFIRM) && body.get(CONFIRM).getAsBoolean();
        try {
            return runtime.runOnMainThread(() -> {
                JsonObject result = new JsonObject();
                if (!(runtime.canvas instanceof RingScene scene) || !scene.ringTool.active
                        || scene.ringTool.geodesics == null) {
                    result.addProperty(OK, false);
                    result.addProperty(ERROR, "the ring tool of a ring scene is not active "
                            + "(Ctrl+R)");
                    return result;
                }
                RingTool tool = scene.ringTool;
                float[] packed = SurfaceWaypoints.parse(points);
                int count = packed.length / SurfaceWaypoints.COORDINATES_PER_WAYPOINT;
                int[] vertexIds = SurfaceWaypoints.snap(tool.geodesics.metric.nearestVertex,
                        packed, count);
                boolean placed = count >= 2 && (tool.bisecting || tool.startBisector());
                // The ends first, in the order given, then each inner point as a click on the
                // open bisector.
                int[] order = new int[count];
                order[Math.min(1, count - 1)] = count - 1;
                for (int inner = 1; inner + 1 < count; inner++) {
                    order[inner + 1] = inner;
                }
                for (int step = 0; placed && step < count; step++) {
                    int point = order[step];
                    int base = SurfaceWaypoints.COORDINATES_PER_WAYPOINT * point;
                    float[] xyz = { packed[base], packed[base + 1], packed[base + 2] };
                    placed = step < 2 ? tool.placeBisectorEnd(vertexIds[point], xyz)
                            : tool.addAuthoredAnchor(vertexIds[point]);
                }
                if (count < 2) {
                    tool.lastError = "a bisector takes at least two points, its two ends";
                }
                result.addProperty(OK, placed);
                result.addProperty("anchors", tool.draftAuthoredVertexId.length);
                result.addProperty("simple", tool.draftSimple);
                if (tool.draft != null && tool.draftBisectorEnds != null) {
                    result.addProperty("firstWall",
                            tool.liveLabel(tool.ringOfSerial(tool.draftBisectorEnds[0])));
                    result.addProperty("lastWall",
                            tool.liveLabel(tool.ringOfSerial(tool.draftBisectorEnds[1])));
                    result.addProperty("edges", tool.draft.markedEdgeCount);
                    result.addProperty("length", tool.draft.length);
                    result.addProperty("fingerprint", EdgeMarks.fingerprint(
                            scene.halfEdgeSurface(), tool.draft.markedByEdgeId));
                }
                if (placed && confirm) {
                    result.addProperty("confirmed", tool.confirmDraft());
                }
                result.addProperty(ERROR, tool.lastError);
                result.addProperty("lastRow", tool.lastRow);
                return result;
            });
        } catch (Exception failure) {
            JsonObject error = new JsonObject();
            error.addProperty(OK, false);
            error.addProperty(ERROR, failure.getMessage() == null ? failure.toString()
                    : failure.getMessage());
            return error;
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("rings bisect")
                .description("Draw a bisector in the ring scene as K and clicks do: its two ends "
                        + "snap onto the nearest ring or bisector, the points between steer it, and "
                        + "the open geodesic spline through them splits every region it crosses.")
                .param(POINTS, RouteParamType.STRING, true, "",
                        "Points as \"x,y,z; ...\": the first and last are the ends, on or beside "
                                + "walls; any between are steering anchors, in any order.",
                        "0.04,-0.01,-0.03; 0.05,-0.12,-0.16")
                .param(CONFIRM, RouteParamType.BOOL, false, "false",
                        "Confirm the bisector as Enter does.", "true")
                .responseHint("{ok, anchors, simple, firstWall, lastWall, edges, length, "
                        + "fingerprint, confirmed, error, lastRow}")
                .build();
    }
}
