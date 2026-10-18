package ixdar.automation.endpoints.mesh.dsl;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.automation.AutomationRuntime;
import ixdar.scenes.mesh.MeshNodeViewerScene;

/**
 * Runs a named DSL graph and answers once it has finished, one graph at a time: a submit arriving
 * mid-run is refused, never queued, so no graph is evaluated twice.
 */
@AutomationRouteAnnotation(path = "mesh/dsl", method = APIMethod.POST)
public class LoadDSL extends AutomationEndpoint implements AutomationRoute {
    public static final String NAME = "name";
    public static final String NODE = "node";
    public static final String PORT = "port";
    public static final String GEOMETRY = "geometry";
    public static final String RUNNING = "running";
    public static final long WAIT_SECONDS = 1800L;
    private static final long MILLIS_PER_SECOND = 1000L;

    /** The graph this route has queued or is evaluating, or {@code null} when it is idle. */
    public String runningGraph;

    public long runningSinceMillis;

    /**
     * {@code POST /mesh/dsl}: claim the route for {@code name}, evaluate the graph on the render
     * thread and answer with its counts and timing once it has finished.
     *
     * @param body JSON body with {@code name}, optional {@code node} and {@code port}
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {ok, dsl, node, port, vertices, faces, timing}}, or {@code {ok: false, error}}
     *         with {@code running} naming the graph in progress when this submit was refused
     */
    @Override
    public JsonObject endpointHandler(JsonObject body)
            throws IOException {
        String dslName = body.has(NAME) ? body.get(NAME).getAsString() : "";
        String node = body.has(NODE) ? body.get(NODE).getAsString() : "";
        String port = body.has(PORT)
                ? body.get(PORT).getAsString()
                : GEOMETRY;

        if (dslName.isEmpty()) {
            return failure("Missing required field: name");
        }

        synchronized (this) {
            if (runningGraph != null) {
                JsonObject busy = failure("graph " + runningGraph + " has been running for "
                        + (System.currentTimeMillis() - runningSinceMillis) / MILLIS_PER_SECOND
                        + " s; this submit was not queued, run it again once that one answers");
                busy.addProperty(RUNNING, runningGraph);
                return busy;
            }
            runningGraph = dslName;
            runningSinceMillis = System.currentTimeMillis();
        }
        try {
            return runtime.runOnMainThread(() -> {
                try {
                    if (!(runtime.canvas instanceof MeshNodeViewerScene)) {
                        return failure("MeshNodeViewerScene is not active");
                    }
                    MeshNodeViewerScene mvs = (MeshNodeViewerScene) runtime.canvas;
                    mvs.loadDsl(dslName, node, port);
                    JsonObject result = success();
                    result.addProperty("dsl", dslName);
                    result.addProperty(NODE, node != null ? node : "");
                    result.addProperty(PORT, port != null ? port : GEOMETRY);
                    result.addProperty("vertices", mvs.getMeshVertexCount());
                    result.addProperty("faces", mvs.getMeshFaceCount());
                    AutomationRuntime.appendTiming(mvs, result);
                    return result;
                } finally {
                    synchronized (this) {
                        runningGraph = null;
                    }
                }
            }, WAIT_SECONDS * MILLIS_PER_SECOND);
        } catch (Exception e) {
            StringBuilder msg = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (msg.length() > 0)
                    msg.append(" <- ");
                msg
                        .append(t.getClass().getSimpleName())
                        .append(": ")
                        .append(t.getMessage());
            }
            return failure(msg.toString());
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Load and execute a named DSL skill graph, making its output geometry the active mesh.")
                .param(NAME, RouteParamType.STRING, true, "",
                        "Name of the DSL skill graph to run.", "skeleton-decompose")
                .param(NODE, RouteParamType.STRING, false, "",
                        "Graph node whose output geometry becomes the active mesh.", "output")
                .param(PORT, RouteParamType.STRING, false, GEOMETRY,
                        "Output port on the selected node to read geometry from.", GEOMETRY)
                .responseHint("{ok, dsl, node, port, vertices, faces, timing...}; while another"
                        + " graph runs, {ok: false, running, error}")
                .waitSeconds(WAIT_SECONDS)
                .build();
    }
}
