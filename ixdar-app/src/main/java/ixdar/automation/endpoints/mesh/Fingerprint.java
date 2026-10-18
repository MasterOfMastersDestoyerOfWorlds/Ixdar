package ixdar.automation.endpoints.mesh;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.geometry.mesh.MeshCanonicalFingerprint;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.automation.AutomationEndpoint;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "/mesh/fingerprint", method = APIMethod.GET)
public class Fingerprint extends AutomationEndpoint implements AutomationRoute {
    public static final String ALGORITHM = "algorithm";

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            return runtime.runOnMainThread(() -> {
                boolean viewerActive = runtime.canvas instanceof MeshNodeViewerScene;
                MeshTopology mesh = viewerActive ? ((MeshNodeViewerScene) runtime.canvas).getMesh() : null;
                if (mesh == null) {
                    JsonObject failed = failure(viewerActive ? "Mesh not loaded yet"
                            : "MeshNodeViewerScene is not active");
                    failed.addProperty(ALGORITHM, MeshCanonicalFingerprint.ALGORITHM_ID);
                    return failed;
                }
                JsonObject result = new JsonObject();
                result.addProperty(ALGORITHM, MeshCanonicalFingerprint.ALGORITHM_ID);
                result.addProperty(OK, true);
                result.addProperty("sha256", MeshCanonicalFingerprint.sha256Hex(mesh));
                result.addProperty("vertexCount", mesh.vertexCount());
                result.addProperty("faceCount", mesh.faceCount());
                result.addProperty("triangleCount", MeshCanonicalFingerprint.triangleCount(mesh));
                return result;
            });
        } catch (Exception e) {
            return failure(e.getMessage() == null ? "" : e.getMessage());
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Compute the canonical SHA-256 fingerprint of the active viewer mesh.")
                .responseHint("{algorithm, ok, sha256, vertexCount, faceCount, triangleCount}")
                .build();
    }
}
