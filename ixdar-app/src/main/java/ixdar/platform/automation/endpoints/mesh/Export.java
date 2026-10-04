package ixdar.platform.automation.endpoints.mesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.load.GltfMeshWriter;
import ixdar.geometry.mesh.data.load.MeshExport;
import ixdar.geometry.mesh.data.load.PlyMeshWriter;
import ixdar.geometry.mesh.nodes.data.ExportMeshNode;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.scenes.mesh.MeshNodeViewerScene;

@AutomationRouteAnnotation(path = "/mesh/export", method = APIMethod.POST)
public class Export extends AutomationEndpoint implements AutomationRoute {
    public static final String OK = "ok";
    public static final String ERROR = "error";
    public static final String PATH = "path";
    public static final String FORMAT = "format";

    /**
     * {@code POST /mesh/export}: write the active viewer's mesh, with its normals and per-corner
     * UVs, to a {@code .glb} or {@code .ply} file.
     *
     * @param body {@code path} to write, and an optional {@code format} overriding the extension
     * @throws IOException when the file cannot be written
     * @return what was written, or an error object when no mesh is loaded
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String path = body.has(PATH) ? body.get(PATH).getAsString() : "";
        if (path.isEmpty()) {
            return failure("Missing required field: path");
        }
        String requested = body.has(FORMAT) ? body.get(FORMAT).getAsString() : "";
        String format = ExportMeshNode.formatOf(path, requested);
        try {
            return runtime.runOnMainThread(() -> {
                if (!(runtime.canvas instanceof MeshNodeViewerScene viewer)) {
                    return failure("MeshNodeViewerScene is not active");
                }
                GeometryBundle bundle = viewer.getGeometryBundle();
                if (bundle == null || bundle.mesh() == null) {
                    return failure("Mesh not loaded yet");
                }
                return write(bundle, Path.of(path), format);
            });
        } catch (Exception failed) {
            return failure(failed.getMessage() == null ? failed.toString() : failed.getMessage());
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Write the active viewer mesh, with normals and per-corner UVs, to a "
                        + "glTF binary or ASCII PLY file.")
                .param(PATH, RouteParamType.STRING, true, "",
                        "File to write; missing parent directories are created.",
                        "/home/acw/crawfish/repaired/IMG_4109.glb")
                .param(FORMAT, RouteParamType.STRING, false, "",
                        "GLB or PLY; empty takes the format from the path's extension.", "glb")
                .responseHint("{ok, path, format, bytes, vertex_count, triangle_count, "
                        + "has_uv, zeroed_uv_corners}")
                .build();
    }

    /**
     * Write one bundle and report what the file holds.
     *
     * @param bundle geometry to write
     * @param file destination path
     * @param format {@link ExportMeshNode#FORMAT_GLB} or {@link ExportMeshNode#FORMAT_PLY}
     * @throws IOException when the mesh has no triangles or the file cannot be written
     * @return the response payload
     */
    private static JsonObject write(GeometryBundle bundle, Path file, String format)
            throws IOException {
        MeshExport written = ExportMeshNode.FORMAT_PLY.equals(format)
                ? PlyMeshWriter.write(bundle, file)
                : GltfMeshWriter.write(bundle, file);
        JsonObject result = new JsonObject();
        result.addProperty(OK, true);
        result.addProperty(PATH, file.toAbsolutePath().toString());
        result.addProperty(FORMAT, format);
        result.addProperty("bytes", Files.size(file));
        result.addProperty("vertex_count", written.vertexCount());
        result.addProperty("triangle_count", written.triangleCount());
        result.addProperty("has_uv", written.cornerU != null);
        result.addProperty("zeroed_uv_corners", written.nonFiniteUvCount);
        return result;
    }

    /**
     * An error body carrying one message.
     *
     * @param message what went wrong
     * @return the response payload
     */
    private static JsonObject failure(String message) {
        JsonObject error = new JsonObject();
        error.addProperty(OK, false);
        error.addProperty(ERROR, message);
        return error;
    }
}
