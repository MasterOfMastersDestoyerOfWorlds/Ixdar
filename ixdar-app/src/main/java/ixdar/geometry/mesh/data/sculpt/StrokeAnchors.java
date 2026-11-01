package ixdar.geometry.mesh.data.sculpt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.graphics.cameras.Camera3D;

/**
 * A stroke layer's stored truth, the sidecar beside its SVG: every stroke under its stable id, held
 * on the base mesh, plus the SVG {@code ixdar-cli sculpt-sync} last wrote from them, which edits are
 * diffed against.
 */
public final class StrokeAnchors {

    public static final String SVG_SUFFIX = ".svg";

    public static final String SUFFIX = ".strokes";

    public static final String BASE_LINE = "base";

    public static final String STROKE_LINE = "stroke";

    public static final String ATTRIBUTES_LINE = "attributes";

    public static final String ANCHORS_LINE = "anchors";

    public static final String PIXELS_LINE = "pixels";

    public static final String WRITTEN_LINE = "written";

    public static final String POLYGON = "polygon";

    public static final String POLYLINE = "polyline";

    public static final String CELL_KEY = "cell=";

    public static final String LAYER_KEY = "layer=";

    public static final String SHAPE_KEY = "shape=";

    public static final String NO_LAYER = "-";

    public static final String ID_PREFIX = "s";

    public static final String LAYER_ID_SEPARATOR = ".";

    public static final String ENTRY_SEPARATOR = "; ";

    public static final String FIELD_SEPARATOR = " ";

    public static final String LINE_BREAK = "\n";

    /** The strokes, in drawing order. */
    public List<AnchoredStroke> strokes = new ArrayList<>();

    /** Vertex count of the base the anchors index, so a changed base is refused. */
    public int baseVertices;

    /** Face count of that base. */
    public int baseFaces;

    /** The SVG text sculpt-sync last wrote, empty before the first sync. */
    public String written = "";

    /**
     * The sidecar path beside an SVG path: its {@code .svg} replaced by {@link #SUFFIX}.
     *
     * @param svgPath the layer's svg argument
     * @return the sidecar's path in the same form
     */
    public static String sidecarPath(String svgPath) {
        return (svgPath.endsWith(SVG_SUFFIX) ? svgPath.substring(0, svgPath.length() - SVG_SUFFIX.length()) : svgPath)
                + SUFFIX;
    }

    /**
     * The sidecar beside the SVG {@link StrokeSketch#read} reads: next to the file
     * {@link MeshLoader#resolve} finds, else beside the classpath resource. A sidecar deleted next to
     * the file is gone, never read from a build copy on the classpath.
     *
     * @param svgPath the layer's svg argument; SVG text has no sidecar
     * @throws IllegalStateException when the sidecar exists but cannot be read
     * @return the anchors, or null when there is no sidecar
     */
    public static StrokeAnchors beside(String svgPath) {
        if (svgPath.startsWith(StrokeSketch.SVG_TEXT_START)) {
            return null;
        }
        String path = sidecarPath(svgPath);
        try {
            Path svgFile = MeshLoader.resolve(svgPath);
            if (Files.isRegularFile(svgFile)) {
                Path file = svgFile.resolveSibling(Path.of(path).getFileName());
                return Files.isRegularFile(file) ? parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                        : null;
            }
            try (InputStream stream = StrokeAnchors.class.getClassLoader().getResourceAsStream(path)) {
                return stream == null ? null : parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException unreadable) {
            throw new IllegalStateException("cannot read the stroke sidecar " + path + ": " + unreadable.getMessage(),
                    unreadable);
        }
    }

    /**
     * Sync one stroke layer's files: fold the SVG's edits into its sidecar (made from the SVG's
     * shapes, held where they land on the base, the first time), re-run the layer, and rewrite the
     * SVG from the strokes on the new sculpt.
     *
     * @param svgPath  the layer's svg argument, a file {@link MeshLoader#resolve} finds
     * @param base     the layer's input geometry
     * @param evaluate runs the layer as its files now stand, returning its output geometry
     * @throws IOException              when a file cannot be read or written
     * @throws IllegalArgumentException when the edits are refused; no file is written then
     * @return what changed, one line per stroke, then where the strokes were written
     */
    public static List<String> sync(String svgPath, GeometryBundle base, Supplier<GeometryBundle> evaluate)
            throws IOException {
        Path svgFile = MeshLoader.resolve(svgPath);
        Path sidecarFile = svgFile.resolveSibling(Path.of(sidecarPath(svgPath)).getFileName());
        String svg = new String(Files.readAllBytes(svgFile), StandardCharsets.UTF_8);
        StrokeSketch sketch = StrokeSketch.parse(svg, svgPath);
        StrokeAnchors anchors = Files.isRegularFile(sidecarFile)
                ? parse(new String(Files.readAllBytes(sidecarFile), StandardCharsets.UTF_8)) : null;
        MeshTopology baseMesh = base.mesh();
        SculptSync folding;
        if (anchors == null) {
            // The first sync holds the SVG's strokes where the layer lifted them: on the base.
            anchors = new StrokeAnchors();
            folding = new SculptSync(sketch, baseMesh, baseMesh, baseMesh);
        } else {
            anchors.checkBase(baseMesh, sidecarFile.toString());
            GeometryBundle before = evaluate.get();
            folding = new SculptSync(sketch, baseMesh, ((StrokeSculpt) before.slots().get(StrokeSculpt.SCULPT_SLOT))
                    .refined, before.mesh());
        }
        folding.fold(anchors, svg);
        String sidecarBefore = Files.isRegularFile(sidecarFile)
                ? new String(Files.readAllBytes(sidecarFile), StandardCharsets.UTF_8) : null;
        Files.write(sidecarFile, anchors.toText().getBytes(StandardCharsets.UTF_8));
        GeometryBundle after;
        try {
            after = evaluate.get();
        } catch (RuntimeException failed) {
            if (sidecarBefore == null) {
                Files.delete(sidecarFile);
            } else {
                Files.write(sidecarFile, sidecarBefore.getBytes(StandardCharsets.UTF_8));
            }
            throw failed;
        }
        StrokeSculpt sculpt = (StrokeSculpt) after.slots().get(StrokeSculpt.SCULPT_SLOT);
        anchors.written = new SculptSync(sketch, baseMesh, sculpt.refined, after.mesh()).draw(anchors);
        Files.write(sidecarFile, anchors.toText().getBytes(StandardCharsets.UTF_8));
        Files.write(svgFile, anchors.written.getBytes(StandardCharsets.UTF_8));
        List<String> report = new ArrayList<>(folding.changes);
        report.add(anchors.strokes.size() + " stroke(s) held in " + sidecarFile + ", drawn into " + svgFile);
        return report;
    }

    /**
     * Read a sidecar's text.
     *
     * @param text the sidecar, as {@link #toText} writes it
     * @throws IllegalArgumentException when a line is malformed
     * @return the anchors
     */
    public static StrokeAnchors parse(String text) {
        StrokeAnchors anchors = new StrokeAnchors();
        int writtenAt = text.indexOf(LINE_BREAK + WRITTEN_LINE + LINE_BREAK);
        anchors.written = writtenAt < 0 ? "" : text.substring(writtenAt + WRITTEN_LINE.length() + 2);
        AnchoredStroke stroke = null;
        for (String line : (writtenAt < 0 ? text : text.substring(0, writtenAt)).split(LINE_BREAK)) {
            String trimmed = line.trim();
            int space = trimmed.indexOf(' ');
            String key = space < 0 ? trimmed : trimmed.substring(0, space);
            String rest = space < 0 ? "" : trimmed.substring(space + 1).trim();
            if (BASE_LINE.equals(key)) {
                String[] counts = rest.split(FIELD_SEPARATOR);
                anchors.baseVertices = Integer.parseInt(counts[0]);
                anchors.baseFaces = Integer.parseInt(counts[1]);
            } else if (STROKE_LINE.equals(key)) {
                stroke = new AnchoredStroke();
                anchors.strokes.add(stroke);
                for (String field : rest.split(FIELD_SEPARATOR)) {
                    stroke.cell = field.startsWith(CELL_KEY) ? Integer.parseInt(field.substring(CELL_KEY.length()))
                            : stroke.cell;
                    stroke.layer = field.startsWith(LAYER_KEY) && !field.equals(LAYER_KEY + NO_LAYER)
                            ? field.substring(LAYER_KEY.length()) : stroke.layer;
                    stroke.polygon |= field.equals(SHAPE_KEY + POLYGON);
                }
                stroke.id = rest.split(FIELD_SEPARATOR)[0];
            } else if (stroke != null && ATTRIBUTES_LINE.equals(key)) {
                stroke.attributes = rest;
            } else if (stroke != null && (ANCHORS_LINE.equals(key) || PIXELS_LINE.equals(key))) {
                String[] entries = rest.isEmpty() ? new String[0] : rest.split(ENTRY_SEPARATOR.trim());
                int fields = ANCHORS_LINE.equals(key) ? FaceAnchors.FIELDS : 2;
                float[] values = new float[entries.length * fields];
                for (int entry = 0; entry < entries.length; entry++) {
                    String[] numbers = entries[entry].trim().split(FIELD_SEPARATOR);
                    if (numbers.length != fields) {
                        throw new IllegalArgumentException("stroke " + stroke.id + ": a " + key + " entry needs "
                                + fields + " numbers: " + entries[entry]);
                    }
                    for (int field = 0; field < fields; field++) {
                        values[entry * fields + field] = Float.parseFloat(numbers[field]);
                    }
                }
                if (ANCHORS_LINE.equals(key)) {
                    stroke.anchors = values;
                } else {
                    stroke.pixels = values;
                }
            }
        }
        return anchors;
    }

    /**
     * The sidecar's text, which {@link #parse} reads back to the same anchors.
     *
     * @return the text
     */
    public String toText() {
        StringBuilder text = new StringBuilder("# Stroke sidecar (MESH-86): every stroke of the SVG beside it as a curve "
                + "on the base mesh, a face, fan triangle and barycentric u v per point (a silhouette: pixels in its "
                + "cell).\n# ixdar-cli sculpt-sync writes this file and the SVG from it; edit the SVG, then sync.\n");
        text.append(BASE_LINE).append(' ').append(baseVertices).append(' ').append(baseFaces).append('\n');
        for (AnchoredStroke stroke : strokes) {
            text.append(STROKE_LINE).append(' ').append(stroke.id).append(' ').append(CELL_KEY).append(stroke.cell)
                    .append(' ').append(LAYER_KEY).append(stroke.layer.isEmpty() ? NO_LAYER : stroke.layer).append(' ')
                    .append(SHAPE_KEY).append(stroke.polygon ? POLYGON : POLYLINE).append('\n');
            text.append(ATTRIBUTES_LINE).append(' ').append(stroke.attributes).append('\n');
            boolean silhouette = stroke.silhouette();
            float[] values = silhouette ? stroke.pixels : stroke.anchors;
            int fields = silhouette ? 2 : FaceAnchors.FIELDS;
            text.append(silhouette ? PIXELS_LINE : ANCHORS_LINE);
            for (int entry = 0; entry < values.length / fields; entry++) {
                text.append(entry == 0 ? " " : ENTRY_SEPARATOR);
                for (int field = 0; field < fields; field++) {
                    float value = values[entry * fields + field];
                    text.append(field == 0 ? "" : FIELD_SEPARATOR).append(!silhouette && field < 2
                            ? String.valueOf((int) value) : String.format(Locale.ROOT, "%.6f", value));
                }
            }
            text.append('\n');
        }
        return text.append(WRITTEN_LINE).append('\n').append(written).toString();
    }

    /**
     * Refuse a base the anchors were not made on, by its vertex and face counts.
     *
     * @param base   the layer's input mesh
     * @param source the sidecar's path, for the refusal
     * @throws IllegalStateException naming both counts and the fix
     */
    public void checkBase(MeshTopology base, String source) {
        if (base.vertexCount() != baseVertices || base.faceCount() != baseFaces) {
            throw new IllegalStateException(String.format(Locale.ROOT, "%s holds its strokes on a base of %d vertices "
                    + "and %d faces, and this layer's input has %d and %d: the base changed. Restore the base the "
                    + "strokes were drawn on, or start a new sculpt DSL on the new base", source, baseVertices,
                    baseFaces, base.vertexCount(), base.faceCount()));
        }
    }

    /**
     * The sketch a layer evaluates: one shape per stroke in the cell it was drawn in, chains carrying
     * their points on the base, fills their outline projected through that cell, silhouettes their
     * pixels.
     *
     * @param camera the layer's SVG, for its cameras
     * @param base   the base mesh the anchors index
     * @return the sketch, its shapes in stroke order with their ids
     */
    public StrokeSketch sketch(StrokeSketch camera, MeshTopology base) {
        StrokeSketch sketch = StrokeSketch.parse(camera.text, "sidecar sketch");
        sketch.shapes = new ArrayList<>();
        sketch.anchoredXyzByShape = new float[strokes.size()][];
        sketch.idByShape = new String[strokes.size()];
        float[] pixel = new float[2];
        Vector3f position = new Vector3f();
        for (int index = 0; index < strokes.size(); index++) {
            AnchoredStroke stroke = strokes.get(index);
            sketch.idByShape[index] = stroke.id;
            int count = stroke.pointCount();
            float[] pixels = stroke.silhouette() ? stroke.pixels : new float[count * 2];
            float[] xyz = new float[count * 3];
            Camera3D cellCamera = sketch.cellCamera(stroke.cell);
            for (int point = 0; point < count && !stroke.silhouette(); point++) {
                FaceAnchors.position(base, stroke.anchors, point * FaceAnchors.FIELDS, position);
                xyz[point * 3] = position.x;
                xyz[point * 3 + 1] = position.y;
                xyz[point * 3 + 2] = position.z;
                // Kept inside the cell, which the first point names and every fill sample is cast in.
                sketch.cellPixel(cellCamera, position, pixel);
                pixels[point * 2] = Math.max(0f, Math.min(sketch.cellWidth - 1f, pixel[0]));
                pixels[point * 2 + 1] = Math.max(0f, Math.min(sketch.cellHeight - 1f, pixel[1]));
            }
            sketch.anchoredXyzByShape[index] = stroke.silhouette() || stroke.polygon ? null : xyz;
            sketch.shapes.add("<" + (stroke.polygon ? POLYGON : POLYLINE) + " " + stroke.attributes + " points=\""
                    + sketch.gridPoints(stroke.cell, pixels, count) + "\"/>");
        }
        return sketch;
    }

    /**
     * The stroke under an id.
     *
     * @param id the id
     * @return the stroke, or null
     */
    public AnchoredStroke stroke(String id) {
        for (AnchoredStroke stroke : strokes) {
            if (stroke.id.equals(id)) {
                return stroke;
            }
        }
        return null;
    }
}
