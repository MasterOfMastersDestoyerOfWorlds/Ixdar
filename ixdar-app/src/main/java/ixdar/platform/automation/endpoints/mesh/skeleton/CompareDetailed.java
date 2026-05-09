package ixdar.platform.automation.endpoints.mesh.skeleton;

import java.io.File;
import java.io.IOException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.geometry.mesh.data.MeshSkeletonComparator;
import ixdar.geometry.mesh.data.MeshSkeletonExtractor;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.platform.automation.AutomationEndpoint;

/**
 * Detailed skeleton comparison returning per-joint 3D position deltas. Pure CPU
 * — no GL context needed.
 */
@AutomationRouteAnnotation(path = "mesh/skeleton/compare-detailed", method = APIMethod.POST)
public class CompareDetailed extends AutomationEndpoint implements AutomationRoute {
    public static final String GENERATED = "generated";
    public static final String REFERENCE = "reference";
    public static final String RESOLUTION = "resolution";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String USER_DIR = "user.dir";
    public static final int NUM_128 = 128;
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            String generatedPath = body.has(GENERATED) ? body.get(GENERATED).getAsString() : "";
            String referencePath = body.has(REFERENCE) ? body.get(REFERENCE).getAsString() : "";
            int resolution = body.has(RESOLUTION) ? body.get(RESOLUTION).getAsInt() : NUM_128;
            if (generatedPath.isEmpty() || referencePath.isEmpty()) {
                JsonObject err = new JsonObject();
                err.addProperty(OK, false);
                err.addProperty(
                        ERROR,
                        "Provide 'generated' and 'reference' OBJ paths");
                return err;
            }
            File genFile = new File(generatedPath);
            if (!genFile.isAbsolute())
                genFile = new File(
                        System.getProperty(USER_DIR),
                        generatedPath);
            if (!genFile.exists()) {
                JsonObject err = new JsonObject();
                err.addProperty(OK, false);
                err.addProperty(
                        ERROR,
                        "Generated mesh not found: " + genFile.getAbsolutePath());
                return err;
            }
            File refFile = new File(referencePath);
            if (!refFile.isAbsolute())
                refFile = new File(
                        System.getProperty(USER_DIR),
                        referencePath);
            if (!refFile.exists()) {
                JsonObject err = new JsonObject();
                err.addProperty(OK, false);
                err.addProperty(
                        ERROR,
                        "Reference mesh not found: " + refFile.getAbsolutePath());
                return err;
            }

            ArrayMesh genMesh = MeshLoader.load(genFile.getAbsolutePath());
            ArrayMesh refMesh = MeshLoader.load(refFile.getAbsolutePath());

            MeshSkeletonExtractor.SkeletonResult genSkel = MeshSkeletonExtractor.extract(genMesh, resolution);
            MeshSkeletonExtractor.SkeletonResult refSkel = MeshSkeletonExtractor.extract(refMesh, resolution);

            MeshSkeletonComparator.DetailedComparisonResult comparison = MeshSkeletonComparator.compareDetailed(genSkel,
                    refSkel);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonObject result = JsonParser.parseString(
                    gson.toJson(comparison)).getAsJsonObject();
            result.addProperty(OK, true);
            result.addProperty("generated_path", genFile.getAbsolutePath());
            result.addProperty("reference_path", refFile.getAbsolutePath());
            result.addProperty(RESOLUTION, resolution);

            return result;
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            err.addProperty(ERROR, e.getMessage());
            return err;
        }
    }
}
