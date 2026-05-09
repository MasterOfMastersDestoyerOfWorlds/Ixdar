package ixdar.platform.automation.endpoints.mesh.patches;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(path = "/mesh/patches/render-flat-multiview", method = APIMethod.POST)
public class RenderFlatMultiview extends AutomationEndpoint implements AutomationRoute {
    public static final String PATH = "path";
    public static final String RESOLUTION = "resolution";
    public static final String OUT_PATH = "out_path";
    public static final String OK = "ok";
    public static final int NUM_128 = 128;
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String path = body.has(PATH) ? body.get(PATH).getAsString() : "";
        int resolution = body.has(RESOLUTION) ? body.get(RESOLUTION).getAsInt() : NUM_128;
        String outPath = body.has(OUT_PATH) ? body.get(OUT_PATH).getAsString() : "";
        File f = resolvePath(path);
        if (f == null) {
            JsonObject err = new JsonObject();
            err.addProperty(OK, false);
            err.addProperty("error", "File not found: " + path);
            return err;
        }
        ixdar.geometry.mesh.data.representation.ArrayMesh mesh = ixdar.geometry.mesh.data.load.MeshLoader.load(f.getAbsolutePath());
        ixdar.geometry.mesh.data.PatchDecomposition decomposition = ixdar.geometry.mesh.data.SemanticPatchDecomposer
                .decompose(mesh, resolution);
        BufferedImage composite = ixdar.geometry.mesh.data.PatchRenderer.renderMultiviewFlat(mesh, decomposition);
        File out;
        if (outPath == null || outPath.isBlank()) {
            out = new File("screenshots/automation", "patches-flat-multiview-" + System.currentTimeMillis() + ".png");
        } else {
            out = new File(outPath);
            if (!out.isAbsolute())
                out = new File(System.getProperty("user.dir"), outPath);
        }
        File parent = out.getParentFile();
        if (parent != null)
            parent.mkdirs();
        ImageIO.write(composite, "PNG", out);
        JsonObject result = new JsonObject();
        result.addProperty(OK, true);
        result.addProperty(PATH, out.getAbsolutePath());
        result.addProperty("width", composite.getWidth());
        result.addProperty("height", composite.getHeight());
        result.addProperty("patch_count", decomposition.patches().size());
        JsonArray palette = new JsonArray();
        for (ixdar.geometry.mesh.data.Patch p : decomposition.patches()) {
            JsonObject pj = new JsonObject();
            pj.addProperty("id", p.id());
            pj.addProperty("flat_color", ixdar.geometry.mesh.data.PatchRenderer.uniquePatchColorHex(p.id()));
            pj.addProperty("vertex_count", p.vertexIndices().length);
            palette.add(pj);
        }
        result.add("palette", palette);
        return result;
    }
}
