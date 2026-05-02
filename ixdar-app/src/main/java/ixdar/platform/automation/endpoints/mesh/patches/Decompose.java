package ixdar.platform.automation.endpoints.mesh.patches;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import ixdar.annotations.automation.APIMethod;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.annotations.automation.AutomationRoute;
import java.io.File;
import java.io.IOException;
import ixdar.annotations.automation.AutomationRouteAnnotation;

@AutomationRouteAnnotation(path = "/mesh/patches/decompose", method = APIMethod.POST)
public class Decompose extends AutomationEndpoint implements AutomationRoute {

    @Override
    public JsonObject endpointHandler(HttpExchange exchange) throws IOException {
        JsonObject body = readBodyJson(exchange);
        String path = body.has("path") ? body.get("path").getAsString() : "";
        int resolution = body.has("resolution")
                ? body.get("resolution").getAsInt()
                : 128;
        File f = resolvePath(path);
        if (f == null) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", "File not found: " + path);
            return err;
        }
        ixdar.geometry.mesh.data.ArrayMesh mesh = ixdar.geometry.mesh.data.MeshLoader.load(f.getAbsolutePath());
        ixdar.geometry.mesh.data.PatchDecomposition decomposition = ixdar.geometry.mesh.data.SemanticPatchDecomposer
                .decompose(
                        mesh,
                        resolution);
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("vertex_count", decomposition.vertexCount());
        JsonArray patches = new JsonArray();
        for (ixdar.geometry.mesh.data.Patch p : decomposition.patches()) {
            JsonObject pj = new JsonObject();
            pj.addProperty("id", p.id());
            pj.addProperty("branch_id", p.branchId());
            pj.addProperty("color", p.color());
            pj.addProperty("curvature_mean", p.curvatureMean());
            JsonArray centroid = new JsonArray();
            for (float c : p.centroid())
                centroid.add(c);
            pj.add("centroid", centroid);
            JsonArray verts = new JsonArray();
            for (int v : p.vertexIndices())
                verts.add(v);
            pj.add("vertex_indices", verts);
            JsonArray faces = new JsonArray();
            for (int fi : p.faceIndices())
                faces.add(fi);
            pj.add("face_indices", faces);
            patches.add(pj);
        }
        out.add("patches", patches);
        return out;
    }
}
