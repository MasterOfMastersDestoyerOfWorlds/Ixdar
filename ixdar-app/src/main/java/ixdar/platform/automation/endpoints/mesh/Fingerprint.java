package ixdar.platform.automation.endpoints.mesh;

import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.geometry.mesh.MeshCanonicalFingerprint;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "/mesh/fingerprint", method = APIMethod.GET)
public class Fingerprint extends AutomationEndpoint implements AutomationRoute {

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            return runtime.runOnMainThread(() -> {
                JsonObject result = new JsonObject();
                result.addProperty("algorithm", MeshCanonicalFingerprint.ALGORITHM_ID);
                if (!(runtime.canvas instanceof MeshNodeViewerScene)) {
                    result.addProperty("ok", false);
                    result.addProperty("error", "MeshNodeViewerScene is not active");
                    return result;
                }
                MeshNodeViewerScene mvs = (MeshNodeViewerScene) runtime.canvas;
                MeshTopology mesh = mvs.getMesh();
                if (mesh == null) {
                    result.addProperty("ok", false);
                    result.addProperty("error", "Mesh not loaded yet");
                    return result;
                }
                result.addProperty("ok", true);
                result.addProperty("sha256", MeshCanonicalFingerprint.sha256Hex(mesh));
                result.addProperty("vertexCount", mesh.vertexCount());
                result.addProperty("faceCount", mesh.faceCount());
                result.addProperty("triangleCount", MeshCanonicalFingerprint.triangleCount(mesh));
                return result;
            });
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", e.getMessage() == null ? "" : e.getMessage());
            return err;
        }
    }
}
