package ixdar.automation.endpoints.mesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.GltfMeshWriter;
import ixdar.geometry.mesh.data.io.MeshExport;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.quadlayout.QuadLayout;
import ixdar.geometry.mesh.quadlayout.embedding.model.EmbeddedPatch;
import ixdar.nodegraph.nodes.quadlayout.QuadLayoutNode;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.scenes.QuadLayoutScene;
import ixdar.scenes.model.ModelScene;

/**
 * Writes the quad-layout scene's finished layout to a {@code .glb}: the extracted quad mesh, or
 * every patch's quad grid on the surface, so a program outside Ixdar can take the layout away.
 */
@AutomationRouteAnnotation(path = "/mesh/quad-layout/export", method = APIMethod.POST)
public class QuadLayoutExport extends AutomationEndpoint implements AutomationRoute {

    public static final String PATH = "path";

    public static final String GEOMETRY = "geometry";

    public static final String GEOMETRY_QUADS = "quads";

    public static final String GEOMETRY_PATCHES = "patches";

    public static final String NODE = "node";

    /**
     * {@code POST /mesh/quad-layout/export}: write the active quad-layout scene's layout to a glTF
     * binary and report its quad, patch and cone counts.
     *
     * @param body {@code path} to write (relative to the checkout), and an optional
     *             {@code geometry}, {@code quads} or {@code patches}
     * @throws IOException when the file cannot be written
     * @return what was written, or an error object when no layout has been built
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String path = body.has(PATH) ? body.get(PATH).getAsString() : "";
        if (path.isEmpty()) {
            return failure("Missing required field: path");
        }
        String geometry = body.has(GEOMETRY) ? body.get(GEOMETRY).getAsString() : GEOMETRY_QUADS;
        if (!GEOMETRY_QUADS.equals(geometry) && !GEOMETRY_PATCHES.equals(geometry)) {
            return failure("geometry must be " + GEOMETRY_QUADS + " or " + GEOMETRY_PATCHES);
        }
        String node = body.has(NODE) ? body.get(NODE).getAsString() : QuadLayoutNode.LAYOUT_CALL;
        try {
            return runtime.runOnMainThread(() -> {
                if (!(runtime.canvas instanceof ModelScene scene)) {
                    return failure("No model scene is active");
                }
                QuadLayout layout = null;
                if (scene instanceof QuadLayoutScene quadScene && quadScene.layout != null) {
                    layout = quadScene.layout;
                } else if (scene.graph != null && scene.modelLoads.finishedFailure == null
                        && scene.sceneOutput(node + NodeGraphRuntime.PORT_SEPARATOR
                                + QuadLayoutNode.LAYOUT.name) instanceof QuadLayout built) {
                    layout = built;
                }
                if (layout == null) {
                    String loadFailure = scene.modelLoads.finishedFailure;
                    return failure(loadFailure == null
                            ? "The quad layout has not been built"
                            : "The quad layout was not built: " + loadFailure);
                }
                GeometryBundle chosen = GEOMETRY_PATCHES.equals(geometry)
                        ? layout.patchSurfaces : layout.quads;
                if (chosen == null || chosen.mesh() == null) {
                    return failure("The layout has no " + geometry + " geometry");
                }
                Path file = MeshLoader.resolve(path);
                MeshExport written = GltfMeshWriter.write(chosen, file);
                int livePatches = 0;
                for (EmbeddedPatch patch : layout.tmesh.patches) {
                    if (patch.alive) {
                        livePatches++;
                    }
                }
                JsonObject result = success();
                result.addProperty(PATH, file.toAbsolutePath().toString());
                result.addProperty(GEOMETRY, geometry);
                result.addProperty("bytes", Files.size(file));
                result.addProperty("vertex_count", written.vertexCount());
                result.addProperty("triangle_count", written.triangleCount());
                result.addProperty("quad_count", layout.quads == null ? 0 : layout.quads.mesh().faceCount());
                result.addProperty("patch_count", livePatches);
                result.addProperty("singularity_count", layout.crossField.singularityCount());
                return result;
            });
        } catch (Exception failed) {
            return failure(failed.getMessage() == null ? failed.toString() : failed.getMessage());
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Write the quad-layout scene's layout, the extracted quad mesh or every"
                        + " patch's quad grid, to a glTF binary and report its quad, patch and cone"
                        + " counts.")
                .param(PATH, RouteParamType.STRING, true, "",
                        "File to write, relative to the checkout like every CLI path; missing parent "
                                + "directories are created.",
                        "tmp/quad_layout.glb")
                .param(GEOMETRY, RouteParamType.STRING, false, GEOMETRY_QUADS,
                        "quads for the extracted quad mesh, patches for the patch grids on the surface.",
                        GEOMETRY_PATCHES)
                .param(NODE, RouteParamType.STRING, false, QuadLayoutNode.LAYOUT_CALL,
                        "The quad_layout call to read when the scene ran a DSL graph, by its name"
                                + " in the graph.",
                        "layout")
                .responseHint("{ok, path, geometry, bytes, vertex_count, triangle_count, quad_count, "
                        + "patch_count, singularity_count}")
                .build();
    }

}
