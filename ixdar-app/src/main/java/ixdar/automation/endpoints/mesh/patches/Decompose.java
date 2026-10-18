package ixdar.automation.endpoints.mesh.patches;

import java.io.File;
import java.io.IOException;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;

import ixdar.devtools.PatchDecomposerCLI;

import ixdar.geometry.mesh.data.analysis.segmentation.PatchDecomposition;

import ixdar.geometry.mesh.data.io.MeshLoader;

import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer;

import ixdar.geometry.mesh.data.representation.ArrayMesh;

@AutomationRouteAnnotation(path = "/mesh/patches/decompose", method = APIMethod.POST)
public class Decompose extends AutomationEndpoint implements AutomationRoute {
    public static final String PATH = "path";
    public static final String RESOLUTION = "resolution";
    public static final int DEFAULT_VOXEL_RESOLUTION = 128;

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String path = body.has(PATH) ? body.get(PATH).getAsString() : "";
        int resolution = body.has(RESOLUTION)
                ? body.get(RESOLUTION).getAsInt()
                : DEFAULT_VOXEL_RESOLUTION;
        File f = resolvePath(path);
        if (f == null) {
            return failure("File not found: " + path);
        }
        ArrayMesh mesh = MeshLoader.load(f.getAbsolutePath());
        PatchDecomposition decomposition = SemanticPatchDecomposer
                .decompose(
                        mesh,
                        resolution);
        return PatchDecomposerCLI.decompositionToJson(decomposition);
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Hybrid skeleton and curvature patch decomposition of a reference mesh.")
                .param(PATH, RouteParamType.STRING, true, "",
                        "Path to an OBJ mesh file.", "~/Blends/Hand/Hand.obj")
                .param(RESOLUTION, RouteParamType.INT, false, String.valueOf(DEFAULT_VOXEL_RESOLUTION),
                        "Voxel resolution for skeletonization.", "128")
                .responseHint(
                        "{ok, vertex_count, patches:[{id, branch_id, color, flat_color, centroid, ...}]}")
                .build();
    }
}
