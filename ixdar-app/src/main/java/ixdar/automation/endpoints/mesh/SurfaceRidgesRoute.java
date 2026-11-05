package ixdar.automation.endpoints.mesh;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.geometry.mesh.data.analysis.curvature.SurfaceRidges;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.sculpt.AnchoredStroke;
import ixdar.geometry.mesh.data.sculpt.FaceAnchors;
import ixdar.geometry.mesh.data.sculpt.SculptSync;
import ixdar.geometry.mesh.data.sculpt.StrokeAnchors;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * A mesh's own ridge and valley lines (OBS04), drawn into every cell of a multiview template that sees
 * them, for strokes_overlay over that capture.
 */
@AutomationRouteAnnotation(path = "/mesh/surface-ridges", method = APIMethod.POST)
public class SurfaceRidgesRoute extends AutomationEndpoint implements AutomationRoute {

    public static final String MESH = "mesh";

    public static final String TEMPLATE = "template";

    public static final String OUT = "out";

    public static final String LEVELS = "levels";

    public static final String STRENGTH = "strength";

    public static final String RIDGE_LAYER = "ridges";

    public static final String VALLEY_LAYER = "valleys";

    public static final float DRAWN_RADIUS = 0.01f;

    public static final long WAIT_SECONDS = 600;

    /**
     * {@code POST /mesh/surface-ridges}: trace the mesh's ridges and valleys and write them as an SVG.
     *
     * @param body mesh, template, out; optional levels, strength
     * @return the line counts, timings and the file written, or the refusal
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) {
        try {
            Path meshPath = existingFile("--mesh", body.has(MESH) ? body.get(MESH).getAsString() : "");
            Path templatePath = existingFile("--template", body.has(TEMPLATE) ? body.get(TEMPLATE).getAsString() : "");
            Path out = Path.of(body.has(OUT) ? body.get(OUT).getAsString() : "").toAbsolutePath().normalize();
            // Loaded as the viewer loads it, so the lines sit where the template's capture shows the mesh.
            NodeGraphRuntime graph = NodeGraphRuntime.fromSource("mesh = load_mesh(path=\"" + meshPath + "\")\n");
            MeshTopology mesh = ((GeometryBundle) graph.executeGraphResult(graph.lastId(), "geometry")).mesh();
            StrokeSketch template = StrokeSketch.parse(new String(Files.readAllBytes(templatePath), StandardCharsets.UTF_8),
                    templatePath.toString());
            SurfaceRidges ridges = new SurfaceRidges();
            ridges.levels = body.has(LEVELS) ? body.get(LEVELS).getAsInt() : ridges.levels;
            ridges.minimumStrength = body.has(STRENGTH) ? body.get(STRENGTH).getAsDouble() : ridges.minimumStrength;
            ridges.detect(mesh);
            FaceAnchors anchors = new FaceAnchors(mesh);
            StrokeAnchors drawn = new StrokeAnchors();
            int[] countByKind = new int[2];
            for (int line = 0; line < ridges.lines.size(); line++) {
                float[] points = ridges.lines.get(line);
                boolean valley = ridges.valleyByLine.get(line);
                AnchoredStroke stroke = new AnchoredStroke();
                stroke.layer = valley ? VALLEY_LAYER : RIDGE_LAYER;
                stroke.id = stroke.layer + StrokeAnchors.LAYER_ID_SEPARATOR + ++countByKind[valley ? 1 : 0];
                stroke.attributes = String.format(Locale.ROOT, "data-kind=\"%s\" data-depth=\"%.4f\" data-radius=\"%.5f\" "
                        + "fill=\"none\"", valley ? StrokeSketch.VALLEY : StrokeSketch.RIDGE, ridges.strengthByLine.get(line),
                        DRAWN_RADIUS);
                stroke.anchors = new float[points.length / 3 * FaceAnchors.FIELDS];
                for (int point = 0; point * 3 < points.length; point++) {
                    anchors.anchor(points[point * 3], points[point * 3 + 1], points[point * 3 + 2], stroke.anchors,
                            point * FaceAnchors.FIELDS);
                }
                drawn.strokes.add(stroke);
            }
            Files.createDirectories(out.getParent());
            Files.write(out, new SculptSync(template, mesh, mesh, mesh).draw(drawn).getBytes(StandardCharsets.UTF_8));
            JsonObject result = success();
            result.addProperty(RIDGE_LAYER, countByKind[0]);
            result.addProperty(VALLEY_LAYER, countByKind[1]);
            result.addProperty("levels_fitted", ridges.levelsFitted);
            JsonArray centres = new JsonArray();
            for (int count : ridges.centresByLevel) {
                centres.add(count);
            }
            result.add("centres_by_level", centres);
            result.addProperty("seconds_fit", ridges.seconds[0]);
            result.addProperty("seconds_curvature", ridges.seconds[1]);
            result.addProperty("seconds_trace", ridges.seconds[2]);
            result.addProperty("file", out.toString());
            return result;
        } catch (Exception failed) {
            return failure(failed.getMessage() == null ? failed.toString() : failed.getMessage());
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("surface-ridges")
                .description("A mesh's own ridge and valley lines after OBS04: fit a multi-level implicit surface "
                        + "(local quadratics blended, compactly supported RBFs pinning the zero set), project each "
                        + "vertex onto it for its principal curvatures and their derivatives, trace the zero "
                        + "crossings at maxima through the triangles and keep the strong lines; written into every "
                        + "cell of a multiview template that sees them, layers ridges and valleys.")
                .param(MESH, RouteParamType.STRING, true, "", "The mesh file, as load_mesh reads it.",
                        "~/.cache/ixdar-sculpt-judge/skull/skull57k.obj")
                .param(TEMPLATE, RouteParamType.STRING, true, "",
                        "A multiview SVG template of this mesh (multiview --svg) the lines are drawn through.",
                        "~/.cache/ixdar-sculpt-judge/skull/key-mesh91/goal_frame/goal_template.svg")
                .param(OUT, RouteParamType.STRING, true, "", "The SVG file written.",
                        "~/.cache/ixdar-sculpt-judge/skull/key-mesh91/goal_frame/ridges.svg")
                .param(LEVELS, RouteParamType.INT, false, "6",
                        "Most fit levels; fewer give a smoother surface and fewer, longer lines (OBS04 figure 1).", "5")
                .param(STRENGTH, RouteParamType.FLOAT, false, "2",
                        "Least curvature integral a kept line has, at a main diagonal of 20 sqrt 3 (OBS04's T).", "0.5")
                .responseHint("{ok, ridges, valleys, levels_fitted, centres_by_level, seconds_fit, seconds_curvature, "
                        + "seconds_trace, file} | {ok:false, error}")
                .waitSeconds(WAIT_SECONDS)
                .build();
    }
}
