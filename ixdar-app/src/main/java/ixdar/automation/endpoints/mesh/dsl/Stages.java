package ixdar.automation.endpoints.mesh.dsl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.automation.AutomationEndpoint;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.fields.crossfield.CrossField;
import ixdar.geometry.mesh.data.ops.MeshRepairReport;
import ixdar.geometry.mesh.data.ops.MeshTopologyAnalysis;
import ixdar.geometry.mesh.quadlayout.QuadLayout;
import ixdar.geometry.mesh.quadlayout.embedding.model.EmbeddedPatch;
import ixdar.nodegraph.nodes.quadlayout.QuadLayoutNode;
import ixdar.nodegraph.runtime.GraphNodeContext;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.scenes.model.ModelScene;

/**
 * Reports how far the active model scene's last graph run got, statement by statement, so a sweep
 * reads one answer per model instead of the scene log; it reads off the render thread, like
 * {@link Timing}.
 */
@AutomationRouteAnnotation(path = "mesh/dsl/stages", method = APIMethod.GET)
public class Stages extends AutomationEndpoint implements AutomationRoute {

    public static final String FURTHEST = "furthest";

    public static final String FURTHEST_TYPE = "furthest_type";

    public static final String SHELL_COUNT = "shell_count";

    /**
     * {@code GET /mesh/dsl/stages}: each top-level statement's time and mesh counts, the repair
     * report a {@code repair_mesh} call attached, and for a {@code quad_layout} call the deepest
     * stage it evaluated, its cross field's cones and genus, and its quad and patch counts.
     *
     * @param body request body (unused)
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return {@code {ok, furthest, furthest_type, statements: [...], revision, total_ms,
     *         peak_heap_mib, nodes: [...]}}, or an error object when no model scene is active or it
     *         has run no graph
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        if (!(runtime.canvas instanceof ModelScene scene)) {
            return failure("no model scene is active");
        }
        NodeGraphRuntime graph = scene.graph;
        if (graph == null) {
            return failure("the active scene has run no graph yet");
        }
        JsonObject result = success();
        List<GraphNodeContext> records = new ArrayList<>(graph.treeRecords());
        if (!records.isEmpty()) {
            GraphNodeContext furthest = records.get(records.size() - 1);
            result.addProperty(FURTHEST, furthest.path);
            result.addProperty(FURTHEST_TYPE, furthest.type);
        }
        JsonArray statements = new JsonArray();
        for (GraphNodeContext statement : new ArrayList<>(graph.root.children)) {
            JsonObject entry = new JsonObject();
            entry.addProperty("node", statement.path);
            entry.addProperty("type", statement.type);
            entry.addProperty("ms", statement.elapsedMs());
            if (statement.getOutput(QuadLayoutNode.GEOMETRY.name) instanceof GeometryBundle bundle
                    && bundle.mesh() != null) {
                entry.addProperty("vertex_count", bundle.mesh().vertexCount());
                entry.addProperty("face_count", bundle.mesh().faceCount());
                if (bundle.slots().get(MeshRepairReport.SLOT) instanceof MeshRepairReport report) {
                    JsonObject repair = new JsonObject();
                    repair.addProperty("summary", report.summaryLine());
                    repair.addProperty("input_vertex_count", report.inputVertexCount);
                    repair.addProperty("input_face_count", report.inputFaceCount);
                    repair.addProperty("output_vertex_count", report.outputVertexCount);
                    repair.addProperty("output_face_count", report.outputFaceCount);
                    repair.addProperty(SHELL_COUNT, report.shellCount);
                    repair.addProperty("dropped_shell_count", report.droppedShellCount);
                    repair.addProperty("hole_count", report.holeCount);
                    repair.addProperty("filled_hole_count", report.filledHoleCount);
                    repair.addProperty("open_hole_count", report.openHoleCount);
                    repair.addProperty("folded_face_count", report.foldedFaceCount);
                    repair.addProperty("still_folded_face_count", report.stillFoldedFaceCount);
                    repair.addProperty("output_boundary_edge_count", report.outputBoundaryEdgeCount);
                    entry.add("repair", repair);
                }
            }
            if (QuadLayoutNode.TYPE.equals(statement.type)) {
                JsonObject layout = new JsonObject();
                List<GraphNodeContext> subtree = statement.subtree();
                GraphNodeContext furthest = subtree.get(subtree.size() - 1);
                layout.addProperty(FURTHEST, furthest.path);
                layout.addProperty(FURTHEST_TYPE, furthest.type);
                JsonArray stages = new JsonArray();
                CrossField field = null;
                for (GraphNodeContext record : subtree.subList(1, subtree.size())) {
                    stages.add(record.path);
                    for (Object value : new ArrayList<>(record.getOutputsSnapshot().values())) {
                        if (value instanceof CrossField built) {
                            field = built;
                        }
                    }
                }
                layout.add("stages", stages);
                if (statement.getOutput(QuadLayoutNode.FIELD.name) instanceof CrossField built) {
                    field = built;
                }
                if (field != null) {
                    MeshTopologyAnalysis analysis = new MeshTopologyAnalysis();
                    analysis.duplicateTolerance = 0;
                    analysis.analyze(field.mesh);
                    layout.addProperty("cones", field.singularityCount());
                    layout.addProperty("euler_characteristic", analysis.eulerCharacteristic);
                    layout.addProperty(SHELL_COUNT, analysis.shellCount);
                    layout.addProperty("boundary_loop_count", analysis.boundaryLoopCount);
                    layout.addProperty("genus", (2 * analysis.shellCount - analysis.eulerCharacteristic
                            - analysis.boundaryLoopCount) / 2);
                }
                if (statement.getOutput(QuadLayoutNode.LAYOUT.name) instanceof QuadLayout built) {
                    layout.addProperty("quad_count", built.quads == null || built.quads.mesh() == null
                            ? 0 : built.quads.mesh().faceCount());
                    int livePatches = 0;
                    for (EmbeddedPatch patch : built.tmesh.patches) {
                        if (patch.alive) {
                            livePatches++;
                        }
                    }
                    layout.addProperty("patch_count", livePatches);
                }
                entry.add("layout", layout);
            }
            statements.add(entry);
        }
        result.add("statements", statements);
        JsonObject timing = Timing.timingJson(graph, 0);
        for (String key : timing.keySet()) {
            result.add(key, timing.get(key));
        }
        return result;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Report how far the graph the active model scene ran last got: each"
                        + " top-level statement's mesh counts and repair report, each quad_layout"
                        + " call's furthest stage, cones, Euler characteristic, genus, quad and patch"
                        + " counts, and every node's time; it answers while a stage is still running.")
                .responseHint("{ok, furthest, furthest_type, statements:[{node, type, ms, vertex_count,"
                        + " face_count, repair:{summary, ...}, layout:{furthest, stages, cones,"
                        + " euler_characteristic, shell_count, boundary_loop_count, genus, quad_count,"
                        + " patch_count}}], revision, total_ms, peak_heap_mib, nodes:[...]}")
                .build();
    }
}
