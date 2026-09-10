package ixdar.platform.automation.endpoints.mesh.dsl;

import java.io.IOException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.geometry.mesh.graph.HeapSampler;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "mesh/dsl/timing", method = APIMethod.GET)
public class Timing extends AutomationEndpoint implements AutomationRoute {
    public static final String OK = "ok";
    public static final String ERROR = "error";

    /** Response key carrying a peak used-heap high-water mark in mebibytes. */
    public static final String PEAK_HEAP_MIB = "peak_heap_mib";

    /**
     * {@code GET /mesh/dsl/timing}: report per-node execution time and peak heap for the most
     * recent DSL graph run on the active {@link MeshNodeViewerScene}.
     *
     * @param body request body (unused)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {"ok": true, "total_ms": <ms>, "peak_heap_mib": <mib>, "nodes":
     *         [{node, ms, peak_heap_mib}, ...]}} on success, or an error object when
     *         {@link MeshNodeViewerScene} is not active or no DSL has been executed yet
     */
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        JsonObject result = new JsonObject();
        if (!(runtime.canvas instanceof MeshNodeViewerScene mvs)) {
            result.addProperty(OK, false);
            result.addProperty(ERROR, "MeshNodeViewerScene is not active");
            return result;
        }
        var runtime = mvs.getLastGraphRuntime();
        if (runtime == null) {
            result.addProperty(OK, false);
            result.addProperty(ERROR, "No DSL has been executed yet");
            return result;
        }
        result.addProperty(OK, true);
        result.addProperty("total_ms", runtime.lastTotalMs());
        double graphPeakMib = 0;
        JsonArray nodes = new JsonArray();
        for (var entry : runtime.lastTimingMs().entrySet()) {
            JsonObject node = new JsonObject();
            node.addProperty("node", entry.getKey());
            node.addProperty("ms", entry.getValue());
            Long peakBytes = runtime.lastPeakHeapBytes().get(entry.getKey());
            double peakMib = peakBytes == null ? 0 : peakBytes / HeapSampler.BYTES_PER_MIB;
            graphPeakMib = Math.max(graphPeakMib, peakMib);
            node.addProperty(PEAK_HEAP_MIB, peakMib);
            nodes.add(node);
        }
        result.addProperty(PEAK_HEAP_MIB, graphPeakMib);
        result.add("nodes", nodes);
        return result;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Report per-node execution times and peak heap from the most recent DSL graph run.")
                .responseHint("{ok, total_ms, peak_heap_mib, nodes:[{node, ms, peak_heap_mib}, ...]}")
                .build();
    }
}
