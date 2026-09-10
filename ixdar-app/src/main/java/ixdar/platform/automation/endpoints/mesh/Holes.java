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
import ixdar.geometry.mesh.data.ops.MeshRepairReport;
import ixdar.geometry.mesh.data.ops.MeshTopologyAnalysis;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "/mesh/holes", method = APIMethod.POST)
public class Holes extends AutomationEndpoint implements AutomationRoute {
    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String PATH = "path";

    /** Loop-entry key holding the loop's edge count. */
    public static final String EDGES = "edges";

    /** Loop-entry key holding the loop's summed edge length. */
    public static final String PERIMETER = "perimeter";

    /** Response key holding how many boundary loops the last {@code repair_mesh} found. */
    public static final String REPAIR_HOLE_COUNT = "repair_hole_count";

    /**
     * {@code POST /mesh/holes}: every boundary loop of a mesh with its edge count, perimeter and
     * area estimate, plus what {@code repair_mesh} filled when the active graph ran one.
     *
     * @param body optional {@code path}; empty reads the active viewer mesh
     * @throws IOException when the mesh file cannot be read
     * @return the loop tables, or an error object when no mesh can be resolved
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String path = body.has(PATH) ? body.get(PATH).getAsString() : "";
        if (!path.isEmpty()) {
            File file = resolvePath(path);
            if (file == null) {
                return failure("File not found: " + path);
            }
            return report(MeshLoader.load(file.getAbsolutePath()), null, path);
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
                return report(mesh, viewer.getLastRepairReport(), "");
            });
        } catch (Exception failed) {
            return failure(failed.getMessage() == null ? failed.toString() : failed.getMessage());
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("List every boundary loop of the mesh with its edge count, perimeter and area "
                        + "estimate, plus the loops repair_mesh filled and the triangles it used.")
                .param(PATH, RouteParamType.STRING, false, "",
                        "Mesh file to analyse; empty analyses the active viewer mesh.",
                        "/home/acw/crawfish/IMG_4109.glb")
                .responseHint("{ok, source, open_loop_count, open_perimeter_total, "
                        + "loops:[{edges, perimeter, area, shell}, ...], repair_hole_count, "
                        + "repair_filled_count, repair_fill_face_count, "
                        + "repair_holes:[{edges, perimeter, filled, fill_faces}, ...]}")
                .build();
    }

    /**
     * Walks the loops still open on {@code mesh} and, when a repair ran, the loops it closed.
     *
     * @param mesh mesh to analyse
     * @param repair the last {@code repair_mesh} report, or null when the graph carries none
     * @param source path the mesh came from, empty when it is the active viewer mesh
     * @return the response payload
     */
    private static JsonObject report(MeshTopology mesh, MeshRepairReport repair, String source) {
        MeshTopologyAnalysis analysis = new MeshTopologyAnalysis();
        analysis.duplicateTolerance = 0;
        analysis.analyze(mesh);
        JsonObject result = new JsonObject();
        result.addProperty(OK, true);
        result.addProperty("source", source);
        result.addProperty("open_loop_count", analysis.boundaryLoopCount);
        result.addProperty("unwalked_boundary_edge_count", analysis.unwalkedBoundaryEdgeCount);
        double perimeterTotal = 0;
        JsonArray loops = new JsonArray();
        for (int loop = 0; loop < analysis.loopEdgeCounts.length; loop++) {
            JsonObject entry = new JsonObject();
            entry.addProperty(EDGES, analysis.loopEdgeCounts[loop]);
            entry.addProperty(PERIMETER, analysis.loopPerimeters[loop]);
            entry.addProperty("area", analysis.loopAreas[loop]);
            entry.addProperty("shell", analysis.loopShells[loop]);
            loops.add(entry);
            perimeterTotal += analysis.loopPerimeters[loop];
        }
        result.addProperty("open_perimeter_total", perimeterTotal);
        result.add("loops", loops);
        if (repair == null) {
            result.addProperty(REPAIR_HOLE_COUNT, -1);
            return result;
        }
        result.addProperty(REPAIR_HOLE_COUNT, repair.holeCount);
        result.addProperty("repair_filled_count", repair.filledHoleCount);
        result.addProperty("repair_open_count", repair.openHoleCount);
        result.addProperty("repair_fill_face_count", repair.fillFaceCount);
        JsonArray repairHoles = new JsonArray();
        for (int hole = 0; hole < repair.holeEdgeCounts.length; hole++) {
            JsonObject entry = new JsonObject();
            entry.addProperty(EDGES, repair.holeEdgeCounts[hole]);
            entry.addProperty(PERIMETER, repair.holePerimeters[hole]);
            entry.addProperty("filled", repair.holeFilled[hole]);
            entry.addProperty("fill_faces", repair.holeFillFaceCounts[hole]);
            repairHoles.add(entry);
        }
        result.add("repair_holes", repairHoles);
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
