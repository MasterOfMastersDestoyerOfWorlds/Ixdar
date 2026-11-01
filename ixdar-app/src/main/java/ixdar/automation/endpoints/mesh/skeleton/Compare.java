package ixdar.automation.endpoints.mesh.skeleton;

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
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonComparator;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.automation.AutomationEndpoint;

/**
 * Compare skeletons of two meshes: extract TEASAR from both, match branches,
 * compute errors, and return parameter recommendations. Pure CPU — no GL
 * context needed.
 */
@AutomationRouteAnnotation(id = "SkeletonCompare", path = "mesh/skeleton/compare", method = APIMethod.POST)
public class Compare extends AutomationEndpoint implements AutomationRoute {
    public static final String GENERATED = "generated";
    public static final String REFERENCE = "reference";
    public static final String RESOLUTION = "resolution";
    public static final int DEFAULT_VOXEL_RESOLUTION = 128;
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            String generatedPath = body.has(GENERATED)
                    ? body.get(GENERATED).getAsString()
                    : "";
            String referencePath = body.has(REFERENCE)
                    ? body.get(REFERENCE).getAsString()
                    : "";
            int resolution = body.has(RESOLUTION)
                    ? body.get(RESOLUTION).getAsInt()
                    : DEFAULT_VOXEL_RESOLUTION;
            if (generatedPath.isEmpty() || referencePath.isEmpty()) {
                return failure("Provide 'generated' and 'reference' OBJ paths");
            }
            File genFile = existingFile("--generated", generatedPath).toFile();
            File refFile = existingFile("--reference", referencePath).toFile();

            MeshTopology genMesh = MeshLoader.load(genFile.getAbsolutePath());
            MeshTopology refMesh = MeshLoader.load(refFile.getAbsolutePath());

            MeshSkeletonExtractor.SkeletonResult genSkel = MeshSkeletonExtractor.extract(genMesh, resolution);
            MeshSkeletonExtractor.SkeletonResult refSkel = MeshSkeletonExtractor.extract(refMesh, resolution);

            MeshSkeletonComparator.ComparisonResult comparison = MeshSkeletonComparator.compare(genSkel, refSkel);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonObject result = JsonParser.parseString(
                    gson.toJson(comparison)).getAsJsonObject();
            result.addProperty(OK, true);
            result.addProperty("generated_path", genFile.getAbsolutePath());
            result.addProperty("reference_path", refFile.getAbsolutePath());
            result.addProperty(RESOLUTION, resolution);

            return result;
        } catch (Exception e) {
            return failure(e.getMessage());
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Compare TEASAR skeletons of two meshes and recommend parameter fixes.")
                .param(GENERATED, RouteParamType.STRING, true, "",
                        "Path to the generated mesh OBJ.", "out/generated.obj")
                .param(REFERENCE, RouteParamType.STRING, true, "",
                        "Path to the reference mesh OBJ.", "meshes/target.obj")
                .param(RESOLUTION, RouteParamType.INT, false, String.valueOf(DEFAULT_VOXEL_RESOLUTION),
                        "Voxel grid resolution for skeleton extraction.", "256")
                .responseHint("{ok, generated_path, reference_path, resolution, ...branchErrors}")
                .build();
    }
}
