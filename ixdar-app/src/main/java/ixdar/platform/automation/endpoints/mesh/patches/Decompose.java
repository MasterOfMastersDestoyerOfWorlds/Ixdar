package ixdar.platform.automation.endpoints.mesh.patches;

import java.io.File;
import java.io.IOException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(path = "/mesh/patches/decompose", method = APIMethod.POST)
public class Decompose extends AutomationEndpoint implements AutomationRoute {
    public static final String PATH = "path";
    public static final String RESOLUTION = "resolution";
    public static final String OK = "ok";
    public static final int NUM_128 = 128;

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String path = body.has(PATH) ? body.get(PATH).getAsString() : "";
        int resolution = body.has(RESOLUTION)
                ? body.get(RESOLUTION).getAsInt()
                : NUM_128;
        File f = resolvePath(path);
        if (f == null) {
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            err.addProperty("error", "File not found: " + path);
            return err;
        }
        ixdar.geometry.mesh.data.representation.ArrayMesh mesh = ixdar.geometry.mesh.data.load.MeshLoader.load(f.getAbsolutePath());
        ixdar.geometry.mesh.data.PatchDecomposition decomposition = ixdar.geometry.mesh.data.SemanticPatchDecomposer
                .decompose(
                        mesh,
                        resolution);
        JsonObject out = new JsonObject();
        out.addProperty(OK, true);
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
