package ixdar.geometry.mesh.data.sculpt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.paths.query.TriangleBvh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.TriangulationRule;
import ixdar.graphics.cameras.Camera3D;

/**
 * One sculpt pass's drawing: weighted SVG strokes over a multiview grid plus the camera the grid
 * was captured with, which lifts the same pixels the same way onto any mesh, with no viewer.
 */
public final class StrokeSketch {

    public static final String CENTER_ATTRIBUTE = "data-center";

    public static final String DISTANCE_ATTRIBUTE = "data-distance";

    public static final String FIELD_OF_VIEW_ATTRIBUTE = "data-fov";

    public static final String PROJECTION_ATTRIBUTE = "data-projection";

    public static final String CELL_WIDTH_ATTRIBUTE = "data-cell-width";

    public static final String CELL_HEIGHT_ATTRIBUTE = "data-cell-height";

    public static final String COLUMNS_ATTRIBUTE = "data-columns";

    public static final String VIEWS_ATTRIBUTE = "data-views";

    public static final String ORTHOGRAPHIC = "orthographic";

    public static final String PERSPECTIVE = "perspective";

    public static final String VIEW_SEPARATOR = ";";

    public static final String RESOURCE_DIRECTORY = "src/main/resources/";

    public static final String POLYGON = "polygon";

    public static final String SILHOUETTE = "silhouette";

    public static final String RIDGE = "ridge";

    public static final String VALLEY = "valley";

    public static final String POINT_SEPARATOR = ", ";

    public static final String STROKE_SEPARATOR = "; ";

    public static final String NUMBER_SEPARATORS = "[\\s,]+";

    public static final float DEFAULT_SPACING = 0.03f;

    public static final float GRAZING_COSINE = 0.25f;

    public static final float FACE_ON_COSINE = 0.65f;

    public static final float MINIMUM_FILL_STEP_PIXELS = 3f;

    public static final float NEAR_PLANE_FRACTION = 0.01f;

    public static final float FAR_PLANE_MULTIPLE = 4f;

    public static final double MINIMUM_RAY_PARAMETER = 1e-6;

    public static final int VIEWBOX_FIELDS = 4;

    public static final int REQUIRED_ATTRIBUTES = 6;

    public static final Pattern ROOT_TAG = Pattern.compile("<svg\\b[^>]*>");

    public static final Pattern SHAPE_TAG = Pattern.compile("<(polyline|polygon)\\b[^>]*>");

    public static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    public static final Pattern POINTS_ATTRIBUTE = Pattern.compile("\\bpoints\\s*=\\s*\"([^\"]*)\"");

    public static final Pattern STRENGTH_ATTRIBUTE = Pattern.compile("\\bdata-strength\\s*=\\s*\"([^\"]*)\"");

    public static final Pattern MIRROR_ATTRIBUTE = Pattern.compile("\\bdata-mirror\\s*=\\s*\"x\"");

    public static final Pattern KIND_ATTRIBUTE = Pattern.compile("\\bdata-kind\\s*=\\s*\"([a-z]+)\"");

    public static final Pattern SMOOTH_ATTRIBUTE = Pattern.compile("\\bdata-op\\s*=\\s*\"smooth\"");

    public static final Pattern WIDTH_ATTRIBUTE = Pattern.compile("\\bstroke-width\\s*[=:]\\s*\"?([0-9.]+)");

    public static final Pattern VIEWBOX_ATTRIBUTE = Pattern.compile("\\bviewBox\\s*=\\s*\"([^\"]*)\"");

    /** Point the camera orbits, the multiview capture's centre. */
    public final float[] center = new float[3];

    /** Camera distance from {@link #center}. */
    public float distance;

    /** Vertical field of view in degrees. */
    public float fieldOfViewDegrees = Camera3D.DEFAULT_FOV;

    /** Whether the capture was orthographic. */
    public boolean orthographic;

    /** Width of one grid cell in pixels, the capture's framebuffer width. */
    public int cellWidth;

    /** Height of one grid cell in pixels. */
    public int cellHeight;

    /** Cells per grid row. */
    public int columns;

    /** Azimuth then elevation in radians of each cell's view, cells in reading order. */
    public float[][] viewAzimuthElevation = new float[0][];

    /** Grid pixels per SVG unit along x, from the viewBox. */
    public float svgScaleX = 1f;

    /** Grid pixels per SVG unit along y, from the viewBox. */
    public float svgScaleY = 1f;

    /** World distance between kept polyline points and between polygon fill samples. */
    public float spacing = DEFAULT_SPACING;

    /** Every polyline and polygon tag, in document order. */
    public List<String> shapes = new ArrayList<>();

    /** Each shape's stroke in {@link StrokeSculpt}'s text form after the last lift, empty when nothing lifted. */
    public String[] strokeByShape = new String[0];

    /** Grid cell each shape was drawn in, -1 for a shape with no points. */
    public int[] viewByShape = new int[0];

    /** Points (silhouettes: view rays) each shape lifted to. */
    public int[] liftedByShape = new int[0];

    /** Points of each shape whose ray met no surface. */
    public int[] missesByShape = new int[0];

    /** Points of each shape dropped as too grazing to the view. */
    public int[] grazingByShape = new int[0];

    /** Falloff radius each shape lifted to, world units. */
    public float[] radiusByShape = new float[0];

    /** Each shape's lifted surface points, packed xyz; silhouettes hold none. */
    public float[][] liftedXyzByShape = new float[0][];

    /**
     * Read a pass's SVG: the file at {@code path}, else under {@link #RESOURCE_DIRECTORY}, else the
     * classpath resource, so {@code dsl/skull/pass1.svg} works from either module root or a jar.
     *
     * @param path file path or resource path
     * @throws IOException              when no location holds the file
     * @throws IllegalArgumentException when the root carries no camera
     * @return the sketch with its camera and shapes
     */
    public static StrokeSketch read(String path) throws IOException {
        String svg = null;
        for (String candidate : new String[] { path, RESOURCE_DIRECTORY + path }) {
            Path file = MeshLoader.resolve(candidate);
            if (svg == null && Files.isRegularFile(file)) {
                svg = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            }
        }
        if (svg == null) {
            try (InputStream stream = StrokeSketch.class.getClassLoader().getResourceAsStream(path)) {
                if (stream == null) {
                    throw new IOException("no stroke SVG at " + path);
                }
                svg = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        Matcher root = ROOT_TAG.matcher(svg);
        String rootTag = root.find() ? root.group() : "";
        StrokeSketch sketch = new StrokeSketch();
        String[] attributes = { CENTER_ATTRIBUTE, DISTANCE_ATTRIBUTE, CELL_WIDTH_ATTRIBUTE,
            CELL_HEIGHT_ATTRIBUTE, COLUMNS_ATTRIBUTE, VIEWS_ATTRIBUTE, FIELD_OF_VIEW_ATTRIBUTE,
            PROJECTION_ATTRIBUTE };
        String[] values = new String[attributes.length];
        for (int attribute = 0; attribute < attributes.length; attribute++) {
            Matcher value = Pattern.compile("\\b" + attributes[attribute] + "\\s*=\\s*\"([^\"]*)\"").matcher(rootTag);
            values[attribute] = value.find() ? value.group(1).trim() : null;
            if (values[attribute] == null && attribute < REQUIRED_ATTRIBUTES) {
                throw new IllegalArgumentException(path + ": the <svg> root has no " + attributes[attribute]
                        + "; draw on a template written by ixdar-cli multiview --svg, which records the camera");
            }
        }
        String[] centerValues = values[0].split(NUMBER_SEPARATORS);
        for (int axis = 0; axis < 3; axis++) {
            sketch.center[axis] = Float.parseFloat(centerValues[axis]);
        }
        sketch.distance = Float.parseFloat(values[1]);
        sketch.cellWidth = Integer.parseInt(values[2]);
        sketch.cellHeight = Integer.parseInt(values[3]);
        sketch.columns = Integer.parseInt(values[4]);
        String[] views = values[5].split(VIEW_SEPARATOR);
        sketch.viewAzimuthElevation = new float[views.length][];
        for (int view = 0; view < views.length; view++) {
            String[] angles = views[view].trim().split(NUMBER_SEPARATORS);
            sketch.viewAzimuthElevation[view] = new float[] { Float.parseFloat(angles[0]),
                Float.parseFloat(angles[1]) };
        }
        sketch.fieldOfViewDegrees = values[6] == null ? Camera3D.DEFAULT_FOV : Float.parseFloat(values[6]);
        sketch.orthographic = ORTHOGRAPHIC.equals(values[7]);
        Matcher viewBox = VIEWBOX_ATTRIBUTE.matcher(rootTag);
        if (viewBox.find()) {
            String[] box = viewBox.group(1).trim().split(NUMBER_SEPARATORS);
            if (box.length == VIEWBOX_FIELDS) {
                int rows = (views.length + sketch.columns - 1) / sketch.columns;
                sketch.svgScaleX = sketch.columns * sketch.cellWidth / Float.parseFloat(box[2]);
                sketch.svgScaleY = rows * sketch.cellHeight / Float.parseFloat(box[3]);
            }
        }
        // A commented-out tag is not a stroke: the template's own instructions show one, and
        // commenting a stroke out is how a pass takes it back.
        Matcher tag = SHAPE_TAG.matcher(COMMENT.matcher(svg).replaceAll(""));
        while (tag.find()) {
            sketch.shapes.add(tag.group());
        }
        return sketch;
    }

    /**
     * Lift every shape onto a mesh by casting CPU rays through the stored camera: polylines to
     * points weighted by how face-on the surface is, polygons to fill samples, silhouettes to rays.
     *
     * @param mesh surface the rays are cast at
     * @return all lifted strokes in {@link StrokeSculpt}'s text form; per-shape results land in the
     *         {@code ...ByShape} fields
     */
    public String lift(MeshTopology mesh) {
        int shapeTotal = shapes.size();
        strokeByShape = new String[shapeTotal];
        viewByShape = new int[shapeTotal];
        liftedByShape = new int[shapeTotal];
        missesByShape = new int[shapeTotal];
        grazingByShape = new int[shapeTotal];
        radiusByShape = new float[shapeTotal];
        liftedXyzByShape = new float[shapeTotal][];
        TriangleBvh bvh = new TriangleBvh();
        int[] faceOfTriangle = new int[mesh.triangleCount()];
        bvh.triangleCorners = mesh.triangulate(TriangulationRule.FAN, faceOfTriangle);
        bvh.triangleCount = faceOfTriangle.length;
        bvh.positions = new float[mesh.vertexIdBound() * 3];
        Vector3f position = new Vector3f();
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            mesh.vertexPosition(vertexId, position);
            bvh.positions[vertexId * 3] = position.x;
            bvh.positions[vertexId * 3 + 1] = position.y;
            bvh.positions[vertexId * 3 + 2] = position.z;
        }
        bvh.build();
        int[] stack = new int[Math.max(1, bvh.nodeCount)];
        Camera3D camera = new Camera3D(new Vector3f(), 0f, 0f);
        camera.setDistanceBounds(0f, Float.MAX_VALUE);
        camera.fov = fieldOfViewDegrees;
        Vector3f target = new Vector3f(center[0], center[1], center[2]);
        Matrix4f model = new Matrix4f();
        Vector3f normal = new Vector3f();
        Vector3f toEye = new Vector3f();
        float[] origin = new float[3];
        float[] direction = new float[3];
        double[] inverse = new double[3];
        int rows = (viewAzimuthElevation.length + columns - 1) / Math.max(1, columns);
        StringBuilder allStrokes = new StringBuilder();
        for (int stroke = 0; stroke < shapeTotal; stroke++) {
            String shape = shapes.get(stroke);
            strokeByShape[stroke] = "";
            viewByShape[stroke] = -1;
            liftedXyzByShape[stroke] = new float[0];
            boolean fill = shape.startsWith("<" + POLYGON);
            Matcher points = POINTS_ATTRIBUTE.matcher(shape);
            Matcher strengthMatch = STRENGTH_ATTRIBUTE.matcher(shape);
            Matcher widthMatch = WIDTH_ATTRIBUTE.matcher(shape);
            Matcher kindMatch = KIND_ATTRIBUTE.matcher(shape);
            boolean mirror = MIRROR_ATTRIBUTE.matcher(shape).find();
            float strength = strengthMatch.find()
                    ? Math.max(-1f, Math.min(1f, Float.parseFloat(strengthMatch.group(1)))) : 1f;
            String kind = kindMatch.find() ? kindMatch.group(1) : "";
            strength = RIDGE.equals(kind) ? Math.abs(strength) : VALLEY.equals(kind) ? -Math.abs(strength) : strength;
            float strokeWidthPixels = widthMatch.find() ? Float.parseFloat(widthMatch.group(1)) * svgScaleY : 0f;
            String[] numbers = points.find() ? points.group(1).trim().split(NUMBER_SEPARATORS) : new String[0];
            float[] pixels = new float[numbers.length / 2 * 2];
            for (int index = 0; index < pixels.length; index++) {
                pixels[index] = Float.parseFloat(numbers[index]) * (index % 2 == 0 ? svgScaleX : svgScaleY);
            }
            if (pixels.length < 2) {
                continue;
            }
            int column = Math.min(columns - 1, Math.max(0, (int) (pixels[0] / cellWidth)));
            int gridRow = Math.min(rows - 1, Math.max(0, (int) (pixels[1] / cellHeight)));
            int view = Math.min(viewAzimuthElevation.length - 1, gridRow * columns + column);
            viewByShape[stroke] = view;
            camera.moveTarget(target);
            camera.setOrbit(viewAzimuthElevation[view][0], viewAzimuthElevation[view][1], distance);
            camera.updateProjection(orthographic, cellWidth / (float) cellHeight, distance * NEAR_PLANE_FRACTION,
                    distance * FAR_PLANE_MULTIPLE);
            for (int index = 0; index < pixels.length; index += 2) {
                pixels[index] -= column * cellWidth;
                pixels[index + 1] -= gridRow * cellHeight;
            }
            float centerWorldPerPixel = (float) camera.worldPerPixel(center[0], center[1], center[2], cellHeight);
            if (SILHOUETTE.equals(kind)) {
                // An over-sketched outline lifts to the view's eye, axis and one view ray per
                // point; the sculpt finds the contour against the stroke itself.
                Vector3f forward = new Vector3f(camera.target).sub(camera.position).normalize();
                float reach = strokeWidthPixels * centerWorldPerPixel;
                StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                        "%.2f r=%.4f silhouette eye=%.5f/%.5f/%.5f/%.5f/%.5f/%.5f%s:", Math.abs(strength), reach,
                        camera.position.x, camera.position.y, camera.position.z, forward.x, forward.y, forward.z,
                        mirror ? " " + StrokeSculpt.MIRROR_FLAG : ""));
                int rays = 0;
                for (int point = 0; point < pixels.length / 2; point++) {
                    if (camera.rayThroughPixel(model, pixels[point * 2], pixels[point * 2 + 1], cellWidth,
                            cellHeight, origin, direction)) {
                        Vector3f ray = new Vector3f(direction[0], direction[1], direction[2]).normalize();
                        text.append(rays++ == 0 ? " " : POINT_SEPARATOR)
                                .append(String.format(Locale.ROOT, "%.5f %.5f %.5f 1", ray.x, ray.y, ray.z));
                    }
                }
                liftedByShape[stroke] = rays;
                radiusByShape[stroke] = reach;
                strokeByShape[stroke] = rays > 0 ? text.toString() : "";
            } else {
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
                    float step = Math.max(MINIMUM_FILL_STEP_PIXELS, spacing / centerWorldPerPixel);
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
                float radiusSum = 0f;
                for (int point = 0; point < samples.length / 2; point++) {
                    float localX = samples[point * 2];
                    float localY = samples[point * 2 + 1];
                    if (localX < 0 || localY < 0 || localX >= cellWidth || localY >= cellHeight
                            || !camera.rayThroughPixel(model, localX, localY, cellWidth, cellHeight, origin,
                                    direction)) {
                        missesByShape[stroke]++;
                        continue;
                    }
                    // Nearest hit through the triangle tree (Moller and Trumbore 1997, "Fast,
                    // Minimum Storage Ray/Triangle Intersection", JGT 2(1)); boxes the ray misses,
                    // or enters past the best hit so far, are skipped.
                    int nearestTriangle = -1;
                    double nearestParameter = Double.POSITIVE_INFINITY;
                    for (int axis = 0; axis < 3; axis++) {
                        inverse[axis] = 1.0 / direction[axis];
                    }
                    int top = 0;
                    stack[top++] = 0;
                    while (top > 0 && bvh.nodeCount > 0) {
                        int node = stack[--top];
                        double enter = MINIMUM_RAY_PARAMETER;
                        double exit = nearestParameter;
                        for (int axis = 0; axis < 3 && enter <= exit; axis++) {
                            double low = (bvh.nodeMin[node * 3 + axis] - origin[axis]) * inverse[axis];
                            double high = (bvh.nodeMax[node * 3 + axis] - origin[axis]) * inverse[axis];
                            enter = Math.max(enter, Math.min(low, high));
                            exit = Math.min(exit, Math.max(low, high));
                        }
                        if (enter > exit) {
                            continue;
                        }
                        int left = bvh.nodeLeftChild[node];
                        if (left >= 0) {
                            stack[top++] = left;
                            stack[top++] = left + 1;
                            continue;
                        }
                        int end = bvh.nodeTriangleStart[node] + bvh.nodeTriangleCount[node];
                        for (int index = bvh.nodeTriangleStart[node]; index < end; index++) {
                            int triangle = bvh.triangleOrder[index];
                            int cornerA = bvh.triangleCorners[triangle * 3] * 3;
                            int cornerB = bvh.triangleCorners[triangle * 3 + 1] * 3;
                            int cornerC = bvh.triangleCorners[triangle * 3 + 2] * 3;
                            float[] xyz = bvh.positions;
                            double edgeOneX = xyz[cornerB] - (double) xyz[cornerA];
                            double edgeOneY = xyz[cornerB + 1] - (double) xyz[cornerA + 1];
                            double edgeOneZ = xyz[cornerB + 2] - (double) xyz[cornerA + 2];
                            double edgeTwoX = xyz[cornerC] - (double) xyz[cornerA];
                            double edgeTwoY = xyz[cornerC + 1] - (double) xyz[cornerA + 1];
                            double edgeTwoZ = xyz[cornerC + 2] - (double) xyz[cornerA + 2];
                            double crossX = direction[1] * edgeTwoZ - direction[2] * edgeTwoY;
                            double crossY = direction[2] * edgeTwoX - direction[0] * edgeTwoZ;
                            double crossZ = direction[0] * edgeTwoY - direction[1] * edgeTwoX;
                            double determinant = edgeOneX * crossX + edgeOneY * crossY + edgeOneZ * crossZ;
                            if (determinant == 0.0) {
                                continue;
                            }
                            double toOriginX = origin[0] - (double) xyz[cornerA];
                            double toOriginY = origin[1] - (double) xyz[cornerA + 1];
                            double toOriginZ = origin[2] - (double) xyz[cornerA + 2];
                            double first = (toOriginX * crossX + toOriginY * crossY + toOriginZ * crossZ) / determinant;
                            double turnX = toOriginY * edgeOneZ - toOriginZ * edgeOneY;
                            double turnY = toOriginZ * edgeOneX - toOriginX * edgeOneZ;
                            double turnZ = toOriginX * edgeOneY - toOriginY * edgeOneX;
                            double second = (direction[0] * turnX + direction[1] * turnY + direction[2] * turnZ)
                                    / determinant;
                            double parameter = (edgeTwoX * turnX + edgeTwoY * turnY + edgeTwoZ * turnZ) / determinant;
                            if (first >= 0.0 && second >= 0.0 && first + second <= 1.0
                                    && parameter >= MINIMUM_RAY_PARAMETER && parameter < nearestParameter) {
                                nearestParameter = parameter;
                                nearestTriangle = triangle;
                            }
                        }
                    }
                    if (nearestTriangle < 0) {
                        missesByShape[stroke]++;
                        continue;
                    }
                    float hitX = (float) (origin[0] + nearestParameter * direction[0]);
                    float hitY = (float) (origin[1] + nearestParameter * direction[1]);
                    float hitZ = (float) (origin[2] + nearestParameter * direction[2]);
                    mesh.faceNormal(faceOfTriangle[nearestTriangle], normal);
                    toEye.set(camera.position).sub(hitX, hitY, hitZ).normalize();
                    float facing = Math.abs(normal.normalize().dot(toEye));
                    if (facing < GRAZING_COSINE) {
                        grazingByShape[stroke]++;
                        continue;
                    }
                    if (!fill && liftedCount > 0 && Vector3f.distance(hitX, hitY, hitZ, lifted[liftedCount * 3 - 3],
                            lifted[liftedCount * 3 - 2], lifted[liftedCount * 3 - 1]) < spacing) {
                        continue;
                    }
                    lifted[liftedCount * 3] = hitX;
                    lifted[liftedCount * 3 + 1] = hitY;
                    lifted[liftedCount * 3 + 2] = hitZ;
                    weights[liftedCount] = Math.min(1f, (facing - GRAZING_COSINE) / (FACE_ON_COSINE - GRAZING_COSINE));
                    radiusSum += strokeWidthPixels * (float) camera.worldPerPixel(hitX, hitY, hitZ, cellHeight);
                    liftedCount++;
                }
                float radius = liftedCount == 0 ? 0f : radiusSum / liftedCount;
                liftedByShape[stroke] = liftedCount;
                radiusByShape[stroke] = radius;
                liftedXyzByShape[stroke] = Arrays.copyOf(lifted, liftedCount * 3);
                StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "%.2f", strength));
                if (radius > 0f) {
                    text.append(String.format(Locale.ROOT, " r=%.4f", radius));
                }
                text.append(fill ? " " + StrokeSculpt.FILL_FLAG : "").append(mirror ? " " + StrokeSculpt.MIRROR_FLAG : "")
                        .append(SMOOTH_ATTRIBUTE.matcher(shape).find() ? " " + StrokeSculpt.SMOOTH_FLAG : "")
                        .append(StrokeSculpt.HEADER_SEPARATOR);
                for (int point = 0; point < liftedCount; point++) {
                    text.append(point == 0 ? " " : POINT_SEPARATOR).append(String.format(Locale.ROOT,
                            "%.4f %.4f %.4f %.2f", lifted[point * 3], lifted[point * 3 + 1], lifted[point * 3 + 2],
                            weights[point]));
                }
                strokeByShape[stroke] = liftedCount > 0 ? text.toString() : "";
            }
            if (!strokeByShape[stroke].isEmpty()) {
                allStrokes.append(allStrokes.length() == 0 ? "" : STROKE_SEPARATOR).append(strokeByShape[stroke]);
            }
        }
        return allStrokes.toString();
    }
}
