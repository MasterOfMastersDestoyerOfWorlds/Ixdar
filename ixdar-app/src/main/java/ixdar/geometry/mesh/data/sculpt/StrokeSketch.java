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
import org.joml.Vector4f;

import ixdar.geometry.grid2d.PolygonScan;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.paths.query.TriangleBvh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
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

    public static final String SVG_TEXT_START = "<";

    public static final String POLYGON = "polygon";

    public static final String POLYLINE = "polyline";

    public static final String SILHOUETTE = "silhouette";

    public static final String RIDGE = "ridge";

    public static final String VALLEY = "valley";

    public static final String FILL = "fill";

    public static final int VIEW_AZIMUTH = 0;

    public static final int VIEW_ELEVATION = 1;

    public static final int VIEW_DISTANCE = 2;

    public static final int VIEW_ROLL = 3;

    public static final int VIEW_FIELD_OF_VIEW = 4;

    public static final int VIEW_CENTER = 5;

    public static final int VIEW_FIELDS = 8;

    public static final String POINT_SEPARATOR = ", ";

    public static final String STROKE_SEPARATOR = "; ";

    public static final String NUMBER_SEPARATORS = "[\\s,]+";

    public static final float DEFAULT_SPACING = 0.03f;

    public static final float GRAZING_COSINE = 0.25f;

    public static final float MINIMUM_FILL_STEP_PIXELS = 3f;

    public static final float NEAR_PLANE_FRACTION = 0.01f;

    public static final float FAR_PLANE_MULTIPLE = 4f;

    public static final int VIEWBOX_FIELDS = 4;

    public static final int REQUIRED_ATTRIBUTES = 6;

    public static final Pattern ROOT_TAG = Pattern.compile("<svg\\b[^>]*>");

    public static final Pattern SHAPE_TAG = Pattern.compile("<(polyline|polygon)\\b[^>]*>");

    public static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    public static final Pattern POINTS_ATTRIBUTE = Pattern.compile("\\bpoints\\s*=\\s*\"([^\"]*)\"");

    public static final Pattern LEGACY_STRENGTH_ATTRIBUTE = Pattern.compile("\\bdata-strength\\s*=");

    public static final Pattern DEPTH_ATTRIBUTE = Pattern.compile("\\bdata-depth\\s*=\\s*\"([^\"]*)\"");

    public static final Pattern RADIUS_ATTRIBUTE = Pattern.compile("\\bdata-radius\\s*=\\s*\"([^\"]*)\"");

    public static final Pattern BOWL_ATTRIBUTE = Pattern.compile("\\bdata-profile\\s*=\\s*\"bowl\"");

    public static final String MIGRATION_HINT = "; since MESH-80 a stroke's size is data-radius and its strength "
            + "data-depth, both in world units: run ixdar-cli sculpt-migrate --dsl <the layer's DSL> to convert it";

    public static final Pattern MIRROR_ATTRIBUTE = Pattern.compile("\\bdata-mirror\\s*=\\s*\"x\"");

    public static final Pattern KIND_ATTRIBUTE = Pattern.compile("\\bdata-kind\\s*=\\s*\"([a-z]+)\"");

    public static final Pattern SMOOTH_ATTRIBUTE = Pattern.compile("\\bdata-op\\s*=\\s*\"smooth\"");

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

    /**
     * Each cell's camera, cells in reading order, indexed by the {@code VIEW_} constants: azimuth
     * and elevation in radians, distance, roll, field of view in degrees, then the centre. A
     * multiview cell names only its angles in {@code data-views} and takes the rest from the root.
     */
    public float[][] viewCameras = new float[0][];

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

    /** Points (fill samples) of each shape whose ray met no surface. */
    public int[] missesByShape = new int[0];

    /** Points of each shape lying outside the cell its first point is in. */
    public int[] outOfCellByShape = new int[0];

    /** Points of each shape dropped as too grazing to the view. */
    public int[] grazingByShape = new int[0];

    /** Each shape's data-radius, its falloff radius (silhouettes: reach) in world units. */
    public float[] radiusByShape = new float[0];

    /** Each shape's data-depth, its signed displacement in world units; 0 for a silhouette. */
    public float[] depthByShape = new float[0];

    /** Each shape's kind: silhouette, fill, smooth, or the sign's ridge or valley. */
    public String[] kindByShape = new String[0];

    /** Each shape's lifted surface points, packed xyz; silhouettes hold none. */
    public float[][] liftedXyzByShape = new float[0][];

    /**
     * Each chain's points on the base its sidecar holds them at, packed xyz, which {@link #lift}
     * takes instead of casting its pixels; null for a shape lifted from its pixels.
     */
    public float[][] anchoredXyzByShape = new float[0][];

    /** Each shape's stable stroke id from its sidecar, null for a shape no sync has seen. */
    public String[] idByShape = new String[0];

    /** The SVG text the sketch was parsed from, empty for one built stroke by stroke. */
    public String text = "";

    /**
     * Read a pass's SVG: the file {@link MeshLoader#resolve} finds, as load_mesh finds its mesh, else
     * the classpath resource, so {@code dsl/skull/pass1.svg} works from either module root or a jar.
     *
     * @param path file path or resource path
     * @throws IOException              when no location holds the file
     * @throws IllegalArgumentException when the root carries no camera
     * @return the sketch with its camera and shapes
     */
    public static StrokeSketch read(String path) throws IOException {
        String svg = null;
        Path file = MeshLoader.resolve(path);
        if (Files.isRegularFile(file)) {
            svg = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        }
        if (svg == null) {
            try (InputStream stream = StrokeSketch.class.getClassLoader().getResourceAsStream(path)) {
                if (stream == null) {
                    throw new IOException("no stroke SVG at " + path);
                }
                svg = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return parse(svg, path);
    }

    /**
     * Parse a pass's SVG text: the camera its root records and its shapes, as {@link #read} does
     * for a file.
     *
     * @param svg    the SVG text
     * @param source where the text came from, for the refusal
     * @throws IllegalArgumentException when the root carries no camera
     * @return the sketch with its camera and shapes
     */
    public static StrokeSketch parse(String svg, String source) {
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
                throw new IllegalArgumentException(source + ": the <svg> root has no " + attributes[attribute]
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
        sketch.fieldOfViewDegrees = values[6] == null ? Camera3D.DEFAULT_FOV : Float.parseFloat(values[6]);
        sketch.orthographic = ORTHOGRAPHIC.equals(values[7]);
        String[] views = values[5].split(VIEW_SEPARATOR);
        sketch.viewCameras = new float[views.length][];
        for (int view = 0; view < views.length; view++) {
            String[] numbers = views[view].trim().split(NUMBER_SEPARATORS);
            float[] camera = { 0f, 0f, sketch.distance, 0f, sketch.fieldOfViewDegrees, sketch.center[0],
                sketch.center[1], sketch.center[2] };
            for (int field = 0; field < Math.min(numbers.length, VIEW_FIELDS); field++) {
                camera[field] = Float.parseFloat(numbers[field]);
            }
            sketch.viewCameras[view] = camera;
        }
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
        sketch.text = svg;
        return sketch;
    }

    /**
     * Where a world point lands in a cell: its pixel from the cell's top left, as
     * {@link Camera3D#rayThroughPixel} counts them, so a ray through that pixel passes the point.
     *
     * @param camera the cell's camera, {@link #cellCamera}
     * @param point  the world point
     * @param dest   receives x and y
     * @return false when the point lies behind the camera
     */
    public boolean cellPixel(Camera3D camera, Vector3f point, float[] dest) {
        Vector4f clip = new Vector4f(point, 1f);
        camera.view.transform(clip);
        camera.projection.transform(clip);
        if (clip.w <= 0f) {
            return false;
        }
        dest[0] = (clip.x / clip.w + 1f) * cellWidth * 0.5f - 0.5f;
        dest[1] = (1f - clip.y / clip.w) * cellHeight * 0.5f - 0.5f;
        return true;
    }

    /**
     * A shape's points attribute for points in one cell's pixels: grid pixels in SVG units.
     *
     * @param cell   the cell, in reading order
     * @param pixels packed xy from the cell's top left
     * @param count  how many points to write
     * @return {@code "x,y x,y ..."} at one decimal
     */
    public String gridPoints(int cell, float[] pixels, int count) {
        StringBuilder points = new StringBuilder();
        for (int point = 0; point < count; point++) {
            points.append(point == 0 ? "" : " ").append(String.format(Locale.ROOT, "%.1f,%.1f",
                    (pixels[point * 2] + cell % columns * cellWidth) / svgScaleX,
                    (pixels[point * 2 + 1] + cell / columns * cellHeight) / svgScaleY));
        }
        return points.toString();
    }

    /**
     * A shape's points in its cell's pixels, the inverse of {@link #gridPoints}.
     *
     * @param shape the shape's tag
     * @param cell  the cell its points are measured from
     * @return packed xy from the cell's top left
     */
    public float[] cellPixels(String shape, int cell) {
        float[] pixels = gridPixels(shape);
        for (int index = 0; index < pixels.length; index += 2) {
            pixels[index] -= cell % columns * cellWidth;
            pixels[index + 1] -= cell / columns * cellHeight;
        }
        return pixels;
    }

    /**
     * The cell a shape's first point is in.
     *
     * @param shape the shape's tag
     * @return the cell in reading order, -1 for a shape with no points
     */
    public int shapeCell(String shape) {
        float[] pixels = gridPixels(shape);
        return pixels.length < 2 ? -1 : cellAt(pixels[0], pixels[1]);
    }

    /**
     * A node's svg input as a sketch: the SVG text itself when it starts with {@code <}, else a path
     * {@link #read} resolves.
     *
     * @param source SVG text or path
     * @param reader the reading node's name, for the refusal
     * @throws IllegalStateException    when no location holds the file
     * @throws IllegalArgumentException when the root carries no camera
     * @return the sketch
     */
    public static StrokeSketch load(String source, String reader) {
        try {
            return source.startsWith(SVG_TEXT_START) ? parse(source, reader) : read(source);
        } catch (IOException failure) {
            throw new IllegalStateException(reader + " cannot read " + source + ": " + failure.getMessage(), failure);
        }
    }

    /**
     * Each cell's closed outline in that cell's pixels: the cell's shapes joined in drawing order,
     * repeated points and the closing point dropped.
     *
     * @return packed xy per cell in reading order, empty for a cell holding no shape
     */
    public float[][] cellOutlines() {
        float[][] cells = new float[viewCameras.length][0];
        for (String shape : shapes) {
            float[] pixels = gridPixels(shape);
            if (pixels.length < 2) {
                continue;
            }
            int cell = cellAt(pixels[0], pixels[1]);
            float[] outline = Arrays.copyOf(cells[cell], cells[cell].length + pixels.length);
            int size = cells[cell].length / 2;
            for (int point = 0; point < pixels.length / 2; point++) {
                float x = pixels[point * 2] - cell % columns * cellWidth;
                float y = pixels[point * 2 + 1] - cell / columns * cellHeight;
                if (size == 0 || x != outline[size * 2 - 2] || y != outline[size * 2 - 1]) {
                    outline[size * 2] = x;
                    outline[size * 2 + 1] = y;
                    size++;
                }
            }
            boolean closed = size > 1 && outline[0] == outline[size * 2 - 2] && outline[1] == outline[size * 2 - 1];
            cells[cell] = Arrays.copyOf(outline, (closed ? size - 1 : size) * 2);
        }
        return cells;
    }

    /**
     * The camera a cell was captured with, its projection set for one cell.
     *
     * @param cell the cell, in reading order
     * @return the cell's orbit camera
     */
    public Camera3D cellCamera(int cell) {
        float[] viewCamera = viewCameras[cell];
        float viewDistance = viewCamera[VIEW_DISTANCE];
        Camera3D camera = new Camera3D(new Vector3f(), 0f, 0f);
        camera.setDistanceBounds(0f, Float.MAX_VALUE);
        camera.fov = viewCamera[VIEW_FIELD_OF_VIEW];
        camera.roll = viewCamera[VIEW_ROLL];
        camera.moveTarget(new Vector3f(viewCamera[VIEW_CENTER], viewCamera[VIEW_CENTER + 1], viewCamera[VIEW_CENTER + 2]));
        camera.setOrbit(viewCamera[VIEW_AZIMUTH], viewCamera[VIEW_ELEVATION], viewDistance);
        camera.updateProjection(orthographic, cellWidth / (float) cellHeight, viewDistance * NEAR_PLANE_FRACTION,
                viewDistance * FAR_PLANE_MULTIPLE);
        return camera;
    }

    /**
     * A shape's points in grid pixels, its SVG units scaled by the viewBox.
     *
     * @param shape the shape's tag
     * @return packed xy, empty when the tag has no points
     */
    private float[] gridPixels(String shape) {
        Matcher points = POINTS_ATTRIBUTE.matcher(shape);
        String[] numbers = points.find() ? points.group(1).trim().split(NUMBER_SEPARATORS) : new String[0];
        float[] pixels = new float[numbers.length / 2 * 2];
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = Float.parseFloat(numbers[index]) * (index % 2 == 0 ? svgScaleX : svgScaleY);
        }
        return pixels;
    }

    /**
     * The cell a grid pixel falls in, clamped to the grid.
     *
     * @param gridX grid pixel x from the left
     * @param gridY grid pixel y from the top
     * @return the cell in reading order
     */
    private int cellAt(float gridX, float gridY) {
        int gridColumns = Math.max(1, columns);
        int rows = (viewCameras.length + gridColumns - 1) / gridColumns;
        int column = Math.min(gridColumns - 1, Math.max(0, (int) (gridX / cellWidth)));
        int gridRow = Math.min(rows - 1, Math.max(0, (int) (gridY / cellHeight)));
        return Math.min(viewCameras.length - 1, gridRow * gridColumns + column);
    }

    /**
     * Lift every shape onto a mesh by casting CPU rays through the stored camera: polylines to
     * points, polygons to fill samples (weighted by depth below the outline when bowl-shaped),
     * silhouettes to rays. Size and depth are the shape's own world-unit attributes.
     *
     * @param mesh surface the rays are cast at
     * @throws IllegalArgumentException when a shape lacks data-radius, or data-depth off a
     *                                  silhouette, still carries data-strength, or its kind
     *                                  contradicts its depth's sign
     * @return all lifted strokes in {@link StrokeSculpt}'s text form; per-shape results land in the
     *         {@code ...ByShape} fields
     */
    public String lift(MeshTopology mesh) {
        int shapeTotal = shapes.size();
        strokeByShape = new String[shapeTotal];
        viewByShape = new int[shapeTotal];
        liftedByShape = new int[shapeTotal];
        missesByShape = new int[shapeTotal];
        outOfCellByShape = new int[shapeTotal];
        grazingByShape = new int[shapeTotal];
        radiusByShape = new float[shapeTotal];
        depthByShape = new float[shapeTotal];
        kindByShape = new String[shapeTotal];
        liftedXyzByShape = new float[shapeTotal][];
        int[] faceOfTriangle = new int[mesh.triangleCount()];
        TriangleBvh bvh = TriangleBvh.over(mesh, faceOfTriangle);
        int[] stack = new int[Math.max(1, bvh.nodeCount)];
        Matrix4f model = new Matrix4f();
        Vector3f normal = new Vector3f();
        Vector3f toEye = new Vector3f();
        Vector3f ray = new Vector3f();
        Vector3f hit = new Vector3f();
        float[] origin = new float[3];
        float[] direction = new float[3];
        StringBuilder allStrokes = new StringBuilder();
        for (int stroke = 0; stroke < shapeTotal; stroke++) {
            String shape = shapes.get(stroke);
            strokeByShape[stroke] = "";
            viewByShape[stroke] = -1;
            liftedXyzByShape[stroke] = new float[0];
            boolean fill = shape.startsWith(SVG_TEXT_START + POLYGON);
            Matcher depthMatch = DEPTH_ATTRIBUTE.matcher(shape);
            Matcher radiusMatch = RADIUS_ATTRIBUTE.matcher(shape);
            Matcher kindMatch = KIND_ATTRIBUTE.matcher(shape);
            boolean mirror = MIRROR_ATTRIBUTE.matcher(shape).find();
            boolean smooth = SMOOTH_ATTRIBUTE.matcher(shape).find();
            String kind = kindMatch.find() ? kindMatch.group(1) : "";
            boolean silhouette = SILHOUETTE.equals(kind);
            if (LEGACY_STRENGTH_ATTRIBUTE.matcher(shape).find() || !radiusMatch.find()
                    || !silhouette && !smooth && !depthMatch.find()) {
                throw new IllegalArgumentException("stroke " + stroke + " needs data-radius" + (silhouette || smooth
                        ? "" : " and data-depth") + " and no data-strength: " + shape + MIGRATION_HINT);
            }
            float radius = radiusByShape[stroke] = Float.parseFloat(radiusMatch.group(1));
            float depth = depthByShape[stroke] = silhouette || smooth ? 0f : Float.parseFloat(depthMatch.group(1));
            if (RIDGE.equals(kind) && depth < 0f || VALLEY.equals(kind) && depth > 0f) {
                throw new IllegalArgumentException("stroke " + stroke + " is a " + kind + " with data-depth " + depth
                        + "; the sign of data-depth alone says raise (+) or sink (-): " + shape);
            }
            kindByShape[stroke] = silhouette ? SILHOUETTE : fill ? FILL : smooth ? StrokeSculpt.SMOOTH_FLAG
                    : depth >= 0f ? RIDGE : VALLEY;
            float[] pixels = gridPixels(shape);
            if (pixels.length < 2) {
                continue;
            }
            int view = cellAt(pixels[0], pixels[1]);
            viewByShape[stroke] = view;
            Camera3D camera = cellCamera(view);
            Vector3f target = camera.orbitTarget;
            for (int index = 0; index < pixels.length; index += 2) {
                pixels[index] -= view % columns * cellWidth;
                pixels[index + 1] -= view / columns * cellHeight;
            }
            float centerWorldPerPixel = (float) camera.worldPerPixel(target.x, target.y, target.z, cellHeight);
            // Every stroke carries its cell's eye and axis, so the sculpt touches only what this
            // camera sees.
            Vector3f forward = new Vector3f(camera.target).sub(camera.position).normalize();
            String eyeFlag = String.format(Locale.ROOT, " %s%.5f/%.5f/%.5f/%.5f/%.5f/%.5f", StrokeSculpt.EYE_PREFIX,
                    camera.position.x, camera.position.y, camera.position.z, forward.x, forward.y, forward.z);
            // A silhouette lifts to one view ray per point, the sculpt finding the contour against the
            // stroke and dragging it all the way on; a polyline to surface points, a polygon to fill
            // samples, which a bowl weights t(2 - t) by their distance in from the outline over the
            // deepest one's, so the cavity's floor curves up to its rim.
            float[] samples = fill ? PolygonScan.coveredPoints(pixels,
                    Math.max(MINIMUM_FILL_STEP_PIXELS, spacing / centerWorldPerPixel)) : pixels;
            // A chain its sidecar holds on the base takes those points as they are, whatever this
            // cell sees of them.
            float[] anchored = stroke < anchoredXyzByShape.length && !fill && !silhouette
                    ? anchoredXyzByShape[stroke] : null;
            int pointBound = Math.max(samples.length / 2, anchored == null ? 0 : anchored.length / 3);
            float[] lifted = new float[pointBound * 3];
            float[] weights = new float[pointBound];
            float[] insetBySample = fill && BOWL_ATTRIBUTE.matcher(shape).find()
                    ? PolygonScan.outlineDistances(pixels, samples) : new float[0];
            float deepestInset = 0f;
            for (float inset : insetBySample) {
                deepestInset = Math.max(deepestInset, inset);
            }
            int liftedCount = anchored == null ? 0 : anchored.length / 3;
            if (anchored != null) {
                System.arraycopy(anchored, 0, lifted, 0, anchored.length);
                Arrays.fill(weights, 1f);
            }
            for (int point = 0; anchored == null && point < samples.length / 2; point++) {
                float localX = samples[point * 2];
                float localY = samples[point * 2 + 1];
                if (localX < 0 || localY < 0 || localX >= cellWidth || localY >= cellHeight) {
                    outOfCellByShape[stroke]++;
                    continue;
                }
                if (!camera.rayThroughPixel(model, localX, localY, cellWidth, cellHeight, origin, direction)) {
                    missesByShape[stroke]++;
                    continue;
                }
                int nearestTriangle = silhouette ? -1 : bvh.nearestHit(origin, direction, Double.POSITIVE_INFINITY, stack);
                ray.set(direction[0], direction[1], direction[2]);
                hit.set(ray).normalize();
                if (!silhouette && nearestTriangle < 0) {
                    missesByShape[stroke]++;
                    continue;
                }
                if (!silhouette) {
                    hit.set(origin[0], origin[1], origin[2]).fma((float) bvh.hitParameter, ray);
                    float facing = Math.abs(mesh.faceNormal(faceOfTriangle[nearestTriangle], normal).normalize()
                            .dot(toEye.set(camera.position).sub(hit).normalize()));
                    if (facing < GRAZING_COSINE) {
                        grazingByShape[stroke]++;
                        continue;
                    }
                    if (!fill && liftedCount > 0 && hit.distance(lifted[liftedCount * 3 - 3],
                            lifted[liftedCount * 3 - 2], lifted[liftedCount * 3 - 1]) < spacing) {
                        continue;
                    }
                }
                lifted[liftedCount * 3] = hit.x;
                lifted[liftedCount * 3 + 1] = hit.y;
                lifted[liftedCount * 3 + 2] = hit.z;
                float inset = deepestInset > 0f ? insetBySample[point] / deepestInset : 1f;
                weights[liftedCount++] = inset * (2f - inset);
            }
            liftedByShape[stroke] = liftedCount;
            liftedXyzByShape[stroke] = silhouette ? new float[0] : Arrays.copyOf(lifted, liftedCount * 3);
            StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "%.5f r=%.5f", silhouette ? 1f : depth,
                    radius)).append(silhouette ? " " + StrokeSculpt.SILHOUETTE_FLAG : "")
                    .append(fill ? " " + StrokeSculpt.FILL_FLAG : "").append(mirror ? " " + StrokeSculpt.MIRROR_FLAG : "")
                    .append(smooth ? " " + StrokeSculpt.SMOOTH_FLAG : "").append(eyeFlag)
                    .append(StrokeSculpt.HEADER_SEPARATOR);
            for (int point = 0; point < liftedCount; point++) {
                text.append(point == 0 ? " " : POINT_SEPARATOR).append(String.format(Locale.ROOT,
                        "%.5f %.5f %.5f %.3f", lifted[point * 3], lifted[point * 3 + 1], lifted[point * 3 + 2],
                        weights[point]));
            }
            strokeByShape[stroke] = liftedCount > 0 ? text.toString() : "";
            if (!strokeByShape[stroke].isEmpty()) {
                allStrokes.append(allStrokes.length() == 0 ? "" : STROKE_SEPARATOR).append(strokeByShape[stroke]);
            }
        }
        return allStrokes.toString();
    }

    /**
     * Add one stroke drawn on a live view as a new cell recording that view's camera, so the stroke
     * lifts through the view it was drawn on. The cells of a live sketch run in one row.
     *
     * @param kind             {@link #SILHOUETTE}, {@link #RIDGE}, {@link #VALLEY} or {@link #FILL}
     * @param strength         depth over radius, the depth written as data-depth; ridge and valley
     *                         take its magnitude
     * @param widthPixels      falloff radius in view pixels at the view's target, written as
     *                         data-radius in world units
     * @param pixels           the stroke's points in view pixels from the top left, packed xy
     * @param view             the camera the stroke was drawn through
     * @param viewOrthographic whether that view projects orthographically
     * @param viewWidth        the view's width in pixels
     * @param viewHeight       the view's height in pixels
     * @throws IllegalArgumentException when the sketch's cells have another size or projection, or
     *                                  run in more than one row
     */
    public void addStroke(String kind, float strength, float widthPixels, float[] pixels, Camera3D view,
            boolean viewOrthographic, int viewWidth, int viewHeight) {
        if (viewCameras.length == 0) {
            cellWidth = viewWidth;
            cellHeight = viewHeight;
            orthographic = viewOrthographic;
            center[0] = view.orbitTarget.x;
            center[1] = view.orbitTarget.y;
            center[2] = view.orbitTarget.z;
            distance = view.distance;
            fieldOfViewDegrees = (float) view.fov;
            svgScaleX = 1f;
            svgScaleY = 1f;
        } else if (columns != viewCameras.length) {
            throw new IllegalArgumentException("this pass was drawn on a multiview grid; live strokes go on "
                    + "a pass of their own");
        } else if (viewWidth != cellWidth || viewHeight != cellHeight || viewOrthographic != orthographic) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "this pass was drawn on a %dx%d %s view and the view is now %dx%d %s; commit the pass to "
                            + "draw on this one", cellWidth, cellHeight, orthographic ? ORTHOGRAPHIC : PERSPECTIVE,
                    viewWidth, viewHeight, viewOrthographic ? ORTHOGRAPHIC : PERSPECTIVE));
        }
        int column = viewCameras.length;
        viewCameras = Arrays.copyOf(viewCameras, column + 1);
        viewCameras[column] = new float[] { view.azimuth, view.elevation, view.distance, view.roll, (float) view.fov,
            view.orbitTarget.x, view.orbitTarget.y, view.orbitTarget.z };
        columns = column + 1;
        String points = gridPoints(column, pixels, pixels.length / 2);
        boolean fill = FILL.equals(kind);
        Vector3f target = view.orbitTarget;
        float radius = widthPixels * (float) view.worldPerPixel(target.x, target.y, target.z, viewHeight);
        String depth = SILHOUETTE.equals(kind) ? ""
                : String.format(Locale.ROOT, " data-depth=\"%.5f\"", (RIDGE.equals(kind) ? Math.abs(strength)
                        : VALLEY.equals(kind) ? -Math.abs(strength) : strength) * radius);
        shapes.add(String.format(Locale.ROOT, "<%s%s data-radius=\"%.5f\"%s stroke-width=\"%.1f\" points=\"%s\"/>",
                fill ? POLYGON : POLYLINE, fill ? "" : " data-kind=\"" + kind + "\" fill=\"none\"", radius, depth,
                widthPixels / svgScaleY, points));
    }

    /**
     * The sketch as SVG text {@link #parse} reads back to the same cameras and shapes, every cell's
     * camera written out in full.
     *
     * @return the SVG text
     */
    public String toSvg() {
        int rows = (viewCameras.length + columns - 1) / Math.max(1, columns);
        int width = columns * cellWidth;
        int height = rows * cellHeight;
        StringBuilder views = new StringBuilder();
        for (float[] camera : viewCameras) {
            views.append(views.length() == 0 ? "" : VIEW_SEPARATOR).append(String.format(Locale.ROOT,
                    "%.6f %.6f %.6f %.6f %.4f %.6f %.6f %.6f", camera[VIEW_AZIMUTH], camera[VIEW_ELEVATION],
                    camera[VIEW_DISTANCE], camera[VIEW_ROLL], camera[VIEW_FIELD_OF_VIEW], camera[VIEW_CENTER],
                    camera[VIEW_CENTER + 1], camera[VIEW_CENTER + 2]));
        }
        StringBuilder svg = new StringBuilder(String.format(Locale.ROOT,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\"%n"
                        + "     %s=\"%.6f %.6f %.6f\" %s=\"%.6f\" %s=\"%.4f\" %s=\"%s\"%n"
                        + "     %s=\"%d\" %s=\"%d\" %s=\"%d\"%n     %s=\"%s\">%n"
                        + "<!-- One cell per view; each data-views entry is that view's azimuth, elevation, "
                        + "distance, roll, field of view and centre. -->%n",
                width, height, Math.round(width / svgScaleX), Math.round(height / svgScaleY), CENTER_ATTRIBUTE,
                center[0], center[1], center[2], DISTANCE_ATTRIBUTE, distance, FIELD_OF_VIEW_ATTRIBUTE,
                fieldOfViewDegrees, PROJECTION_ATTRIBUTE, orthographic ? ORTHOGRAPHIC : PERSPECTIVE,
                CELL_WIDTH_ATTRIBUTE, cellWidth, CELL_HEIGHT_ATTRIBUTE, cellHeight, COLUMNS_ATTRIBUTE, columns,
                VIEWS_ATTRIBUTE, views));
        for (String shape : shapes) {
            svg.append(shape).append('\n');
        }
        return svg.append("</svg>\n").toString();
    }
}
