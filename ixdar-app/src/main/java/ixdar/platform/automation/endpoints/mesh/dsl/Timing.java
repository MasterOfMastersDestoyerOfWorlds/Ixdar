package ixdar.platform.automation.endpoints.mesh.dsl;

import java.io.IOException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.geometry.mesh.graph.GraphNodeContext;
import ixdar.geometry.mesh.graph.HeapSampler;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.model.ModelScene;

@AutomationRouteAnnotation(path = "mesh/dsl/timing", method = APIMethod.GET)
public class Timing extends AutomationEndpoint implements AutomationRoute {
    public static final String OK = "ok";
    public static final String ERROR = "error";

    public static final String PEAK_HEAP_MIB = "peak_heap_mib";

    /**
     * {@code GET /mesh/dsl/timing}: report per-node execution time and peak heap for the graph
     * the active {@link ModelScene} ran last, every node of its tree included.
     *
     * @param body request body (unused)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": true, "total_ms", "peak_heap_mib", "nodes": [{node, type, ms,
     *         peak_heap_mib}, ...]}}, or an error object when no model scene is active or it has
     *         run no graph
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        JsonObject result = new JsonObject();
        if (!(runtime.canvas instanceof ModelScene scene)) {
            result.addProperty(OK, false);
            result.addProperty(ERROR, "no model scene is active");
            return result;
        }
        if (scene.graph == null) {
            result.addProperty(OK, false);
            result.addProperty(ERROR, "the active scene has run no graph yet");
            return result;
        }
        result.addProperty(OK, true);
        JsonObject timing = timingJson(scene.graph, 0);
        for (String key : timing.keySet()) {
            result.add(key, timing.get(key));
        }
        return result;
    }

    /**
     * The timing of a graph's node tree as JSON: total time, graph-wide peak heap, and one row
     * per node at or above a time threshold, keyed by path.
     *
     * @param graph the graph whose current run is reported
     * @param minMs rows faster than this are left out
     * @return {@code {total_ms, peak_heap_mib, nodes: [{node, type, ms, peak_heap_mib}]}}
     */
    public static JsonObject timingJson(NodeGraphRuntime graph, long minMs) {
        JsonObject timing = new JsonObject();
        timing.addProperty("total_ms", graph.lastTotalMs());
        double graphPeakMib = 0;
        JsonArray nodes = new JsonArray();
        for (GraphNodeContext record : graph.treeRecords()) {
            double peakMib = record.peakHeapBytes / HeapSampler.BYTES_PER_MIB;
            graphPeakMib = Math.max(graphPeakMib, peakMib);
            if (record.elapsedMs() < minMs) {
                continue;
            }
            JsonObject node = new JsonObject();
            node.addProperty("node", record.path);
            node.addProperty("type", record.type);
            node.addProperty("ms", record.elapsedMs());
            node.addProperty(PEAK_HEAP_MIB, peakMib);
            nodes.add(node);
        }
        timing.addProperty(PEAK_HEAP_MIB, graphPeakMib);
        timing.add("nodes", nodes);
        return timing;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Report per-node execution times and peak heap of the graph the active"
                        + " model scene ran last, nodes inside groups included.")
                .responseHint("{ok, total_ms, peak_heap_mib, nodes:[{node, type, ms, peak_heap_mib}, ...]}")
                .build();
    }
}
