package ixdar.automation.endpoints.mesh;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.joml.Vector3f;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.automation.endpoints.ui.MultiviewScreenshot;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.sculpt.StrokeChain;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.graphics.cameras.Camera3D;
import ixdar.scenes.mesh.MeshNodeViewerScene;

/**
 * Previews where a pass's SVG strokes land on the shown surface, lifted by the same CPU rays
 * through the SVG's stored camera that the {@code stroke_sculpt} node casts.
 */
@AutomationRouteAnnotation(path = "/mesh/stroke-lift", method = APIMethod.POST)
public class StrokeLift extends AutomationEndpoint implements AutomationRoute {

    public static final String SVG = "svg";

    public static final String SPACING = "spacing";

    public static final String STROKES = "strokes";

    public static final Pattern STROKE_WIDTH = Pattern.compile("\\bstroke-width\\s*[=:]\\s*\"?([0-9.]+)");

    /**
     * {@code POST /mesh/stroke-lift}: every shape's view, lifted points, chain, falloff radius and
     * stroke text, plus all strokes in the {@code stroke_sculpt} node's text form.
     *
     * @param body {@code svg} path, optional {@code spacing}
     * @return the lifted strokes, or a failure naming the missing input
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) {
        String path = body.has(SVG) ? body.get(SVG).getAsString() : "";
        File file = resolvePath(path);
        try {
            StrokeSketch sketch = StrokeSketch.read(file == null ? path : file.getPath());
            if (body.has(SPACING)) {
                sketch.spacing = body.get(SPACING).getAsFloat();
            }
            return runtime.runOnMainThread(() -> {
                if (!(runtime.canvas instanceof MeshNodeViewerScene viewer)) {
                    return failure("MeshNodeViewerScene is not active");
                }
                String refusal = viewer.pickRefusal();
                if (!refusal.isEmpty()) {
                    return failure(refusal);
                }
                MeshTopology surface = viewer.halfEdgeSurface();
                String strokes = sketch.lift(surface);
                StrokeChain chain = new StrokeChain();
                JsonArray strokeRows = new JsonArray();
                for (int shape = 0; shape < sketch.shapes.size(); shape++) {
                    JsonObject row = new JsonObject();
                    row.addProperty("index", shape);
                    int view = sketch.viewByShape[shape];
                    row.addProperty("view", view < 0 ? ""
                            : view < MultiviewScreenshot.VIEW_LABELS.length ? MultiviewScreenshot.VIEW_LABELS[view]
                            : String.valueOf(view));
                    row.addProperty("lifted", sketch.liftedByShape[shape]);
                    row.addProperty("kind", sketch.kindByShape[shape]);
                    row.addProperty("misses", sketch.missesByShape[shape]);
                    row.addProperty("outOfCell", sketch.outOfCellByShape[shape]);
                    row.addProperty("grazing", sketch.grazingByShape[shape]);
                    row.addProperty("radius", sketch.radiusByShape[shape]);
                    row.addProperty("depth", sketch.depthByShape[shape]);
                    float[] lifted = sketch.liftedXyzByShape[shape];
                    // The world radius the shape's stroke-width drew before MESH-80: its pixels times
                    // the world size of a pixel where its points land (a silhouette's, at the target).
                    Matcher width = STROKE_WIDTH.matcher(sketch.shapes.get(shape));
                    if (width.find() && view >= 0) {
                        Camera3D camera = sketch.cellCamera(view);
                        Vector3f target = camera.orbitTarget;
                        double worldPerPixel = lifted.length == 0
                                ? camera.worldPerPixel(target.x, target.y, target.z, sketch.cellHeight) : 0.0;
                        for (int point = 0; point < lifted.length; point += 3) {
                            worldPerPixel += camera.worldPerPixel(lifted[point], lifted[point + 1], lifted[point + 2],
                                    sketch.cellHeight) * 3.0 / lifted.length;
                        }
                        row.addProperty("strokeWidthRadius",
                                Float.parseFloat(width.group(1)) * sketch.svgScaleY * worldPerPixel);
                    }
                    if (lifted.length > 0 && !sketch.shapes.get(shape).startsWith("<" + StrokeSketch.POLYGON)) {
                        chain.snap(surface, lifted, lifted.length / 3);
                        JsonArray vertexIds = new JsonArray();
                        for (int vertexId : chain.vertexIds) {
                            vertexIds.add(vertexId);
                        }
                        row.add("vertexIds", vertexIds);
                        row.addProperty("unresolvedGaps", chain.unresolvedGaps);
                    }
                    row.addProperty("stroke", sketch.strokeByShape[shape]);
                    strokeRows.add(row);
                }
                JsonObject result = success();
                result.add("strokeRows", strokeRows);
                result.addProperty(STROKES, strokes);
                return result;
            });
        } catch (Exception failed) {
            return failure(failed.getMessage() == null ? failed.toString() : failed.getMessage());
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("stroke-lift")
                .description("Preview where a pass's SVG strokes land on the shown surface, lifted "
                        + "through the camera the SVG records exactly as the stroke_sculpt node lifts "
                        + "them; show the pass's input node (mesh-dsl --node) to preview that pass.")
                .param(SVG, RouteParamType.STRING, true, "",
                        "SVG drawn on an ixdar-cli multiview --svg template: <polyline> or <polygon> "
                                + "shapes with data-radius and data-depth in world units, data-kind "
                                + "silhouette/ridge/valley, data-mirror=\"x\", data-op=\"smooth\", "
                                + "data-profile=\"bowl\".",
                        "tmp/pass1.svg")
                .param(SPACING, RouteParamType.FLOAT, false, String.valueOf(StrokeSketch.DEFAULT_SPACING),
                        "World distance between kept polyline points and between polygon fill "
                                + "samples.", "0.03")
                .responseHint("{ok, strokeRows:[{index, view, lifted, kind, misses, outOfCell, grazing, radius, depth, "
                        + "strokeWidthRadius (world radius a stroke-width meant before MESH-80), "
                        + "vertexIds, unresolvedGaps, stroke}], strokes}")
                .build();
    }
}
