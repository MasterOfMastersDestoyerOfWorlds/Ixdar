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

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            String referencePath = body.has("reference")
                    ? body.get("reference").getAsString()
                    : "";
            String distanceType = body.has("distance_type")
                    ? body.get("distance_type").getAsString()
                    : "HAUSDORFF";
            float scale = body.has("scale")
                    ? body.get("scale").getAsFloat()
                    : 1.0f;
            boolean normalize = body.has("normalize") && body.get("normalize").getAsBoolean();

            if (referencePath.isEmpty()) {
                JsonObject err = new JsonObject();
                err.addProperty("ok", false);
                err.addProperty("error", "Missing required field: reference");
                return err;
            }

            try {
                return runtime.runOnMainThread(() -> {
                    JsonObject result = new JsonObject();
                    result.addProperty("ok", false);

                    if (!(runtime.canvas instanceof MeshNodeViewerScene)) {
                        result.addProperty(
                                "error",
                                "MeshNodeViewerScene is not active");
                        return result;
                    }
                    MeshNodeViewerScene mvs = (MeshNodeViewerScene) runtime.canvas;
                    MeshTopology currentMeshTopology = mvs.getMesh();
                    if (currentMeshTopology == null) {
                        result.addProperty("error", "Mesh not loaded yet");
                        return result;
                    }

                    // Convert current mesh to ArrayMesh
                    ixdar.geometry.mesh.data.ArrayMesh currentMesh;
                    if (currentMeshTopology instanceof ixdar.geometry.mesh.data.ArrayMesh) {
                        currentMesh = (ixdar.geometry.mesh.data.ArrayMesh) currentMeshTopology;
                    } else {
                        currentMesh = ixdar.geometry.mesh.data.ArrayMeshEngine.fromUniformMeshTopology(
                                currentMeshTopology);
                    }

                    // Load reference mesh
                    ixdar.geometry.mesh.data.ArrayMesh referenceMesh;
                    try {
                        referenceMesh = ixdar.geometry.mesh.data.MeshLoader.load(
                                referencePath);
                    } catch (Exception e) {
                        result.addProperty(
                                "error",
                                "Failed to load reference mesh: " + e.getMessage());
                        return result;
                    }

                    if (referenceMesh.vertexCount() == 0) {
                        result.addProperty("error", "Reference mesh is empty");
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

                    result.addProperty("ok", true);
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
                    result.addProperty("distance_type", distanceType);
                    result.addProperty("scale", effectiveScale);
                    result.addProperty("normalized", normalize);

                    return result;
                });
            } catch (Exception e) {
                JsonObject err = new JsonObject();
                err.addProperty("ok", false);
                err.addProperty(
                        "error",
                        e.getMessage() == null ? "" : e.getMessage());
                return err;
            }
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", e.getMessage());
            return err;
        }
    }
}
