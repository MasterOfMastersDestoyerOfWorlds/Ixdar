package ixdar.automation.endpoints.mesh.dsl;

import java.io.IOException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.nodegraph.runtime.GraphNodeContext;
import ixdar.nodegraph.runtime.HeapSampler;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.automation.AutomationEndpoint;
import ixdar.scenes.model.ModelScene;

@AutomationRouteAnnotation(path = "mesh/dsl/timing", method = APIMethod.GET)
public class Timing extends AutomationEndpoint implements AutomationRoute {
    public static final String PEAK_HEAP_MIB = "peak_heap_mib";

    /**
     * {@code GET /mesh/dsl/timing}: report per-node execution time and peak heap for the graph
     * the active {@link ModelScene} ran last, every node of its tree included.
     *
     * @param body request body (unused)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": true, "revision", "total_ms", "peak_heap_mib", "nodes": [{node, type, ms,
     *         peak_heap_mib}, ...]}}, or an error object when no model scene is active or it has
     *         run no graph
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        if (!(runtime.canvas instanceof ModelScene scene)) {
            return failure("no model scene is active");
        }
        if (scene.graph == null) {
            return failure("the active scene has run no graph yet");
        }
        JsonObject result = success();
        JsonObject timing = timingJson(scene.graph, 0);
        for (String key : timing.keySet()) {
            result.add(key, timing.get(key));
        }
        return result;
    }

    /**
     * A graph's current run report as JSON: its revision, total time and peak heap from the
     * tree's root, and one row per record at or above a time threshold, keyed by path.
     *
     * @param graph the graph whose current run is reported
     * @param minMs rows faster than this are left out
     * @return {@code {revision, total_ms, peak_heap_mib, nodes: [{node, type, ms, peak_heap_mib}]}}
     */
    public static JsonObject timingJson(NodeGraphRuntime graph, long minMs) {
        JsonObject timing = new JsonObject();
        timing.addProperty("revision", graph.revision);
        timing.addProperty("total_ms", graph.root.elapsedMs());
        timing.addProperty(PEAK_HEAP_MIB, graph.root.peakHeapBytes / HeapSampler.BYTES_PER_MIB);
        JsonArray nodes = new JsonArray();
        for (GraphNodeContext record : graph.treeRecords()) {
            if (record.elapsedMs() < minMs) {
                continue;
            }
            JsonObject node = new JsonObject();
            node.addProperty("node", record.path);
            node.addProperty("type", record.type);
            node.addProperty("ms", record.elapsedMs());
            node.addProperty(PEAK_HEAP_MIB, record.peakHeapBytes / HeapSampler.BYTES_PER_MIB);
            nodes.add(node);
        }
        timing.add("nodes", nodes);
        return timing;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Report per-node execution times and peak heap of the graph the active"
                        + " model scene ran last, nodes inside groups included.")
                .responseHint("{ok, revision, total_ms, peak_heap_mib, nodes:[{node, type, ms, peak_heap_mib}, ...]}")
                .build();
    }
}
