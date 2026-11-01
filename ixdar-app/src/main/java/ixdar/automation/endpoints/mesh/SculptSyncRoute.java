package ixdar.automation.endpoints.mesh;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.sculpt.StrokeAnchors;
import ixdar.nodegraph.nodes.modifier.StrokeSculptNode;
import ixdar.nodegraph.runtime.GraphNodeContext;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * Syncs a sculpt DSL's stroke layers: each stroke_sculpt node's SVG edits folded into its sidecar of
 * base-anchored strokes, then the SVG redrawn from them in every cell of the finished sculpt.
 */
@AutomationRouteAnnotation(path = "/mesh/sculpt-sync", method = APIMethod.POST)
public class SculptSyncRoute extends AutomationEndpoint implements AutomationRoute {

    public static final String DSL = "dsl";

    public static final String LAYER = "layer";

    /**
     * {@code POST /mesh/sculpt-sync}: run the DSL, sync every stroke_sculpt node's files, or the one
     * named, and report what changed per layer.
     *
     * @param body {@code dsl} path, optional {@code layer}
     * @return {@code {ok, layers:[{layer, svg, changes}]}}, or the refusal with its fix
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) {
        String dsl = body.has(DSL) ? body.get(DSL).getAsString() : "";
        String layer = body.has(LAYER) ? body.get(LAYER).getAsString().trim() : "";
        try {
            NodeGraphRuntime graph = NodeGraphRuntime.fromSource(new String(Files.readAllBytes(existingFile("--dsl", dsl)),
                    StandardCharsets.UTF_8));
            graph.executeGraphResult(graph.lastId(), StrokeSculptNode.GEOMETRY_OUT.name);
            JsonArray layers = new JsonArray();
            for (GraphNodeContext record : List.copyOf(graph.root.declared)) {
                String id = record.nodeAssignmentId();
                if (!StrokeSculptNode.NODE_ID.equals(NodeGraphRuntime.typeOf(record.node))
                        || !layer.isEmpty() && !layer.equals(id)
                        || !(record.getInputValue(StrokeSculptNode.SVG.name) instanceof String svg) || svg.isBlank()) {
                    continue;
                }
                GeometryBundle base = (GeometryBundle) record.getInputValue(StrokeSculptNode.GEOMETRY.name);
                List<String> changes = StrokeAnchors.sync(svg, base, () -> {
                    try {
                        graph.executeGraphResult(graph.lastId(), StrokeSculptNode.GEOMETRY_OUT.name);
                    } catch (Exception failed) {
                        throw new IllegalStateException(failed.getMessage(), failed);
                    }
                    return (GeometryBundle) graph.getNodeOutput(id, StrokeSculptNode.GEOMETRY_OUT.name);
                });
                JsonObject row = new JsonObject();
                row.addProperty(LAYER, id);
                row.addProperty(StrokeSculptNode.SVG.name, svg);
                JsonArray lines = new JsonArray();
                changes.forEach(lines::add);
                row.add("changes", lines);
                layers.add(row);
            }
            if (layers.isEmpty()) {
                return failure(dsl + " has no stroke_sculpt node" + (layer.isEmpty() ? "" : " " + layer)
                        + " reading an svg file");
            }
            JsonObject result = success();
            result.add("layers", layers);
            return result;
        } catch (Exception failed) {
            return failure(failed.getMessage() == null ? failed.toString() : failed.getMessage());
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("sculpt-sync")
                .description("Fold a sculpt DSL's stroke SVG edits (moved points, shapes with no data-id, deleted "
                        + "ids, in one cell per stroke) into each layer's sidecar of strokes held on the base, then "
                        + "redraw every stroke into every cell that sees it on the finished sculpt, under its id, "
                        + "hidden spans dashed; refuses conflicting or id-less edits with the fix.")
                .param(DSL, RouteParamType.STRING, true, "",
                        "Sculpt DSL file, relative to the checkout or as its own file inputs resolve.",
                        "ixdar-app/src/main/resources/dsl/stroke_sculpt_head.dsl")
                .param(LAYER, RouteParamType.STRING, false, "",
                        "Sync only this stroke_sculpt node (its statement id); default every one.", "head")
                .responseHint("{ok, layers:[{layer, svg, changes:[\"s3: moved 1 point(s)\", ...]}]} | {ok:false, "
                        + "error: every refusal with its fix}")
                .build();
    }
}
