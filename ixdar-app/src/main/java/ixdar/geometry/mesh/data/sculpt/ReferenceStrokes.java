package ixdar.geometry.mesh.data.sculpt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import org.joml.Vector3f;

import ixdar.geometry.grid2d.PolygonScan;
import ixdar.geometry.mesh.data.paths.query.TriangleBvh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.image.ReferenceOutlines;
import ixdar.graphics.image.ShadingEdges;

/**
 * Reference pictures' shading as a stroke SVG on a sculpt's camera: each edge a ridge or valley
 * chain, each dark region a bowl fill, placed through the trace-outlines fit onto the base.
 */
public final class ReferenceStrokes {

    public static final String GROUP_PREFIX = "edges_";

    public static final float MINIMUM_RADIUS = 0.015f;

    public static final float MAXIMUM_RADIUS = 0.12f;

    public static final float RADIUS_PER_WIDTH = 0.5f;

    public static final float FILL_RADIUS_PER_INSET = 1f;

    public static final float MAXIMUM_TURN = 1.4f;

    public static final float LIMIT_SHARE = 0.9f;

    public static final float WHOLE_OUTLINE_SHARE = 0.75f;

    public static final float SAMPLES_PER_FILL_RADIUS = 1.25f;

    public static final float RAY_START_EXTENTS = 4f;

    public static final float PLACE_COSINE = 0.5f;

    public static final float RESAMPLE_PIXELS = 3f;

    public static final int COVERED_FIELDS = 4;

    public static final float CAVITY_DEPTH_PER_INSET = 2.5f;

    public static final double ROUND_HOLLOW = 0.5;

    public static final float HOLLOW_DARKNESS = 0.3f;

    public static final float FAINT_DARKNESS = 0.15f;

    public static final float MINIMUM_LENGTH_RADII = 2f;

    public static final float MINIMUM_CONTRAST = 0.08f;

    public static final String RIDGE_COLOUR = "crimson";

    public static final String VALLEY_COLOUR = "royalblue";

    public static final String FILL_COLOUR = "seagreen";

    public static final String GROUP_END = "</g>\n";

    public static final String SVG_END = "</svg>";

    public static final int PLACED_FIELDS = 4;

    /** The template's text with one {@code <g id="edges_<view>">} group of strokes per picture. */
    public String svg = "";

    /** Per picture, its kept strokes in its own pixels, for drawing over it (strokes_overlay.py). */
    public String[] pictureSvgByView = new String[0];

    /** Ridge chains written per picture. */
    public int[] ridgesByView = new int[0];

    /** Valley chains written per picture. */
    public int[] valleysByView = new int[0];

    /** Fills written per picture. */
    public int[] fillsByView = new int[0];

    /** The fitted pictures. */
    public ReferenceOutlines traced;

    /** The base the strokes land on. */
    public MeshTopology base;

    /** Triangle tree over {@link #base}. */
    public TriangleBvh triangles;

    /** Base face of each triangle of {@link #triangles}. */
    public int[] faceOfTriangle;

    /** Scratch stack for {@link #triangles}. */
    public int[] stack;

    /** The sculpt's camera the strokes are drawn for. */
    public StrokeSketch template;

    /** Each picture's view direction toward its eye, unit xyz. */
    public float[][] backByView;

    /** The template cell each picture is drawn in: the one looking most nearly its way. */
    public int[] templateCellByView;

    /** Base points earlier pictures' marks landed on, {@link #COVERED_FIELDS} each: xyz, the mark's radius. */
    public float[] covered = new float[0];

    /** Points in {@link #covered}. */
    public int coveredPoints;

    /** The current picture's landed points, joining {@link #covered} once the picture is done. */
    public float[] pending = new float[0];

    /** Points in {@link #pending}. */
    public int pendingPoints;

    /**
     * Turn every picture's shading into strokes on the sculpt's camera: each mark placed by the fit,
     * cast along its view onto the base where no earlier mark lies, axis pictures first, and drawn
     * into the template cell looking the same way.
     *
     * @param traced   the pictures, fitted (fitAxisViews and fitObliqueViews)
     * @param shading  each picture's shading
     * @param names    each picture's view name, for its group
     * @param base     the base mesh in the fit's world
     * @param template the sculpt's SVG, whose cameras the strokes are drawn through
     * @return the strokes
     */
    public static ReferenceStrokes of(ReferenceOutlines traced, ShadingEdges[] shading, String[] names,
            MeshTopology base, StrokeSketch template) {
        ReferenceStrokes strokes = new ReferenceStrokes();
        strokes.traced = traced;
        strokes.base = base;
        strokes.template = template;
        strokes.faceOfTriangle = new int[base.triangleCount()];
        strokes.triangles = TriangleBvh.over(base, strokes.faceOfTriangle);
        strokes.stack = new int[Math.max(1, strokes.triangles.nodeCount)];
        int views = traced.views;
        strokes.backByView = new float[views][];
        strokes.templateCellByView = new int[views];
        for (int view = 0; view < views; view++) {
            float[] axes = traced.axesByView[view];
            Vector3f back = new Vector3f(axes[0], axes[1], axes[2]).cross(axes[ReferenceOutlines.UP],
                    axes[ReferenceOutlines.UP + 1], axes[ReferenceOutlines.UP + 2]).normalize();
            strokes.backByView[view] = new float[] { back.x, back.y, back.z };
            float nearest = -Float.MAX_VALUE;
            for (int cell = 0; cell < template.viewCameras.length; cell++) {
                Camera3D camera = template.cellCamera(cell);
                float along = new Vector3f(camera.position).sub(camera.target).normalize().dot(back);
                if (along > nearest) {
                    nearest = along;
                    strokes.templateCellByView[view] = cell;
                }
            }
        }
        float finest = new StrokeRefinement().minimumEdgeLength;
        strokes.pictureSvgByView = new String[views];
        strokes.ridgesByView = new int[views];
        strokes.valleysByView = new int[views];
        strokes.fillsByView = new int[views];
        String[] groupByView = new String[views];
        // Axis pictures place their marks first; a 3/4 picture then adds what they left uncovered.
        for (int view : IntStream.range(0, views).boxed().sorted(Comparator.comparing(
                (Integer each) -> traced.measuredByView[each] == null)).mapToInt(Integer::intValue).toArray()) {
            ShadingEdges marks = shading[view];
            float worldPerPixel = traced.scaleByView[view];
            int cell = strokes.templateCellByView[view];
            StringBuilder group = new StringBuilder();
            StringBuilder[] onPicture = { new StringBuilder(), new StringBuilder(), new StringBuilder() };
            // Strongest first, so a weaker line beside it merges into it; faint ones only crumple.
            for (int line : IntStream.range(0, marks.lines.size()).boxed().sorted((first, second) -> Float.compare(
                    Math.abs(marks.contrastByLine[second]), Math.abs(marks.contrastByLine[first])))
                    .mapToInt(Integer::intValue).toArray()) {
                if (Math.abs(marks.contrastByLine[line]) < MINIMUM_CONTRAST) {
                    continue;
                }
                float radius = Math.max(MINIMUM_RADIUS, Math.min(MAXIMUM_RADIUS,
                        RADIUS_PER_WIDTH * marks.widthByLine[line] * worldPerPixel));
                // The step across the edge as the flanks' turn, 2 atan(s) for slope s = 1.54 depth / radius.
                float turn = Math.min(MAXIMUM_TURN, Math.abs(marks.contrastByLine[line]) * (float) Math.PI / 2f);
                float depth = Math.min(radius * (float) Math.tan(turn / 2f) / SculptStroke.FALLOFF_STEEPEST_SLOPE,
                        LIMIT_SHARE * SculptStroke.steepestDepth(radius, SculptStroke.FALLOFF_STEEPEST_SLOPE, finest));
                boolean ridge = marks.ridgeByLine[line];
                String attributes = String.format(Locale.ROOT, "data-kind=\"%s\" data-depth=\"%.5f\" "
                        + "data-radius=\"%.4f\" fill=\"none\" stroke=\"%s\" stroke-width=\"2\"", ridge
                                ? StrokeSketch.RIDGE : StrokeSketch.VALLEY, ridge ? depth : -depth, radius,
                        ridge ? RIDGE_COLOUR : VALLEY_COLOUR);
                onPicture[ridge ? 0 : 1].append(strokes.unplacedTag(StrokeSketch.POLYLINE, attributes,
                        marks.lines.get(line)));
                for (float[] piece : strokes.place(view, marks.lines.get(line), false, radius)) {
                    // A piece shorter than its own width is a dimple, not a line: it crumples the surface.
                    double length = 0.0;
                    for (int point = PLACED_FIELDS; point < piece.length; point += PLACED_FIELDS) {
                        length += Math.hypot(piece[point + 2] - piece[point + 2 - PLACED_FIELDS],
                                piece[point + 3] - piece[point + 3 - PLACED_FIELDS]);
                    }
                    if (length * worldPerPixel < MINIMUM_LENGTH_RADII * radius) {
                        continue;
                    }
                    group.append(strokes.shapeTag(StrokeSketch.POLYLINE, attributes, cell, piece, 0));
                    onPicture[ridge ? 0 : 1].append(strokes.shapeTag(StrokeSketch.POLYLINE, attributes, -1, piece, 2));
                    strokes.ridgesByView[view] += ridge ? 1 : 0;
                    strokes.valleysByView[view] += ridge ? 0 : 1;
                }
            }
            for (int region = 0; region < marks.regions.size(); region++) {
                float[] outline = marks.regions.get(region);
                double perimeter = 0.0;
                for (int point = 0; point + 3 < outline.length; point += 2) {
                    perimeter += Math.hypot(outline[point + 2] - outline[point], outline[point + 3] - outline[point + 1]);
                }
                // A dark region is a cavity about as deep as it is wide when black: 2 area / perimeter
                // is its inset, how far its middle lies from its rim.
                float inset = (float) (2.0 * marks.areaByRegion[region] / Math.max(1.0, perimeter)) * worldPerPixel;
                // Under the sample spacing a fill's samples do not overlap and it stipples into dots.
                float radius = Math.max(StrokeSketch.DEFAULT_SPACING * SAMPLES_PER_FILL_RADIUS, Math.min(MAXIMUM_RADIUS,
                        FILL_RADIUS_PER_INSET * inset));
                // The bowl's floor rises over the inset its lift measures, its deepest fill sample's.
                float deepestSample = 0f;
                for (float distance : PolygonScan.outlineDistances(outline, PolygonScan.coveredPoints(outline,
                        Math.max(StrokeSketch.MINIMUM_FILL_STEP_PIXELS, StrokeSketch.DEFAULT_SPACING / worldPerPixel)))) {
                    deepestSample = Math.max(deepestSample, distance * worldPerPixel);
                }
                inset = Math.min(inset, deepestSample);
                float slope = FillStroke.rimSlope(StrokeSketch.DEFAULT_SPACING / radius);
                // A round, dark hollow (an eye socket, a nasal opening) is about as deep as its mouth is
                // wide; a long thin band is the shadow along a crease, and a faint one a shallow dip.
                float roundness = (float) Math.min(1.0, 4.0 * Math.PI * marks.areaByRegion[region]
                        / Math.max(1.0, perimeter * perimeter) / ROUND_HOLLOW);
                float darkness = Math.max(0f, Math.min(1f, (marks.darknessByRegion[region] - FAINT_DARKNESS)
                        / (HOLLOW_DARKNESS - FAINT_DARKNESS)));
                float depth = Math.min(CAVITY_DEPTH_PER_INSET * inset * roundness * darkness, LIMIT_SHARE * Math.min(
                        SculptStroke.steepestDepth(radius, slope, finest),
                        SculptStroke.steepestDepth(inset, SculptStroke.BOWL_RIM_SLOPE, finest)));
                // An outline that lost points to another picture or off the base would cut a chord
                // across what it encloses: only a nearly whole one is a fill here.
                String attributes = String.format(Locale.ROOT, "data-depth=\"%.5f\" data-radius=\"%.4f\" "
                        + "data-profile=\"bowl\" fill=\"none\" stroke=\"%s\" stroke-width=\"2\"", -depth, radius,
                        FILL_COLOUR);
                onPicture[2].append(strokes.unplacedTag(StrokeSketch.POLYGON, attributes + String.format(Locale.ROOT,
                        " data-darkness=\"%.3f\"", marks.darknessByRegion[region]), outline));
                List<float[]> pieces = strokes.place(view, outline, true, radius);
                if (depth <= 0f || pieces.isEmpty() || pieces.get(0).length < 3 * PLACED_FIELDS
                        || pieces.get(0).length / PLACED_FIELDS < WHOLE_OUTLINE_SHARE * (outline.length / 2)) {
                    continue;
                }
                group.append(strokes.shapeTag(StrokeSketch.POLYGON, attributes, cell, pieces.get(0), 0));
                onPicture[2].append(strokes.shapeTag(StrokeSketch.POLYGON, attributes, -1, pieces.get(0), 2));
                strokes.fillsByView[view]++;
            }
            groupByView[view] = "<g id=\"" + GROUP_PREFIX + names[view] + "\">\n" + group + GROUP_END;
            strokes.covered = Arrays.copyOf(strokes.covered, (strokes.coveredPoints + strokes.pendingPoints)
                    * COVERED_FIELDS);
            System.arraycopy(strokes.pending, 0, strokes.covered, strokes.coveredPoints * COVERED_FIELDS,
                    strokes.pendingPoints * COVERED_FIELDS);
            strokes.coveredPoints += strokes.pendingPoints;
            strokes.pendingPoints = 0;
            int width = marks.width;
            int height = marks.height;
            // strokes_overlay.py reads world units per pixel from the cell height, distance and field
            // of view: this picture's own scale, through a 90 degree view of the picture's height.
            strokes.pictureSvgByView[view] = String.format(Locale.ROOT, "<svg xmlns=\"http://www.w3.org/2000/svg\" "
                    + "width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\" data-cell-height=\"%d\" data-distance=\"%.6f\" "
                    + "data-fov=\"90\">%n<!-- reference-strokes: the strokes computed from this picture, in its own "
                    + "pixels, ridges, valleys and fills -->%n<g id=\"ridges\">%n%s</g>%n<g id=\"valleys\">%n%s</g>%n"
                    + "<g id=\"fills\">%n%s</g>%n%s%n", width, height, width, height, height,
                    height * worldPerPixel / 2f, onPicture[0], onPicture[1], onPicture[2], SVG_END);
        }
        String text = template.text;
        int end = text.lastIndexOf(SVG_END);
        strokes.svg = (end < 0 ? text : text.substring(0, end)) + "<!-- reference-strokes: every shading edge of each "
                + "reference picture as a ridge or valley, every dark region as a bowl fill, in a group per picture; "
                + "edit or delete them, then ixdar-cli sculpt-sync -->\n" + String.join("", groupByView) + SVG_END + "\n";
        return strokes;
    }

    /**
     * Place a picture's mark on the sculpt: each point through the fit into its cell, cast along the
     * view onto the base, kept where the picture sees the surface within 60 degrees and no earlier
     * picture's mark lies, then projected into the template cell. A line breaks where points drop.
     *
     * @param view   the picture
     * @param sparse the mark's points in picture pixels, packed xy
     * @param closed whether the mark is an outline, kept whole from its placed points
     * @param radius the mark's stroke radius, how near another mark covers it
     * @return pieces of {@link #PLACED_FIELDS} floats per point: template cell pixel, then picture pixel
     */
    public List<float[]> place(int view, float[] sparse, boolean closed, float radius) {
        int ownStart = pendingPoints;
        // Points no further apart than RESAMPLE_PIXELS, so no earlier mark hides between two of them.
        float[] picture = new float[0];
        int filled = 0;
        for (int point = 0; point < sparse.length / 2; point++) {
            float stepX = point == 0 ? 0f : sparse[point * 2] - sparse[point * 2 - 2];
            float stepY = point == 0 ? 0f : sparse[point * 2 + 1] - sparse[point * 2 - 1];
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(stepX, stepY) / RESAMPLE_PIXELS));
            picture = Arrays.copyOf(picture, filled + steps * 2);
            for (int step = point == 0 ? steps - 1 : 0; step < steps; step++) {
                float along = (step + 1f) / steps;
                picture[filled++] = sparse[point * 2] - stepX * (1f - along);
                picture[filled++] = sparse[point * 2 + 1] - stepY * (1f - along);
            }
        }
        picture = Arrays.copyOf(picture, filled);
        float[] box = { traced.subjectByView[view].box[0] + traced.subjectByView[view].box[2] / 2f,
            traced.subjectByView[view].box[1] + traced.subjectByView[view].box[3] / 2f };
        float[] axes = traced.axesByView[view];
        float[] back = backByView[view];
        float extent = Math.max(traced.extents[0], Math.max(traced.extents[1], traced.extents[2]));
        int cell = templateCellByView[view];
        Camera3D camera = template.cellCamera(cell);
        List<float[]> pieces = new ArrayList<>();
        float[] piece = new float[picture.length / 2 * PLACED_FIELDS];
        int length = 0;
        float[] origin = new float[3];
        float[] direction = { -back[0], -back[1], -back[2] };
        Vector3f normal = new Vector3f();
        Vector3f hit = new Vector3f();
        float[] pixel = new float[2];
        for (int point = 0; point < picture.length / 2; point++) {
            float cellX = (picture[point * 2] - box[0]) * traced.pixelsPerPicturePixelByView[view]
                    + traced.centreByView[view][0];
            float cellY = (picture[point * 2 + 1] - box[1]) * traced.pixelsPerPicturePixelByView[view]
                    + traced.centreByView[view][1];
            float across = (cellX - (traced.cellWidth / 2f - 0.5f)) * traced.worldPerCellPixel;
            float up = (traced.cellHeight / 2f - 0.5f - cellY) * traced.worldPerCellPixel;
            for (int axis = 0; axis < 3; axis++) {
                origin[axis] = axes[axis] * across + axes[ReferenceOutlines.UP + axis] * up
                        + back[axis] * extent * RAY_START_EXTENTS;
            }
            int triangle = triangles.nearestHit(origin, direction, Double.POSITIVE_INFINITY, stack);
            boolean kept = triangle >= 0;
            if (kept) {
                hit.set(origin[0], origin[1], origin[2]).fma((float) triangles.hitParameter,
                        new Vector3f(direction[0], direction[1], direction[2]));
                base.faceNormal(faceOfTriangle[triangle], normal).normalize();
                kept = normal.dot(back[0], back[1], back[2]) >= PLACE_COSINE && template.cellPixel(camera, hit, pixel)
                        && pixel[0] >= 0f && pixel[1] >= 0f && pixel[0] < template.cellWidth && pixel[1] < template.cellHeight;
                // No edge counted twice: a point an earlier picture's mark already covers is dropped,
                // and so is a line's point beside a stronger line of the same picture (lines come
                // strongest first), so parallel creases merge into one.
                for (int earlier = 0; earlier < coveredPoints + (closed ? 0 : ownStart) && kept; earlier++) {
                    float[] from = earlier < coveredPoints ? covered : pending;
                    int at = (earlier < coveredPoints ? earlier : earlier - coveredPoints) * COVERED_FIELDS;
                    float reach = Math.max(radius, from[at + 3]);
                    kept = hit.distanceSquared(from[at], from[at + 1], from[at + 2]) >= reach * reach;
                }
            }
            if (kept) {
                if ((pendingPoints + 1) * COVERED_FIELDS > pending.length) {
                    pending = Arrays.copyOf(pending, pending.length * 2 + COVERED_FIELDS);
                }
                pending[pendingPoints * COVERED_FIELDS] = hit.x;
                pending[pendingPoints * COVERED_FIELDS + 1] = hit.y;
                pending[pendingPoints * COVERED_FIELDS + 2] = hit.z;
                pending[pendingPoints * COVERED_FIELDS + 3] = radius;
                pendingPoints++;
                piece[length * PLACED_FIELDS] = pixel[0];
                piece[length * PLACED_FIELDS + 1] = pixel[1];
                piece[length * PLACED_FIELDS + 2] = picture[point * 2];
                piece[length * PLACED_FIELDS + 3] = picture[point * 2 + 1];
                length++;
            } else if (!closed && length > 0) {
                pieces.add(Arrays.copyOf(piece, length * PLACED_FIELDS));
                length = 0;
            }
        }
        if (length > 0) {
            pieces.add(Arrays.copyOf(piece, length * PLACED_FIELDS));
        }
        return pieces;
    }

    /**
     * A whole mark in its picture's pixels, marked {@code data-hidden} so the overlay draws it faint
     * under the pieces placed from it: what the picture shows that another picture or the base took.
     *
     * @param tag        polyline or polygon
     * @param attributes the mark's stroke attributes
     * @param xy         the mark in picture pixels, packed xy
     * @return the tag and a line break
     */
    public String unplacedTag(String tag, String attributes, float[] xy) {
        float[] piece = new float[xy.length / 2 * PLACED_FIELDS];
        for (int point = 0; point < xy.length / 2; point++) {
            piece[point * PLACED_FIELDS + 2] = xy[point * 2];
            piece[point * PLACED_FIELDS + 3] = xy[point * 2 + 1];
        }
        return shapeTag(tag, attributes + " data-hidden=\"1\"", -1, piece, 2);
    }

    /**
     * One placed piece as an SVG shape: in the template's sheet units for a cell, in picture pixels
     * for none.
     *
     * @param tag        polyline or polygon
     * @param attributes the shape's attributes
     * @param cell       the template cell, or -1 for picture pixels
     * @param piece      the placed points, {@link #PLACED_FIELDS} floats each
     * @param offset     which pair of the fields to write: 0 the template pixel, 2 the picture pixel
     * @return the tag and a line break
     */
    public String shapeTag(String tag, String attributes, int cell, float[] piece, int offset) {
        int count = piece.length / PLACED_FIELDS;
        float[] xy = new float[count * 2];
        for (int point = 0; point < count; point++) {
            xy[point * 2] = piece[point * PLACED_FIELDS + offset];
            xy[point * 2 + 1] = piece[point * PLACED_FIELDS + offset + 1];
        }
        StringBuilder points = new StringBuilder();
        for (int point = 0; point < count && cell < 0; point++) {
            points.append(point == 0 ? "" : " ").append(String.format(Locale.ROOT, "%.1f,%.1f", xy[point * 2],
                    xy[point * 2 + 1]));
        }
        return "<" + tag + " " + attributes + " points=\"" + (cell < 0 ? points : template.gridPoints(cell, xy, count))
                + "\"/>\n";
    }
}
