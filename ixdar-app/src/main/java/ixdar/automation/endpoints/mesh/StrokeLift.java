package ixdar.automation.endpoints.mesh;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.joml.Vector3f;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.automation.endpoints.ui.MultiviewScreenshot;
import ixdar.geometry.mesh.data.paths.query.SurfacePicker;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.sculpt.StrokeChain;
import ixdar.graphics.cameras.Camera3D;
import ixdar.platform.Platforms;
import ixdar.scenes.mesh.MeshNodeViewerScene;

/**
 * Lifts weighted SVG strokes drawn over a {@code multiview} grid onto the shown surface, each
 * point weighted by how face-on the surface is: polylines become edge chains, polygons filled
 * regions.
 */
@AutomationRouteAnnotation(path = "/mesh/stroke-lift", method = APIMethod.POST)
public class StrokeLift extends AutomationEndpoint implements AutomationRoute {

    public static final String SVG = "svg";

    public static final String MULTIVIEW = "multiview";

    public static final String SPACING = "spacing";

    public static final String CELL_WIDTH = "cellWidth";

    public static final String CELL_HEIGHT = "cellHeight";

    public static final String STROKES = "strokes";

    public static final String POLYGON = "polygon";

    public static final float DEFAULT_SPACING = 0.03f;

    public static final float GRAZING_COSINE = 0.25f;

    public static final float FACE_ON_COSINE = 0.65f;

    public static final float MINIMUM_FILL_STEP_PIXELS = 3f;

    public static final int VIEWBOX_FIELDS = 4;

    public static final Pattern SHAPE_TAG = Pattern.compile("<(polyline|polygon)\\b[^>]*>");

    public static final Pattern POINTS_ATTRIBUTE = Pattern.compile("\\bpoints\\s*=\\s*\"([^\"]*)\"");

    public static final Pattern STRENGTH_ATTRIBUTE = Pattern.compile(
            "\\bdata-strength\\s*=\\s*\"([^\"]*)\"");

    public static final Pattern MIRROR_ATTRIBUTE = Pattern.compile("\\bdata-mirror\\s*=\\s*\"x\"");

    public static final Pattern KIND_ATTRIBUTE = Pattern.compile("\\bdata-kind\\s*=\\s*\"([a-z]+)\"");

    public static final String SILHOUETTE = "silhouette";

    public static final String RADIUS_FIELD = "radius";

    public static final String LIFTED_FIELD = "lifted";

    public static final String STROKE_FIELD = "stroke";

    public static final String MIRROR_FLAG = " mirror";

    public static final String POINT_SEPARATOR = ", ";

    public static final String STROKE_SEPARATOR = "; ";

    public static final String RIDGE = "ridge";

    public static final String VALLEY = "valley";

    public static final Pattern SMOOTH_ATTRIBUTE = Pattern.compile("\\bdata-op\\s*=\\s*\"smooth\"");

    public static final Pattern WIDTH_ATTRIBUTE = Pattern.compile(
            "\\bstroke-width\\s*[=:]\\s*\"?([0-9.]+)");

    public static final Pattern VIEWBOX_ATTRIBUTE = Pattern.compile("\\bviewBox\\s*=\\s*\"([^\"]*)\"");

    public static final String NUMBER_SEPARATORS = "[\\s,]+";

    /**
     * {@code POST /mesh/stroke-lift}: every shape's lifted points, chain, strength and falloff
     * radius, plus all strokes in the {@code stroke_sculpt} node's text form.
     *
     * @param body {@code svg} and {@code multiview} paths, optional {@code spacing}
     * @return the lifted strokes, or a failure naming the missing input
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) {
        File svgFile = resolvePath(body.has(SVG) ? body.get(SVG).getAsString() : "");
        File multiviewFile = resolvePath(body.has(MULTIVIEW) ? body.get(MULTIVIEW).getAsString() : "");
        if (svgFile == null || multiviewFile == null) {
            return failure("both svg and multiview must name existing files");
        }
        float spacing = body.has(SPACING) ? body.get(SPACING).getAsFloat() : DEFAULT_SPACING;
        try {
            String svg = new String(Files.readAllBytes(svgFile.toPath()), StandardCharsets.UTF_8);
            JsonObject multiview = JsonParser.parseString(new String(
                    Files.readAllBytes(multiviewFile.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray centerRow = multiview.getAsJsonArray("center");
            Vector3f center = new Vector3f(centerRow.get(0).getAsFloat(), centerRow.get(1).getAsFloat(),
                    centerRow.get(2).getAsFloat());
            float distance = multiview.get("distance").getAsFloat();
            int cellWidth = multiview.get(CELL_WIDTH).getAsInt();
            int cellHeight = multiview.get(CELL_HEIGHT).getAsInt();
            int columns = MultiviewScreenshot.GRID_COLUMNS;
            float svgScaleX = 1f;
            float svgScaleY = 1f;
            Matcher viewBox = VIEWBOX_ATTRIBUTE.matcher(svg);
            if (viewBox.find()) {
                String[] box = viewBox.group(1).trim().split(NUMBER_SEPARATORS);
                if (box.length == VIEWBOX_FIELDS) {
                    svgScaleX = columns * cellWidth / Float.parseFloat(box[2]);
                    svgScaleY = 2 * cellHeight / Float.parseFloat(box[3]);
                }
            }
            List<String> shapeTags = new ArrayList<>();
            Matcher tag = SHAPE_TAG.matcher(svg);
            while (tag.find()) {
                shapeTags.add(tag.group());
            }
            final float pointSpacing = spacing;
            final float gridScaleX = svgScaleX;
            final float gridScaleY = svgScaleY;
            return runtime.runOnMainThread(() -> {
                if (!(runtime.canvas instanceof MeshNodeViewerScene viewer)) {
                    return failure("MeshNodeViewerScene is not active");
                }
                String refusal = viewer.pickRefusal();
                if (!refusal.isEmpty()) {
                    return failure(refusal);
                }
                Camera3D camera = viewer.camera;
                float savedAzimuth = camera.azimuth;
                float savedElevation = camera.elevation;
                float savedDistance = camera.distance;
                Vector3f savedTarget = new Vector3f(camera.orbitTarget);
                float framebufferScaleX = Platforms.get().getFrameBufferWidth() / (float) cellWidth;
                float framebufferScaleY = Platforms.get().getFrameBufferHeight() / (float) cellHeight;
                MeshTopology surface = viewer.halfEdgeSurface();
                SurfacePicker picker = new SurfacePicker();
                Vector3f normal = new Vector3f();
                Vector3f toEye = new Vector3f();
                StringBuilder allStrokes = new StringBuilder();
                JsonArray strokeRows = new JsonArray();
                for (int stroke = 0; stroke < shapeTags.size(); stroke++) {
                    String shape = shapeTags.get(stroke);
                    boolean fill = shape.startsWith("<" + POLYGON);
                    Matcher points = POINTS_ATTRIBUTE.matcher(shape);
                    Matcher strengthMatch = STRENGTH_ATTRIBUTE.matcher(shape);
                    Matcher widthMatch = WIDTH_ATTRIBUTE.matcher(shape);
                    boolean mirror = MIRROR_ATTRIBUTE.matcher(shape).find();
                    float strength = strengthMatch.find() ? Math.max(-1f, Math.min(1f,
                            Float.parseFloat(strengthMatch.group(1)))) : 1f;
                    Matcher kindMatch = KIND_ATTRIBUTE.matcher(shape);
                    String kind = kindMatch.find() ? kindMatch.group(1) : "";
                    strength = RIDGE.equals(kind) ? Math.abs(strength)
                            : VALLEY.equals(kind) ? -Math.abs(strength) : strength;
                    float strokeWidthPixels = widthMatch.find()
                            ? Float.parseFloat(widthMatch.group(1)) * gridScaleY : 0f;
                    String[] numbers = points.find() ? points.group(1).trim().split(NUMBER_SEPARATORS)
                            : new String[0];
                    float[] pixels = new float[numbers.length / 2 * 2];
                    for (int index = 0; index < pixels.length; index++) {
                        pixels[index] = Float.parseFloat(numbers[index])
                                * (index % 2 == 0 ? gridScaleX : gridScaleY);
                    }
                    JsonObject row = new JsonObject();
                    row.addProperty("index", stroke);
                    row.addProperty("strength", strength);
                    row.addProperty("fill", fill);
                    row.addProperty("mirror", mirror);
                    row.addProperty("points", pixels.length / 2);
                    if (pixels.length < 2) {
                        strokeRows.add(row);
                        continue;
                    }
                    int column = Math.min(columns - 1, Math.max(0, (int) (pixels[0] / cellWidth)));
                    int gridRow = Math.min(1, Math.max(0, (int) (pixels[1] / cellHeight)));
                    int view = gridRow * columns + column;
                    row.addProperty("view", MultiviewScreenshot.VIEW_LABELS[view]);
                    camera.moveTarget(center);
                    camera.setOrbit(MultiviewScreenshot.VIEW_AZIMUTH_ELEVATION_RADIANS[view][0],
                            MultiviewScreenshot.VIEW_AZIMUTH_ELEVATION_RADIANS[view][1], distance);
                    for (int index = 0; index < pixels.length; index += 2) {
                        pixels[index] -= column * cellWidth;
                        pixels[index + 1] -= gridRow * cellHeight;
                    }
                    if (SILHOUETTE.equals(kind)) {
                        // An over-sketched outline lifts to the view's eye, axis and one view ray
                        // per point; the sculpt finds the contour against the stroke itself.
                        float[] origin = new float[3];
                        float[] direction = new float[3];
                        Vector3f forward = new Vector3f(camera.target).sub(camera.position).normalize();
                        float reach = strokeWidthPixels * framebufferScaleY
                                * (float) camera.worldPerPixel(center.x, center.y, center.z);
                        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                                "%.2f r=%.4f silhouette eye=%.5f/%.5f/%.5f/%.5f/%.5f/%.5f%s:",
                                Math.abs(strength), reach, camera.position.x, camera.position.y,
                                camera.position.z, forward.x, forward.y, forward.z,
                                mirror ? MIRROR_FLAG : ""));
                        int rays = 0;
                        for (int point = 0; point < pixels.length / 2; point++) {
                            if (viewer.surfaceRuntime().rayThroughPixel(camera,
                                    pixels[point * 2] * framebufferScaleX,
                                    pixels[point * 2 + 1] * framebufferScaleY, origin, direction)) {
                                Vector3f ray = new Vector3f(direction[0], direction[1], direction[2])
                                        .normalize();
                                text.append(rays++ == 0 ? " " : POINT_SEPARATOR).append(String.format(Locale.ROOT,
                                        "%.5f %.5f %.5f 1", ray.x, ray.y, ray.z));
                            }
                        }
                        row.addProperty("kind", kind);
                        row.addProperty(RADIUS_FIELD, reach);
                        row.addProperty(LIFTED_FIELD, rays);
                        row.addProperty(STROKE_FIELD, text.toString());
                        strokeRows.add(row);
                        allStrokes.append(allStrokes.length() == 0 ? "" : STROKE_SEPARATOR).append(text);
                        continue;
                    }
                    float[] samples = pixels;
                    if (fill) {
                        float minimumX = Float.POSITIVE_INFINITY;
                        float minimumY = Float.POSITIVE_INFINITY;
                        float maximumX = Float.NEGATIVE_INFINITY;
                        float maximumY = Float.NEGATIVE_INFINITY;
                        for (int index = 0; index < pixels.length; index += 2) {
                            minimumX = Math.min(minimumX, pixels[index]);
                            maximumX = Math.max(maximumX, pixels[index]);
                            minimumY = Math.min(minimumY, pixels[index + 1]);
                            maximumY = Math.max(maximumY, pixels[index + 1]);
                        }
                        float step = Math.max(MINIMUM_FILL_STEP_PIXELS, pointSpacing
                                / (float) camera.worldPerPixel(center.x, center.y, center.z)
                                / framebufferScaleY);
                        List<Float> inside = new ArrayList<>();
                        for (float sampleY = minimumY + step / 2; sampleY < maximumY; sampleY += step) {
                            for (float sampleX = minimumX + step / 2; sampleX < maximumX; sampleX += step) {
                                boolean contained = false;
                                for (int index = 0, previous = pixels.length - 2; index < pixels.length;
                                        previous = index, index += 2) {
                                    if ((pixels[index + 1] > sampleY) != (pixels[previous + 1] > sampleY)
                                            && sampleX < pixels[index] + (sampleY - pixels[index + 1])
                                            * (pixels[previous] - pixels[index])
                                            / (pixels[previous + 1] - pixels[index + 1])) {
                                        contained = !contained;
                                    }
                                }
                                if (contained) {
                                    inside.add(sampleX);
                                    inside.add(sampleY);
                                }
                            }
                        }
                        samples = new float[inside.size()];
                        for (int index = 0; index < samples.length; index++) {
                            samples[index] = inside.get(index);
                        }
                    }
                    float[] lifted = new float[samples.length / 2 * 3];
                    float[] weights = new float[samples.length / 2];
                    int liftedCount = 0;
                    int misses = 0;
                    int grazing = 0;
                    float radiusSum = 0f;
                    for (int point = 0; point < samples.length / 2; point++) {
                        float localX = samples[point * 2];
                        float localY = samples[point * 2 + 1];
                        if (localX < 0 || localY < 0 || localX >= cellWidth || localY >= cellHeight
                                || !viewer.pickPixel(localX * framebufferScaleX,
                                        localY * framebufferScaleY, picker)) {
                            misses++;
                            continue;
                        }
                        surface.faceNormal(picker.faceId, normal);
                        toEye.set(camera.position).sub(picker.pointX, picker.pointY, picker.pointZ)
                                .normalize();
                        float facing = Math.abs(normal.normalize().dot(toEye));
                        if (facing < GRAZING_COSINE) {
                            grazing++;
                            continue;
                        }
                        if (!fill && liftedCount > 0 && Vector3f.distance(picker.pointX, picker.pointY,
                                picker.pointZ, lifted[liftedCount * 3 - 3], lifted[liftedCount * 3 - 2],
                                lifted[liftedCount * 3 - 1]) < pointSpacing) {
                            continue;
                        }
                        lifted[liftedCount * 3] = picker.pointX;
                        lifted[liftedCount * 3 + 1] = picker.pointY;
                        lifted[liftedCount * 3 + 2] = picker.pointZ;
                        weights[liftedCount] = Math.min(1f,
                                (facing - GRAZING_COSINE) / (FACE_ON_COSINE - GRAZING_COSINE));
                        radiusSum += strokeWidthPixels * framebufferScaleY
                                * (float) camera.worldPerPixel(picker.pointX, picker.pointY, picker.pointZ);
                        liftedCount++;
                    }
                    float radius = liftedCount == 0 ? 0f : radiusSum / liftedCount;
                    row.addProperty(LIFTED_FIELD, liftedCount);
                    row.addProperty("misses", misses);
                    row.addProperty("grazing", grazing);
                    row.addProperty(RADIUS_FIELD, radius);
                    if (!fill) {
                        StrokeChain chain = new StrokeChain();
                        chain.snap(surface, lifted, liftedCount);
                        JsonArray vertexIds = new JsonArray();
                        for (int vertexId : chain.vertexIds) {
                            vertexIds.add(vertexId);
                        }
                        JsonArray edgeIds = new JsonArray();
                        for (int edgeId : chain.edgeIds) {
                            edgeIds.add(edgeId);
                        }
                        row.add("vertexIds", vertexIds);
                        row.add("edgeIds", edgeIds);
                        row.addProperty("unresolvedGaps", chain.unresolvedGaps);
                    }
                    StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "%.2f", strength));
                    if (radius > 0f) {
                        text.append(String.format(Locale.ROOT, " r=%.4f", radius));
                    }
                    text.append(fill ? " fill" : "").append(mirror ? MIRROR_FLAG : "")
                            .append(SMOOTH_ATTRIBUTE.matcher(shape).find() ? " smooth" : "").append(':');
                    for (int point = 0; point < liftedCount; point++) {
                        text.append(point == 0 ? " " : POINT_SEPARATOR).append(String.format(Locale.ROOT,
                                "%.4f %.4f %.4f %.2f", lifted[point * 3], lifted[point * 3 + 1],
                                lifted[point * 3 + 2], weights[point]));
                    }
                    row.addProperty(STROKE_FIELD, text.toString());
                    strokeRows.add(row);
                    if (liftedCount > 0) {
                        allStrokes.append(allStrokes.length() == 0 ? "" : STROKE_SEPARATOR).append(text);
                    }
                }
                camera.moveTarget(savedTarget);
                camera.setOrbit(savedAzimuth, savedElevation, savedDistance);
                JsonObject result = success();
                result.add("strokeRows", strokeRows);
                result.addProperty(STROKES, allStrokes.toString());
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
                .description("Lift weighted SVG strokes drawn over a multiview grid onto the shown "
                        + "surface, polylines as edge chains and polygons as filled regions, "
                        + "printing them as the stroke_sculpt node reads them.")
                .param(SVG, RouteParamType.STRING, true, "",
                        "SVG of <polyline> or <polygon> shapes with data-strength in [-1, 1], "
                                + "stroke-width as the falloff width, data-mirror=\"x\" for "
                                + "symmetry, data-op=\"smooth\" to relax instead of displace and "
                                + "data-kind=\"silhouette\" (drag the view's outline onto the line), "
                                + "\"ridge\" or \"valley\"; a "
                                + "viewBox rescales it to the grid's pixels.",
                        "tmp/round1.svg")
                .param(MULTIVIEW, RouteParamType.STRING, true, "",
                        "The multiview response JSON the grid was captured with (center, distance, "
                                + "cellWidth, cellHeight).", "tmp/round1.json")
                .param(SPACING, RouteParamType.FLOAT, false, String.valueOf(DEFAULT_SPACING),
                        "World distance between kept polyline points and between polygon fill "
                                + "samples.", "0.03")
                .responseHint("{ok, strokeRows:[{index, strength, fill, mirror, points, view, "
                        + "lifted, misses, grazing, radius, vertexIds, edgeIds, unresolvedGaps, "
                        + "stroke}], strokes}")
                .build();
    }
}
