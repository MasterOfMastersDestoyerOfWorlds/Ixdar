package ixdar.platform.automation.endpoints.mesh;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.geometry.mesh.data.MeshSegmenter;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;

import java.io.File;
import java.io.IOException;
import java.util.Map;

@AutomationRouteAnnotation(path = "/mesh/segmentation", method = APIMethod.POST)
public class Segmentation extends AutomationEndpoint implements AutomationRoute {
    public static final String PATH = "path";
    public static final String METHOD = "method";
    public static final String SPATIAL = "spatial";
    public static final String N_CLUSTERS = "n_clusters";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final int NUM_6 = 6;

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String path = body.has(PATH) ? body.get(PATH).getAsString() : "";
        String method = body.has(METHOD)
                ? body.get(METHOD).getAsString()
                : SPATIAL;
        int nClusters = body.has(N_CLUSTERS)
                ? body.get(N_CLUSTERS).getAsInt()
                : NUM_6;
        File f = resolvePath(path);
        if (f == null) {
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            err.addProperty(ERROR, "File not found: " + path);
            return err;
        }
        ArrayMesh mesh = MeshLoader.load(f.getAbsolutePath());
        Map<String, int[]> tags;
        switch (method) {
        case "components" -> tags = MeshSegmenter.segmentComponents(mesh);
        case "curvature" -> tags = MeshSegmenter.segmentCurvature(
                mesh,
                nClusters);
        case SPATIAL -> tags = MeshSegmenter.segmentSpatial(
                mesh,
                nClusters);
        default -> {
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            err.addProperty(
                    ERROR,
                    "Unknown method: " +
                            method +
                            " (expected components|curvature|spatial)");
            return err;
        }
        }
        JsonObject result = new JsonObject();
        result.addProperty(OK, true);
        result.addProperty("vertex_count", mesh.vertexCount());
        result.addProperty(METHOD, method);
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
