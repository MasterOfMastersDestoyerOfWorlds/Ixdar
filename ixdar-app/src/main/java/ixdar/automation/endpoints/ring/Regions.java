package ixdar.automation.endpoints.ring;

import java.io.IOException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.automation.AutomationEndpoint;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.regions.RegionLayer;
import ixdar.tools.regions.RingRegionTool;

/**
 * Reports the ring regions the editing scene's region layer shows: the count, each region's
 * faces, area and bounding rings, the problem rings, and the current selection and its query.
 */
@AutomationRouteAnnotation(path = "/mesh/regions", method = APIMethod.GET)
public class Regions extends AutomationEndpoint implements AutomationRoute {

    public static final String REGIONS = "regions";

    public static final String ACTIVE_TOOL = "activeTool";

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            return runtime.runOnMainThread(() -> {
                if (!(runtime.canvas instanceof RingScene scene)) {
                    return failure("the ring-tool editing scene is not active");
                }
                RingRegionTool tool = scene.regionTool;
                RegionLayer layer = scene.regionLayer;
                RingRegions regions = layer.regions;
                if (regions == null) {
                    JsonObject none = failure("no regions yet: switch the region colours on "
                            + "with G or open the " + RingRegionTool.TOOL_NAME + " with Ctrl+T");
                    none.addProperty(ACTIVE_TOOL, scene.activeTool.toolName());
                    return none;
                }
                JsonObject result = new JsonObject();
                result.addProperty(ACTIVE_TOOL, scene.activeTool.toolName());
                result.addProperty(OK, true);
                result.addProperty("regionCount", regions.regionCount);
                boolean[] hidden = layer.hiddenByActiveFace;
                boolean hiding = hidden.length == regions.regionByActiveFace.length;
                int[] hiddenFacesByRegion = new int[regions.regionCount];
                int hiddenFaces = 0;
                for (int activeFace = 0; hiding && activeFace < hidden.length; activeFace++) {
                    hiddenFacesByRegion[regions.regionByActiveFace[activeFace]] +=
                            hidden[activeFace] ? 1 : 0;
                    hiddenFaces += hidden[activeFace] ? 1 : 0;
                }
                JsonArray rows = new JsonArray();
                int[] colourByRegion = layer.colouring.colourByRegion;
                for (int region = 0; region < regions.regionCount; region++) {
                    JsonObject row = new JsonObject();
                    row.addProperty("region", region);
                    row.addProperty("faces", regions.regionFaceCount[region]);
                    row.addProperty("area", regions.regionArea[region]);
                    row.addProperty("boundedBy", regions.boundingRingText(region));
                    row.addProperty("sliver", regions.isSliver(region));
                    row.addProperty("colour", region < colourByRegion.length
                            ? colourByRegion[region] : -1);
                    JsonArray neighbours = new JsonArray();
                    for (int neighbour : regions.neighboursByRegion[region]) {
                        neighbours.add(neighbour);
                    }
                    row.add("neighbours", neighbours);
                    row.addProperty("selected", tool.selectedRegions.length > region
                            && tool.selectedRegions[region]);
                    row.addProperty("hidden",
                            hiddenFacesByRegion[region] == regions.regionFaceCount[region]);
                    rows.add(row);
                }
                result.add(REGIONS, rows);
                JsonArray problems = new JsonArray();
                regions.problems.forEach(problems::add);
                result.add("problems", problems);
                JsonArray ringsSplittingNothing = new JsonArray();
                for (int ring = 0; ring < regions.ringLabels.length; ring++) {
                    if (regions.ringSplitsNothing[ring]) {
                        ringsSplittingNothing.add(regions.ringLabels[ring]);
                    }
                }
                result.add("ringsSplittingNothing", ringsSplittingNothing);
                result.addProperty("visible", layer.visible);
                result.addProperty("hiddenFaces", hiddenFaces);
                JsonArray hiddenRings = new JsonArray();
                layer.hiddenRingLabels.forEach(hiddenRings::add);
                result.add("hiddenRings", hiddenRings);
                result.addProperty("absorbSlivers", layer.absorbSlivers);
                result.addProperty("summary", layer.lastRow);
                result.addProperty("ringCount", regions.ringLabels.length);
                result.addProperty("selectedCount", tool.selectedCount());
                result.addProperty("select", tool.selectQuery);
                result.addProperty("lastRow", tool.lastRow);
                result.addProperty(ERROR, tool.lastError);
                return result;
            });
        } catch (Exception failure) {
            return failure(String.valueOf(failure.getMessage()));
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
                        + "boundedBy, sliver, colour, neighbours, selected, hidden}], problems, "
                        + "ringsSplittingNothing, visible, hiddenFaces, hiddenRings, "
                        + "absorbSlivers, summary, "
                        + "ringCount, selectedCount, select, lastRow, error}")
                .build();
    }
}
