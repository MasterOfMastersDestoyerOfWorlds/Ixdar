package ixdar.platform.automation.endpoints.mesh;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.automation.endpoints.AutomationRuntime;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "mesh/compare", method = APIMethod.POST)
public class Compare extends AutomationEndpoint implements AutomationRoute {
    public static final String REFERENCE = "reference";
    public static final String DISTANCE_TYPE = "distance_type";
    public static final String SCALE = "scale";
    public static final String NORMALIZE = "normalize";
    public static final String OK = "ok";
    public static final String ERROR = "error";

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            String referencePath = body.has(REFERENCE)
                    ? body.get(REFERENCE).getAsString()
                    : "";
            String distanceType = body.has(DISTANCE_TYPE)
                    ? body.get(DISTANCE_TYPE).getAsString()
                    : "HAUSDORFF";
            float scale = body.has(SCALE)
                    ? body.get(SCALE).getAsFloat()
                    : 1.0f;
            boolean normalize = body.has(NORMALIZE) && body.get(NORMALIZE).getAsBoolean();

            if (referencePath.isEmpty()) {
                JsonObject err = new JsonObject();
                err.addProperty(OK, false);
                err.addProperty(ERROR, "Missing required field: reference");
                return err;
            }

            try {
                return runtime.runOnMainThread(() -> {
                    JsonObject result = new JsonObject();
                    result.addProperty(OK, false);

                    if (!(runtime.canvas instanceof MeshNodeViewerScene)) {
                        result.addProperty(
                                ERROR,
                                "MeshNodeViewerScene is not active");
                        return result;
                    }
                    MeshNodeViewerScene mvs = (MeshNodeViewerScene) runtime.canvas;
                    MeshTopology currentMeshTopology = mvs.getMesh();
                    if (currentMeshTopology == null) {
                        result.addProperty(ERROR, "Mesh not loaded yet");
                        return result;
                    }

                    // Convert current mesh to ArrayMesh
                    ixdar.geometry.mesh.data.representation.ArrayMesh currentMesh;
                    if (currentMeshTopology instanceof ixdar.geometry.mesh.data.representation.ArrayMesh) {
                        currentMesh = (ixdar.geometry.mesh.data.representation.ArrayMesh) currentMeshTopology;
                    } else {
                        currentMesh = ixdar.geometry.mesh.data.representation.ArrayMeshEngine.fromUniformMeshTopology(
                                currentMeshTopology);
                    }

                    // Load reference mesh
                    ixdar.geometry.mesh.data.representation.ArrayMesh referenceMesh;
                    try {
                        referenceMesh = ixdar.geometry.mesh.data.load.MeshLoader.load(
                                referencePath);
                    } catch (Exception e) {
                        result.addProperty(
                                ERROR,
                                "Failed to load reference mesh: " + e.getMessage());
                        return result;
                    }

                    if (referenceMesh.vertexCount() == 0) {
                        result.addProperty(ERROR, "Reference mesh is empty");
                        return result;
                    }

                    // Normalize: center both meshes to origin, scale to unit bounding box diagonal
                    if (normalize) {
                        AutomationRuntime.normalizeMeshPositions(currentMesh);
                        AutomationRuntime.normalizeMeshPositions(referenceMesh);
                    }

                    float effectiveScale = (Float.isNaN(scale) || scale <= 0f)
                            ? 1.0f
                            : scale;
                    ixdar.geometry.mesh.data.MeshDistance.MeshMetrics metrics = ixdar.geometry.mesh.data.MeshDistance
                            .computeAllMetrics(
                                    currentMesh,
                                    referenceMesh,
                                    effectiveScale);

                    result.addProperty(OK, true);
                    result.addProperty(
                            "current_vertices",
                            currentMesh.vertexCount());
                    result.addProperty(
                            "reference_vertices",
                            referenceMesh.vertexCount());
                    result.addProperty(
                            "hausdorff_distance",
                            metrics.hausdorffDistance);
                    result.addProperty(
                            "chamfer_distance",
                            metrics.chamferDistance);
                    result.addProperty(
                            "similarity_score",
                            metrics.similarityScore);
                    result.addProperty(DISTANCE_TYPE, distanceType);
                    result.addProperty(SCALE, effectiveScale);
                    result.addProperty("normalized", normalize);

                    return result;
                });
            } catch (Exception e) {
                JsonObject err = new JsonObject();
                err.addProperty(OK, false);
                err.addProperty(
                        ERROR,
                        e.getMessage() == null ? "" : e.getMessage());
                return err;
            }
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            err.addProperty(ERROR, e.getMessage());
            return err;
        }
    }
}
