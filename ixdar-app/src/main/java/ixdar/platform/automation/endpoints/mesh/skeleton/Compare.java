package ixdar.platform.automation.endpoints.mesh.skeleton;

import java.io.File;
import java.io.IOException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.MeshLoader;
import ixdar.geometry.mesh.data.MeshSkeletonComparator;
import ixdar.geometry.mesh.data.MeshSkeletonExtractor;
import ixdar.platform.automation.AutomationEndpoint;

/**
 * Compare skeletons of two meshes: extract TEASAR from both, match branches,
 * compute errors, and return parameter recommendations. Pure CPU — no GL
 * context needed.
 */
@AutomationRouteAnnotation(path = "mesh/skeleton/compare", method = APIMethod.POST)
public class Compare extends AutomationEndpoint implements AutomationRoute {
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            String generatedPath = body.has("generated")
                    ? body.get("generated").getAsString()
                    : "";
            String referencePath = body.has("reference")
                    ? body.get("reference").getAsString()
                    : "";
            int resolution = body.has("resolution")
                    ? body.get("resolution").getAsInt()
                    : 128;
            if (generatedPath.isEmpty() || referencePath.isEmpty()) {
                JsonObject err = new JsonObject();
                err.addProperty("ok", false);
                err.addProperty(
                        "error",
                        "Provide 'generated' and 'reference' OBJ paths");
                return err;
            }
            // Resolve and validate generated mesh path
            File genFile = new File(generatedPath);
            if (!genFile.isAbsolute())
                genFile = new File(
                        System.getProperty("user.dir"),
                        generatedPath);
            if (!genFile.exists()) {
                JsonObject err = new JsonObject();
                err.addProperty("ok", false);
                err.addProperty(
                        "error",
                        "Generated mesh not found: " + genFile.getAbsolutePath());
                return err;
            }

            // Resolve and validate reference mesh path
            File refFile = new File(referencePath);
            if (!refFile.isAbsolute())
                refFile = new File(
                        System.getProperty("user.dir"),
                        referencePath);
            if (!refFile.exists()) {
                JsonObject err = new JsonObject();
                err.addProperty("ok", false);
                err.addProperty(
                        "error",
                        "Reference mesh not found: " + refFile.getAbsolutePath());
                return err;
            }

            ArrayMesh genMesh = MeshLoader.load(genFile.getAbsolutePath());
            ArrayMesh refMesh = MeshLoader.load(refFile.getAbsolutePath());

            MeshSkeletonExtractor.SkeletonResult genSkel = MeshSkeletonExtractor.extract(genMesh, resolution);
            MeshSkeletonExtractor.SkeletonResult refSkel = MeshSkeletonExtractor.extract(refMesh, resolution);

            MeshSkeletonComparator.ComparisonResult comparison = MeshSkeletonComparator.compare(genSkel, refSkel);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonObject result = JsonParser.parseString(
                    gson.toJson(comparison)).getAsJsonObject();
            result.addProperty("ok", true);
            result.addProperty("generated_path", genFile.getAbsolutePath());
            result.addProperty("reference_path", refFile.getAbsolutePath());
            result.addProperty("resolution", resolution);

            return result;
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", e.getMessage());
            return err;
        }
    }
}
