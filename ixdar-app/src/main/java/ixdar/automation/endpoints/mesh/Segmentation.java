package ixdar.automation.endpoints.mesh;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.automation.AutomationEndpoint;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.geometry.mesh.data.analysis.segmentation.MeshSegmenter;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.representation.MeshTopology;

import java.io.File;
import java.io.IOException;
import java.util.Map;

@AutomationRouteAnnotation(path = "/mesh/segmentation", method = APIMethod.POST)
public class Segmentation extends AutomationEndpoint implements AutomationRoute {
    public static final String PATH = "path";
    public static final String METHOD = "method";
    public static final String SPATIAL = "spatial";
    public static final String N_CLUSTERS = "n_clusters";
    public static final int DEFAULT_CLUSTER_COUNT = 6;

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String path = body.has(PATH) ? body.get(PATH).getAsString() : "";
        String method = body.has(METHOD)
                ? body.get(METHOD).getAsString()
                : SPATIAL;
        int nClusters = body.has(N_CLUSTERS)
                ? body.get(N_CLUSTERS).getAsInt()
                : DEFAULT_CLUSTER_COUNT;
        File f = resolvePath(path);
        if (f == null) {
            return failure("File not found: " + path);
        }
        MeshTopology mesh = MeshLoader.load(f.getAbsolutePath());
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
            return failure("Unknown method: " + method + " (expected components|curvature|spatial)");
        }
        }
        JsonObject result = success();
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

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Segment a mesh into labeled vertex groups by connected components, curvature, or spatial clustering.")
                .param(PATH, RouteParamType.STRING, true, "",
                        "Path to the OBJ mesh file to segment.", "~/Blends/Hand/Hand.obj")
                .param(METHOD, RouteParamType.STRING, false, SPATIAL,
                        "Segmentation strategy: components, curvature, or spatial.", SPATIAL)
                .param(N_CLUSTERS, RouteParamType.INT, false, String.valueOf(DEFAULT_CLUSTER_COUNT),
                        "Cluster count for the curvature and spatial strategies.", "8")
                .responseHint("{ok, vertex_count, method, tags:{label:[vertexIndex, ...]}}")
                .build();
    }
}
