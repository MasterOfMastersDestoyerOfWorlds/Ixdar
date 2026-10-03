package ixdar.platform.automation.endpoints.mesh;

import java.io.IOException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.geometry.mesh.data.RingRegions;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.regions.RingRegionTool;
import ixdar.scenes.ring.RingScene;

/**
 * Reports the ring regions the editing scene's region-select tool shows: the count, each region's
 * faces, area and bounding rings, the problem rings, and the current selection and its query.
 */
@AutomationRouteAnnotation(path = "/mesh/regions", method = APIMethod.GET)
public class Regions extends AutomationEndpoint implements AutomationRoute {

    public static final String OK = "ok";

    public static final String ERROR = "error";

    public static final String REGIONS = "regions";

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            return runtime.runOnMainThread(() -> {
                JsonObject result = new JsonObject();
                if (!(runtime.canvas instanceof RingScene scene)) {
                    result.addProperty(OK, false);
                    result.addProperty(ERROR, "the ring-tool editing scene is not active");
                    return result;
                }
                RingRegionTool tool = scene.regionTool;
                RingRegions regions = tool.regions;
                result.addProperty("activeTool", scene.activeTool.toolName());
                if (regions == null) {
                    result.addProperty(OK, false);
                    result.addProperty(ERROR, "no regions yet: switch to the "
                            + RingRegionTool.TOOL_NAME + " tool with Ctrl+T");
                    return result;
                }
                result.addProperty(OK, true);
                result.addProperty("regionCount", regions.regionCount);
                JsonArray rows = new JsonArray();
                for (int region = 0; region < regions.regionCount; region++) {
                    JsonObject row = new JsonObject();
                    row.addProperty("region", region);
                    row.addProperty("faces", regions.regionFaceCount[region]);
                    row.addProperty("area", regions.regionArea[region]);
                    row.addProperty("boundedBy", regions.boundingRingText(region));
                    row.addProperty("selected", tool.selectedRegions.length > region
                            && tool.selectedRegions[region]);
                    rows.add(row);
                }
                result.add(REGIONS, rows);
                JsonArray problems = new JsonArray();
                regions.problems.forEach(problems::add);
                result.add("problems", problems);
                result.addProperty("ringCount", regions.ringLabels.length);
                result.addProperty("selectedCount", tool.selectedCount());
                result.addProperty("select", tool.selectQuery);
                result.addProperty("lastRow", tool.lastRow);
                result.addProperty(ERROR, tool.lastError);
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
                .commandName(REGIONS)
                .description("Report the ring regions the editing scene's region-select tool "
                        + "shows: each region's faces, area and bounding rings, the problem "
                        + "rings, and the selection.")
                .responseHint("{ok, activeTool, regionCount, regions:[{region, faces, area, "
                        + "boundedBy, selected}], problems, ringCount, selectedCount, select, "
                        + "lastRow, error}")
                .build();
    }
}
