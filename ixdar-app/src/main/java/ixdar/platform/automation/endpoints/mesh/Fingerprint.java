package ixdar.platform.automation.endpoints.mesh;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.geometry.mesh.MeshCanonicalFingerprint;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "/mesh/fingerprint", method = APIMethod.GET)
public class Fingerprint extends AutomationEndpoint implements AutomationRoute {
    public static final String OK = "ok";
    public static final String ERROR = "error";

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            return runtime.runOnMainThread(() -> {
                JsonObject result = new JsonObject();
                result.addProperty("algorithm", MeshCanonicalFingerprint.ALGORITHM_ID);
                if (!(runtime.canvas instanceof MeshNodeViewerScene)) {
                    result.addProperty(OK, false);
                    result.addProperty(ERROR, "MeshNodeViewerScene is not active");
                    return result;
                }
                MeshNodeViewerScene mvs = (MeshNodeViewerScene) runtime.canvas;
                MeshTopology mesh = mvs.getMesh();
                if (mesh == null) {
                    result.addProperty(OK, false);
                    result.addProperty(ERROR, "Mesh not loaded yet");
                    return result;
                }
                result.addProperty(OK, true);
                result.addProperty("sha256", MeshCanonicalFingerprint.sha256Hex(mesh));
                result.addProperty("vertexCount", mesh.vertexCount());
                result.addProperty("faceCount", mesh.faceCount());
                result.addProperty("triangleCount", MeshCanonicalFingerprint.triangleCount(mesh));
                return result;
            });
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            err.addProperty(ERROR, e.getMessage() == null ? "" : e.getMessage());
            return err;
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
