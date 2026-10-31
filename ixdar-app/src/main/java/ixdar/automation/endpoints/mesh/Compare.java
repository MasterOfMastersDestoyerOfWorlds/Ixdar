package ixdar.automation.endpoints.mesh;

import java.io.IOException;
import java.nio.file.Files;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.geometry.mesh.data.representation.MeshTopology;

import ixdar.geometry.mesh.data.analysis.MeshDistance;
import ixdar.automation.AutomationEndpoint;

import ixdar.geometry.mesh.data.io.MeshLoader;

import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(id = "MeshCompare", path = "mesh/compare", method = APIMethod.POST)
public class Compare extends AutomationEndpoint implements AutomationRoute {
    public static final String REFERENCE = "reference";
    public static final String DISTANCE_TYPE = "distance_type";
    public static final String SCALE = "scale";
    public static final String NORMALIZE = "normalize";

    /** Path of the reference mesh {@link #cachedReferenceMesh} was parsed from. */
    public static String cachedReferencePath = "";

    /** Modified time of that file when it was parsed; a newer file is parsed again. */
    public static long cachedReferenceModified = -1L;

    /** The last reference mesh parsed, reused while its file is unchanged. */
    public static MeshTopology cachedReferenceMesh;

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
                return failure("Missing required field: reference");
            }

            try {
                return runtime.runOnMainThread(() -> {
                    if (!(runtime.canvas instanceof MeshNodeViewerScene)) {
                        return failure("MeshNodeViewerScene is not active");
                    }
                    MeshNodeViewerScene mvs = (MeshNodeViewerScene) runtime.canvas;
                    MeshTopology currentMeshTopology = mvs.getMesh();
                    if (currentMeshTopology == null) {
                        return failure("Mesh not loaded yet");
                    }

                    MeshTopology currentMesh = currentMeshTopology;

                    // Load reference mesh
                    MeshTopology referenceMesh;
                    try {
                        long modified = Files.getLastModifiedTime(
                                MeshLoader.resolve(referencePath)).toMillis();
                        if (cachedReferenceMesh == null || modified != cachedReferenceModified
                                || !referencePath.equals(cachedReferencePath)) {
                            cachedReferenceMesh = MeshLoader.load(referencePath);
                            cachedReferencePath = referencePath;
                            cachedReferenceModified = modified;
                        }
                        referenceMesh = cachedReferenceMesh;
                    } catch (Exception e) {
                        return failure("Failed to load reference mesh: " + e.getMessage());
                    }

                    if (referenceMesh.vertexCount() == 0) {
                        return failure("Reference mesh is empty");
                    }

                    // Normalize: center both meshes to origin, scale to unit bounding box diagonal
                    float[] currentPositions = normalize
                            ? MeshDistance.normalizedPositions(currentMesh)
                            : MeshDistance.extractPositions(currentMesh);
                    float[] referencePositions = normalize
                            ? MeshDistance.normalizedPositions(referenceMesh)
                            : MeshDistance.extractPositions(referenceMesh);

                    float effectiveScale = (Float.isNaN(scale) || scale <= 0f)
                            ? 1.0f
                            : scale;
                    MeshDistance.MeshMetrics metrics = MeshDistance
                            .computeAllMetrics(
                                    currentPositions,
                                    referencePositions,
                                    effectiveScale);

                    JsonObject result = success();
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
                return failure(e.getMessage() == null ? "" : e.getMessage());
            }
        } catch (Exception e) {
            return failure(e.getMessage());
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Compare the active viewer mesh against a reference OBJ using Hausdorff and Chamfer metrics.")
                .param(REFERENCE, RouteParamType.STRING, true, "",
                        "Path to the reference OBJ mesh to compare against.", "~/Blends/Hand/Hand.obj")
                .param(DISTANCE_TYPE, RouteParamType.STRING, false, "hausdorff",
                        "Metric label echoed back in the response.", "chamfer")
                .param(SCALE, RouteParamType.FLOAT, false, "1.0",
                        "Scale factor applied when computing distance metrics.", "2.0")
                .param(NORMALIZE, RouteParamType.BOOL, false, "false",
                        "Center and unit-scale both meshes before comparing.", "true")
                .responseHint("{ok, current_vertices, reference_vertices, "
                        + "hausdorff_distance, chamfer_distance, similarity_score, distance_type, scale, normalized}")
                .build();
    }
}
