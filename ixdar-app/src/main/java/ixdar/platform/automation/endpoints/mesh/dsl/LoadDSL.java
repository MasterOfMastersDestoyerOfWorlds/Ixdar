package ixdar.platform.automation.endpoints.mesh.dsl;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.automation.endpoints.AutomationRuntime;
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
    public static final String OK = "ok";
    public static final String ERROR = "error";
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
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            err.addProperty(ERROR, "Missing required field: name");
            return err;

        }

        synchronized (this) {
            if (runningGraph != null) {
                JsonObject busy = new JsonObject();
                busy.addProperty(OK, false);
                busy.addProperty(RUNNING, runningGraph);
                busy.addProperty(ERROR, "graph " + runningGraph + " has been running for "
                        + (System.currentTimeMillis() - runningSinceMillis) / MILLIS_PER_SECOND
                        + " s; this submit was not queued, run it again once that one answers");
                return busy;
            }
            runningGraph = dslName;
            runningSinceMillis = System.currentTimeMillis();
        }
        try {
            return runtime.runOnMainThread(() -> {
                try {
                    JsonObject result = new JsonObject();
                    if (!(runtime.canvas instanceof MeshNodeViewerScene)) {
                        result.addProperty(OK, false);
                        result.addProperty(
                                ERROR,
                                "MeshNodeViewerScene is not active");
                        return result;
                    }
                    MeshNodeViewerScene mvs = (MeshNodeViewerScene) runtime.canvas;
                    mvs.loadDsl(dslName, node, port);
                    result.addProperty(OK, true);
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
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            StringBuilder msg = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (msg.length() > 0)
                    msg.append(" <- ");
                msg
                        .append(t.getClass().getSimpleName())
                        .append(": ")
                        .append(t.getMessage());
            }
            err.addProperty(ERROR, msg.toString());
            return err;
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
