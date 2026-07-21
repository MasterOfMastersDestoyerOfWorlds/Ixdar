package ixdar.platform.automation.endpoints.mesh.patches;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.platform.automation.AutomationEndpoint;

import ixdar.geometry.mesh.data.PatchDecomposition;

import ixdar.geometry.mesh.data.load.MeshLoader;

import ixdar.geometry.mesh.data.PatchRenderer;

import ixdar.geometry.mesh.data.SemanticPatchDecomposer;

import ixdar.geometry.mesh.data.representation.ArrayMesh;

@AutomationRouteAnnotation(path = "/mesh/patches/render-multiview", method = APIMethod.POST)
public class RenderMultiview extends AutomationEndpoint implements AutomationRoute {
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
        ArrayMesh mesh = MeshLoader.load(f.getAbsolutePath());
        PatchDecomposition decomposition = SemanticPatchDecomposer
                .decompose(mesh, resolution);
        BufferedImage composite = PatchRenderer.renderMultiview(mesh, decomposition);

        File out;
        if (outPath == null || outPath.isBlank()) {
            out = new File("screenshots/automation", "patches-multiview-" + System.currentTimeMillis() + ".png");
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
        return result;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Decompose a mesh into semantic patches and render a shaded multiview composite PNG.")
                .param(PATH, RouteParamType.STRING, true, "",
                        "Path to the OBJ mesh file to render.", "~/Blends/Hand/Hand.obj")
                .param(RESOLUTION, RouteParamType.INT, false, String.valueOf(NUM_128),
                        "Voxel resolution for patch decomposition.", "128")
                .param(OUT_PATH, RouteParamType.STRING, false, "",
                        "Destination PNG path; defaults to a timestamped file under screenshots/automation.",
                        "~/out/patches.png")
                .responseHint("{ok, path, width, height, patch_count}")
                .build();
    }
}
