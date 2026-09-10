package ixdar.platform.automation.endpoints.mesh;

import java.io.File;
import java.io.IOException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.ops.MeshTopologyAnalysis;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "/mesh/topology", method = APIMethod.POST)
public class Topology extends AutomationEndpoint implements AutomationRoute {
    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String PATH = "path";
    public static final String DUPLICATE_TOLERANCE = "duplicate_tolerance";

    /** Default distance below which two vertices count as sharing a position. */
    public static final double DEFAULT_DUPLICATE_TOLERANCE = 1e-6;

    /**
     * {@code POST /mesh/topology}: counts, edge-connected shells and boundary loops of a mesh
     * file, or of the active viewer mesh when no path is given.
     *
     * @param body optional {@code path} and {@code duplicate_tolerance}
     * @throws IOException when the mesh file cannot be read
     * @return the analysis, or an error object when no mesh can be resolved
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String path = body.has(PATH) ? body.get(PATH).getAsString() : "";
        double tolerance = body.has(DUPLICATE_TOLERANCE)
                ? body.get(DUPLICATE_TOLERANCE).getAsDouble()
                : DEFAULT_DUPLICATE_TOLERANCE;
        if (!path.isEmpty()) {
            File file = resolvePath(path);
            if (file == null) {
                return failure("File not found: " + path);
            }
            return report(MeshLoader.load(file.getAbsolutePath()), tolerance, path);
        }
        try {
            return runtime.runOnMainThread(() -> {
                if (!(runtime.canvas instanceof MeshNodeViewerScene viewer)) {
                    return failure("MeshNodeViewerScene is not active");
                }
                MeshTopology mesh = viewer.getMesh();
                if (mesh == null) {
                    return failure("Mesh not loaded yet");
                }
                return report(mesh, tolerance, "");
            });
        } catch (Exception failed) {
            return failure(failed.getMessage() == null ? failed.toString() : failed.getMessage());
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Report mesh topology: element counts, shells with their Euler characteristic, "
                        + "boundary loops, non-manifold edges and duplicate-position vertices.")
                .param(PATH, RouteParamType.STRING, false, "",
                        "Mesh file to analyse; empty analyses the active viewer mesh.",
                        "/home/acw/crawfish/IMG_4109.glb")
                .param(DUPLICATE_TOLERANCE, RouteParamType.FLOAT, false,
                        String.valueOf(DEFAULT_DUPLICATE_TOLERANCE),
                        "Distance below which two vertices count as one position; 0 skips the scan.",
                        "1e-6")
                .responseHint("{ok, source, vertex_count, edge_count, face_count, face_side_count, "
                        + "triangle_count, euler_characteristic, boundary_edge_count, "
                        + "non_manifold_edge_count, unoriented_edge_count, isolated_vertex_count, "
                        + "non_manifold_boundary_vertex_count, unwalked_boundary_edge_count, "
                        + "duplicate_position_vertex_count, distinct_position_count, shell_count, "
                        + "boundary_loop_count, "
                        + "shells:[{faces, vertices, edges, boundary_edges, boundary_loops, euler}, ...], text}")
                .build();
    }

    /**
     * Runs the analysis and renders it as the route's JSON body.
     *
     * @param mesh mesh to analyse
     * @param tolerance duplicate-position distance to pass the analysis
     * @param source path the mesh came from, empty when it is the active viewer mesh
     * @return the response payload
     */
    private static JsonObject report(MeshTopology mesh, double tolerance, String source) {
        MeshTopologyAnalysis analysis = new MeshTopologyAnalysis();
        analysis.duplicateTolerance = tolerance;
        analysis.analyze(mesh);
        JsonObject result = new JsonObject();
        result.addProperty(OK, true);
        result.addProperty("source", source);
        result.addProperty("vertex_count", analysis.vertexCount);
        result.addProperty("edge_count", analysis.edgeCount);
        result.addProperty("face_count", analysis.faceCount);
        result.addProperty("face_side_count", analysis.faceSideCount);
        result.addProperty("triangle_count", analysis.triangleCount);
        result.addProperty("euler_characteristic", analysis.eulerCharacteristic);
        result.addProperty("boundary_edge_count", analysis.boundaryEdgeCount);
        result.addProperty("non_manifold_edge_count", analysis.nonManifoldEdgeCount);
        result.addProperty("unoriented_edge_count", analysis.unorientedEdgeCount);
        result.addProperty("isolated_vertex_count", analysis.isolatedVertexCount);
        result.addProperty("non_manifold_boundary_vertex_count",
                analysis.nonManifoldBoundaryVertexCount);
        result.addProperty("unwalked_boundary_edge_count", analysis.unwalkedBoundaryEdgeCount);
        result.addProperty("duplicate_position_vertex_count", analysis.duplicatePositionVertexCount);
        result.addProperty("distinct_position_count", analysis.distinctPositionCount);
        result.addProperty("shell_count", analysis.shellCount);
        result.addProperty("boundary_loop_count", analysis.boundaryLoopCount);
        JsonArray shells = new JsonArray();
        for (int shell = 0; shell < analysis.shellFaceCounts.length; shell++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("faces", analysis.shellFaceCounts[shell]);
            entry.addProperty("vertices", analysis.shellVertexCounts[shell]);
            entry.addProperty("edges", analysis.shellEdgeCounts[shell]);
            entry.addProperty("boundary_edges", analysis.shellBoundaryEdgeCounts[shell]);
            entry.addProperty("boundary_loops", analysis.shellBoundaryLoopCounts[shell]);
            entry.addProperty("euler", analysis.shellEulerCharacteristics[shell]);
            shells.add(entry);
        }
        result.add("shells", shells);
        result.addProperty("text", analysis.toText());
        return result;
    }

    /**
     * An error body carrying one message.
     *
     * @param message what went wrong
     * @return the response payload
     */
    private static JsonObject failure(String message) {
        JsonObject error = new JsonObject();
        error.addProperty(OK, false);
        error.addProperty(ERROR, message);
        return error;
    }
}
