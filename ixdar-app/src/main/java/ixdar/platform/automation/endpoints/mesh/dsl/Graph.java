package ixdar.platform.automation.endpoints.mesh.dsl;

import java.io.IOException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.geometry.mesh.graph.HeapSampler;
import ixdar.graphics.cameras.Bounds;
import ixdar.graphics.cameras.Camera2D;
import ixdar.gui.ui.graph.DslGraphLayout;
import ixdar.gui.ui.graph.DslGraphView;
import ixdar.gui.ui.menu.MenuScrollBar;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.model.ModelScene;

@AutomationRouteAnnotation(path = "mesh/dsl/graph", method = APIMethod.POST)
public class Graph extends AutomationEndpoint implements AutomationRoute {
    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String TEXT_PX = "text_px";
    public static final String TOGGLE_GROUP = "toggle_group";
    public static final String TYPE = "type";
    public static final String MS = "ms";
    public static final String SCREEN = "screen";

    /**
     * {@code POST /mesh/dsl/graph}: the DSL graph view of the active model scene as numbers, so
     * its layout, times, zoom, scroll bars and expanded groups can be checked without pixels.
     *
     * @param body optional {@code toggle_group}: first expand or collapse that group; optional
     *             {@code text_px}: then zoom about the slowest node so text is that tall
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {ok, shown, active_tool, hotspots, hotspot, hotspot_rank, node_count, edge_count, total_ms, critical_path_ms,
     *         critical_path, slowest, text_px, scale_factor, scroll, home_frame, scroll_range,
     *         view_rect (layout units, left bottom right top), whole_graph_visible, nodes,
     *         expanded_groups, edges}}, or an error object when
     *         no model scene is active or it has run no graph
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            return runtime.runOnMainThread(() -> {
                JsonObject result = new JsonObject();
                if (!(runtime.canvas instanceof ModelScene scene) || scene.graphTool == null) {
                    result.addProperty(OK, false);
                    result.addProperty(ERROR, "no model scene is active");
                    return result;
                }
                DslGraphView view = scene.graphTool;
                view.refresh();
                if (view.layout == null) {
                    result.addProperty(OK, false);
                    result.addProperty(ERROR, "no DSL graph has run in this scene");
                    return result;
                }
                String toggle = body != null && body.has(TOGGLE_GROUP) ? body.get(TOGGLE_GROUP).getAsString() : "";
                if (!toggle.isEmpty()) {
                    view.toggleGroup(toggle);
                }
                DslGraphLayout graph = view.layout;
                float textPx = body != null && body.has(TEXT_PX) ? body.get(TEXT_PX).getAsFloat() : 0;
                if (textPx > 0 && graph.slowestNode >= 0) {
                    int node = graph.slowestNode;
                    float viewWidth = scene.paneBounds.viewWidth;
                    float width = viewWidth * (1 - 2 * DslGraphView.FRAME_MARGIN) * DslGraphLayout.TEXT_HEIGHT / textPx;
                    float height = width * scene.paneBounds.viewHeight / viewWidth;
                    float centreX = graph.boxX[node] + graph.boxWidth[node] / 2;
                    float centreY = graph.boxY[node] + graph.boxHeight[node] / 2;
                    view.focus(centreX - width / 2, centreY - height / 2, centreX + width / 2, centreY + height / 2);
                }
                Camera2D camera = view.camera;
                camera.calculateCameraTransform(camera.ps);
                float pixelsPerUnit = camera.width / camera.rangeX;
                result.addProperty(OK, true);
                result.addProperty("shown", view.active);
                result.addProperty("active_tool", scene.activeTool.toolName());
                JsonArray hotspots = new JsonArray();
                for (String hotspot : view.hotspotPaths) {
                    hotspots.add(hotspot);
                }
                result.add("hotspots", hotspots);
                result.addProperty("hotspot", view.hotspot < 0 ? "" : view.hotspotPaths[view.hotspot]);
                result.addProperty("hotspot_rank", view.hotspot + 1);
                result.addProperty("node_count", graph.nodeCount);
                result.addProperty("edge_count", graph.edgeCount);
                result.addProperty("depth_count", graph.depthCount);
                result.addProperty("total_ms", graph.totalMs);
                result.addProperty("critical_path_ms", graph.criticalPathMs);
                JsonArray path = new JsonArray();
                for (int node : graph.criticalPath) {
                    path.add(graph.nodeId[node]);
                }
                result.add("critical_path", path);
                result.addProperty("slowest", graph.slowestNode < 0 ? "" : graph.nodeId[graph.slowestNode]);
                result.addProperty(TEXT_PX, DslGraphLayout.TEXT_HEIGHT * pixelsPerUnit);
                result.addProperty("scale_factor", camera.ScaleFactor);
                view.updateScrollBars();
                JsonObject scroll = new JsonObject();
                for (MenuScrollBar bar : new MenuScrollBar[] { view.horizontalBar, view.verticalBar }) {
                    JsonObject barRow = new JsonObject();
                    barRow.addProperty("content_px", bar.contentLength);
                    barRow.addProperty("visible_px", bar.visibleLength);
                    barRow.addProperty("offset_px", bar.scrollOffset);
                    barRow.addProperty("thumb_start_px", bar.thumbStart());
                    barRow.addProperty("thumb_length_px", bar.thumbLength());
                    barRow.addProperty("track_px", bar.trackLength());
                    scroll.add(bar.horizontal ? "horizontal" : "vertical", barRow);
                }
                result.add("scroll", scroll);
                JsonArray home = new JsonArray();
                JsonArray range = new JsonArray();
                for (float edge : new float[] { view.homeLeft, view.homeBottom, view.homeRight, view.homeTop }) {
                    home.add(edge);
                }
                for (float edge : new float[] { view.rangeLeft, view.rangeBottom, view.rangeRight, view.rangeTop }) {
                    range.add(edge);
                }
                result.add("home_frame", home);
                result.add("scroll_range", range);
                JsonArray shown = new JsonArray();
                Bounds pane = scene.paneBounds;
                for (float edge : new float[] { camera.screenTransformX(0), camera.screenTransformY(0),
                        camera.screenTransformX(pane.viewWidth), camera.screenTransformY(pane.viewHeight) }) {
                    shown.add(edge);
                }
                result.add("view_rect", shown);
                result.addProperty("whole_graph_visible", !view.horizontalBar.isNeeded()
                        && !view.verticalBar.isNeeded());
                JsonArray nodes = new JsonArray();
                for (int node = 0; node < graph.nodeCount; node++) {
                    JsonObject row = new JsonObject();
                    row.addProperty("id", graph.nodeId[node]);
                    row.addProperty(TYPE, graph.nodeType[node]);
                    row.addProperty(MS, graph.nodeMs[node]);
                    row.addProperty("peak_heap_mib", graph.nodePeakHeapBytes[node] / HeapSampler.BYTES_PER_MIB);
                    row.addProperty("expandable", graph.nodeExpandable[node]);
                    row.addProperty("depth", graph.nodeDepth[node]);
                    row.addProperty("order", graph.nodeOrder[node]);
                    JsonArray box = new JsonArray();
                    box.add(graph.boxX[node]);
                    box.add(graph.boxY[node]);
                    box.add(graph.boxWidth[node]);
                    box.add(graph.boxHeight[node]);
                    row.add("box", box);
                    JsonArray screen = new JsonArray();
                    screen.add(camera.pointTransformX(graph.boxX[node]));
                    screen.add(camera.pointTransformY(graph.boxY[node]));
                    screen.add(graph.boxWidth[node] * pixelsPerUnit);
                    screen.add(graph.boxHeight[node] * pixelsPerUnit);
                    row.add(SCREEN, screen);
                    nodes.add(row);
                }
                result.add("nodes", nodes);
                JsonArray groups = new JsonArray();
                for (int group = 0; group < graph.groupCount; group++) {
                    JsonObject row = new JsonObject();
                    row.addProperty("path", graph.groupPath[group]);
                    row.addProperty(TYPE, graph.groupType[group]);
                    row.addProperty(MS, graph.groupMs[group]);
                    JsonArray screen = new JsonArray();
                    screen.add(camera.pointTransformX(graph.groupFrameX[group]));
                    screen.add(camera.pointTransformY(graph.groupFrameY[group]));
                    screen.add(graph.groupFrameWidth[group] * pixelsPerUnit);
                    screen.add(graph.groupFrameHeight[group] * pixelsPerUnit);
                    row.add(SCREEN, screen);
                    groups.add(row);
                }
                result.add("expanded_groups", groups);
                JsonArray edges = new JsonArray();
                for (int edge = 0; edge < graph.edgeCount; edge++) {
                    JsonObject row = new JsonObject();
                    row.addProperty("from", graph.nodeId[graph.edgeSource[edge]]);
                    row.addProperty("from_port", graph.outputPortName[graph.edgeSourcePort[edge]]);
                    row.addProperty("to", graph.nodeId[graph.edgeTarget[edge]]);
                    row.addProperty("to_port", graph.inputPortName[graph.edgeTargetPort[edge]]);
                    row.addProperty("critical", graph.edgeCritical[edge]);
                    edges.add(row);
                }
                result.add("edges", edges);
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
                .description("Report the DSL graph view of the active model scene: every node's box, depth and"
                        + " run time, every edge, the critical path, the zoom and the scroll bars.")
                .param(TEXT_PX, RouteParamType.FLOAT, false, "0",
                        "First zoom about the slowest node so its text is this many pixels tall; 0 leaves the zoom.",
                        "8")
                .param(TOGGLE_GROUP, RouteParamType.STRING, false, "",
                        "First expand this group call's box, or collapse the expanded group it is or sits in,"
                                + " as E does under the cursor.", "layout")
                .responseHint("{ok, shown, active_tool, hotspots, hotspot, hotspot_rank, node_count, edge_count, total_ms, critical_path_ms, critical_path,"
                        + " slowest, text_px, scale_factor, scroll:{horizontal, vertical:{content_px, visible_px,"
                        + " offset_px, thumb_start_px, thumb_length_px, track_px}}, home_frame, scroll_range,"
                        + " view_rect, whole_graph_visible,"
                        + " nodes:[{id, type, ms, peak_heap_mib, expandable, depth, order, box,"
                        + " screen}], expanded_groups:[{path, type, ms, screen}],"
                        + " edges:[{from, from_port, to, to_port, critical}]}")
                .build();
    }
}
