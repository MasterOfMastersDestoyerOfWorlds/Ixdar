package ixdar.platform.automation.endpoints.mesh;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.data.MeshSegmenter;

import java.io.File;
import java.io.IOException;
import java.util.Map;

@AutomationRouteAnnotation(path = "/mesh/segmentation", method = APIMethod.POST)
public class Segmentation extends AutomationEndpoint implements AutomationRoute {

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String path = body.has("path") ? body.get("path").getAsString() : "";
        String method = body.has("method")
                ? body.get("method").getAsString()
                : "spatial";
        int nClusters = body.has("n_clusters")
                ? body.get("n_clusters").getAsInt()
                : 6;
        File f = resolvePath(path);
        if (f == null) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", "File not found: " + path);
            return err;
        }
        ArrayMesh mesh = MeshLoader.load(f.getAbsolutePath());
        Map<String, int[]> tags;
        switch (method) {
        case "components" -> tags = MeshSegmenter.segmentComponents(mesh);
        case "curvature" -> tags = MeshSegmenter.segmentCurvature(
                mesh,
                nClusters);
        case "spatial" -> tags = MeshSegmenter.segmentSpatial(
                mesh,
                nClusters);
        default -> {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty(
                    "error",
                    "Unknown method: " +
                            method +
                            " (expected components|curvature|spatial)");
            return err;
        }
        }
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("vertex_count", mesh.vertexCount());
        result.addProperty("method", method);
        JsonObject tagsJson = new JsonObject();
        for (Map.Entry<String, int[]> e : tags.entrySet()) {
            JsonArray arr = new JsonArray();
            for (int v : e.getValue())
                arr.add(v);
            tagsJson.add(e.getKey(), arr);
        }
        result.add("tags", tagsJson);
        return result;
    }
}
