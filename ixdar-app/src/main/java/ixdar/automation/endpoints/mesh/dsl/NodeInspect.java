package ixdar.automation.endpoints.mesh.dsl;

import java.io.IOException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.nodegraph.runtime.GraphNodeContext;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.automation.AutomationEndpoint;
import ixdar.scenes.model.ModelScene;

import ixdar.nodegraph.catalog.NodeInspector;
/**
 * Reads the node tree of the graph the active {@link ModelScene} ran: the whole tree, one node's
 * inputs, outputs and public fields, or a field path below a node, so a question about one trace
 * or arc is answered without adding a log line.
 */
@AutomationRouteAnnotation(path = "/mesh/dsl/inspect", method = APIMethod.POST)
public class NodeInspect extends AutomationEndpoint implements AutomationRoute {

    public static final String PATH = "path";

    public static final String FIELD = "field";

    public static final String DEPTH = "depth";

    public static final String LIMIT = "limit";

    public static final String OK = "ok";
    public static final String ERROR = "error";

    public static final int DEFAULT_DEPTH = 1;
    public static final int DEFAULT_LIMIT = 20;

    /**
     * {@code POST /mesh/dsl/inspect}: the tree, a node or a field path of the active scene's graph.
     *
     * @param body JSON body with optional {@code path}, {@code field}, {@code depth}, {@code limit}
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {ok, tree}}, {@code {ok, node, type, ms, inputs, outputs, fields}} or
     *         {@code {ok, node, field, value}}; {@code {ok: false, error}} when the path or field
     *         names nothing
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String path = text(body, PATH);
        String field = text(body, FIELD);
        int depth = body.has(DEPTH) && !body.get(DEPTH).isJsonNull() ? body.get(DEPTH).getAsInt()
                : DEFAULT_DEPTH;
        int limit = body.has(LIMIT) && !body.get(LIMIT).isJsonNull() ? body.get(LIMIT).getAsInt()
                : DEFAULT_LIMIT;
        try {
            // On the render thread, where the scene's graph is not being replaced.
            return runtime.runOnMainThread(() -> {
                if (!(runtime.canvas instanceof ModelScene scene)) {
                    return failure("the active scene is not a model scene");
                }
                NodeGraphRuntime graph = scene.graph;
                if (graph == null) {
                    return failure("the active scene has run no graph yet");
                }
                JsonObject result = new JsonObject();
                result.addProperty(OK, true);
                if (path.isEmpty()) {
                    JsonArray tree = new JsonArray();
                    for (String line : graph.treeLines()) {
                        tree.add(line);
                    }
                    result.add("tree", tree);
                    return result;
                }
                GraphNodeContext record = graph.node(path);
                if (record == null) {
                    boolean exists = graph.staticPaths().containsKey(path);
                    return failure(exists
                            ? path + " is in the graph but did not run: nothing the scene read depends on it"
                            : "no node at path '" + path + "'; nearest: " + String.join(", ",
                                    NodeGraphRuntime.nearest(path, graph.staticPaths().keySet())));
                }
                NodeInspector inspector = new NodeInspector(depth, limit);
                if (field.isEmpty()) {
                    JsonObject view = inspector.node(record);
                    for (String key : view.keySet()) {
                        result.add(key, view.get(key));
                    }
                    return result;
                }
                try {
                    result.addProperty("node", path);
                    result.addProperty(FIELD, field);
                    result.add("value", inspector.walk(record, field));
                    return result;
                } catch (IllegalArgumentException missing) {
                    return failure(missing.getMessage());
                }
            });
        } catch (Exception failure) {
            return failure(String.valueOf(failure.getMessage()));
        }
    }

    private static String text(JsonObject body, String key) {
        return body.has(key) && !body.get(key).isJsonNull() ? body.get(key).getAsString().trim() : "";
    }

    private static JsonObject failure(String message) {
        JsonObject error = new JsonObject();
        error.addProperty(OK, false);
        error.addProperty(ERROR, message);
        return error;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("node-inspect")
                .description("Inspect the graph the active model scene ran: the node tree with times"
                        + " and peak heap, one node's inputs, outputs and fields, or a field path"
                        + " below it, with depth and length limits.")
                .optionalPositional(PATH, RouteParamType.STRING,
                        "Node path, call-site ids joined with /; omit for the whole tree.",
                        "layout/motorcycle")
                .optionalPositional(FIELD, RouteParamType.STRING,
                        "Field path below the node: a port or public field, then .field and [index].",
                        "traces[127].state")
                .param(DEPTH, RouteParamType.INT, false, String.valueOf(DEFAULT_DEPTH),
                        "Levels of nested objects to expand.", "2")
                .param(LIMIT, RouteParamType.INT, false, String.valueOf(DEFAULT_LIMIT),
                        "Elements of a list, array or map to show.", "50")
                .responseHint("{ok, tree:[...]} | {ok, node, type, ms, peak_heap_mib, children,"
                        + " inputs, outputs, fields} | {ok, node, field, value}")
                .build();
    }
}
