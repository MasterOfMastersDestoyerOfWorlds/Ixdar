package ixdar.geometry.mesh.data.sculpt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import ixdar.geometry.mesh.data.paths.query.TriangleBvh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.graphics.cameras.Camera3D;

/**
 * Keeps a stroke layer's SVG and its base-anchored strokes in step: folds plain-SVG edits made in
 * any cell (moves, adds, deletes) back onto the base, and draws every stroke into every cell that
 * sees it on the finished sculpt, under its id, hidden spans dashed.
 */
public final class SculptSync {

    public static final String ID_ATTRIBUTE = "data-id";

    public static final String CELL_ATTRIBUTE = "data-cell";

    public static final String POINT_INDEX_ATTRIBUTE = "data-pts";

    public static final String HIDDEN_ATTRIBUTE = "data-hidden";

    public static final String HIDDEN_STYLE = " data-hidden=\"true\" stroke-dasharray=\"12 8\"";

    public static final Pattern TOKEN = Pattern.compile("<(polyline|polygon)\\b[^>]*>|<g\\b[^>]*>|</g\\s*>");

    public static final Pattern GROUP_ID = Pattern.compile("^<g\\b[^>]*\\bid\\s*=\\s*\"([^\"]*)\"");

    public static final Pattern WRITTEN_ATTRIBUTES = Pattern.compile(
            "\\s*\\b(?:data-id|data-cell|data-pts|data-hidden|stroke-dasharray|points)\\s*=\\s*\"[^\"]*\"");

    public static final Pattern TAG_ENDS = Pattern.compile("^<\\w+\\s*|\\s*/?>$");

    public static final String WHITESPACE = "\\s+";

    public static final String LINE_BREAK = "\n";

    public static final String GROUP_CLOSE = "</g>";

    public static final float MOVE_TOLERANCE_PIXELS = 0.5f;

    public static final float HIDDEN_SLACK_FRACTION = 0.004f;

    public static final int VISIBLE = 0;

    public static final int HIDDEN = 1;

    public static final int OUTSIDE = 2;

    /** The layer's SVG as a sketch, for its cell cameras. */
    public final StrokeSketch sketch;

    /** The base mesh the strokes are held on. */
    public final MeshTopology base;

    /** The refined surface before the strokes moved it, lying on the base. */
    public final MeshTopology refined;

    /** The finished sculpt, {@link #refined}'s vertices and faces moved. */
    public final MeshTopology sculpted;

    /** Anchors on the base, for points coming back from the sculpt. */
    public final FaceAnchors baseAnchors;

    /** Anchors on the refined surface, to carry base points onto the sculpt. */
    public final FaceAnchors refinedAnchors;

    /** Anchors on the sculpt, for points cast onto it. */
    public final FaceAnchors sculptedAnchors;

    /** Triangle tree over the sculpt, for casting and hiding. */
    public final TriangleBvh sculptedTriangles;

    /** Triangle tree over the base, which new shapes are cast onto. */
    public final TriangleBvh baseTriangles;

    /** Scratch stack for {@link #sculptedTriangles}. */
    public final int[] stack;

    /** Face id on the sculpt of each refined face id: the same dense face. */
    public final int[] sculptedFaceByRefinedFace;

    /** Face id on the refined surface of each sculpt face id. */
    public final int[] refinedFaceBySculptedFace;

    /** What the last {@link #fold} did, one line per stroke it changed. */
    public final List<String> changes = new ArrayList<>();

    /**
     * Index a layer's surfaces for syncing.
     *
     * @param sketch   the layer's SVG, for its cameras
     * @param base     the base the strokes are held on
     * @param refined  the layer's refined surface before any stroke moved it
     * @param sculpted the layer's output, same vertex and face order as {@code refined}
     */
    public SculptSync(StrokeSketch sketch, MeshTopology base, MeshTopology refined, MeshTopology sculpted) {
        this.sketch = sketch;
        this.base = base;
        this.refined = refined;
        this.sculpted = sculpted;
        baseAnchors = new FaceAnchors(base);
        refinedAnchors = refined == base ? baseAnchors : new FaceAnchors(refined);
        sculptedAnchors = sculpted == base ? baseAnchors : new FaceAnchors(sculpted);
        sculptedTriangles = TriangleBvh.over(sculpted, new int[sculpted.triangleCount()]);
        baseTriangles = sculpted == base ? sculptedTriangles : TriangleBvh.over(base, new int[base.triangleCount()]);
        stack = new int[Math.max(1, sculptedTriangles.nodeCount)];
        sculptedFaceByRefinedFace = new int[refined.faceIdBound()];
        refinedFaceBySculptedFace = new int[sculpted.faceIdBound()];
        for (int activeFace = 0; activeFace < refined.faceCount(); activeFace++) {
            sculptedFaceByRefinedFace[refined.faceIdAt(activeFace)] = sculpted.faceIdAt(activeFace);
            refinedFaceBySculptedFace[sculpted.faceIdAt(activeFace)] = refined.faceIdAt(activeFace);
        }
    }

    /**
     * Fold the SVG's edits since the last sync into the strokes: moved points are cast onto the
     * sculpt and held on the base again, a shape with no id is added, an id gone from a cell deleted.
     *
     * @param anchors the layer's strokes, changed in place only when nothing is refused
     * @param svg     the SVG as edited
     * @throws IllegalArgumentException listing every refusal with its fix: a stroke edited in two
     *                                  cells, an unknown or duplicated id, data-pts not matching the
     *                                  points, a point that misses the surface
     */
    public void fold(StrokeAnchors anchors, String svg) {
        changes.clear();
        List<String> refusals = new ArrayList<>();
        Map<String, List<String>> nowById = new LinkedHashMap<>();
        Map<String, List<String>> beforeById = new HashMap<>();
        List<String> added = new ArrayList<>();
        List<String> addedLayers = new ArrayList<>();
        List<String> layerByShape = new ArrayList<>();
        List<String> shapes = groupedShapes(svg, layerByShape);
        for (int index = 0; index < shapes.size(); index++) {
            String shape = shapes.get(index);
            String id = attribute(shape, ID_ATTRIBUTE);
            if (id == null) {
                added.add(shape);
                addedLayers.add(layerByShape.get(index));
            } else {
                nowById.computeIfAbsent(id, unused -> new ArrayList<>()).add(shape);
            }
        }
        for (String shape : groupedShapes(anchors.written, null)) {
            String id = attribute(shape, ID_ATTRIBUTE);
            beforeById.computeIfAbsent(id == null ? "" : id, unused -> new ArrayList<>()).add(shape);
        }
        for (String id : nowById.keySet()) {
            if (anchors.stroke(id) == null) {
                refusals.add("data-id=\"" + id + "\" names no stroke of this layer: remove its data-id and data-pts to "
                        + "add the shape as a new stroke");
            }
        }
        List<AnchoredStroke> kept = new ArrayList<>();
        Set<String> keptIds = new HashSet<>();
        Map<String, String> deletionById = new LinkedHashMap<>();
        Map<AnchoredStroke, float[]> movedAnchors = new LinkedHashMap<>();
        for (AnchoredStroke stroke : anchors.strokes) {
            stroke.diff(sketch, beforeById.getOrDefault(stroke.id, List.of()), nowById.getOrDefault(stroke.id,
                    List.of()), refusals);
            if (stroke.deleted) {
                deletionById.put(stroke.id, stroke.id + ": deleted in cell(s) " + stroke.deletedCells);
                continue;
            }
            kept.add(stroke);
            keptIds.add(stroke.id);
            float[] moved = stroke.silhouette() ? stroke.pixels.clone() : stroke.anchors.clone();
            for (Map.Entry<Integer, float[]> move : stroke.moves.entrySet()) {
                int point = move.getKey() / sketch.viewCameras.length;
                float[] hit = stroke.silhouette() ? move.getValue() : castAll(sculptedTriangles,
                        move.getKey() % sketch.viewCameras.length, move.getValue(), refusals, "point " + point
                                + " of stroke " + stroke.id);
                int fields = stroke.silhouette() ? 2 : FaceAnchors.FIELDS;
                if (hit.length > 0) {
                    System.arraycopy(stroke.silhouette() ? hit : toBase(hit), 0, moved, point * fields, fields);
                }
            }
            movedAnchors.put(stroke, moved);
            changes.addAll(stroke.moves.isEmpty() ? List.of()
                    : List.of(stroke.id + ": moved " + stroke.moves.size() + " point(s)"));
            changes.addAll(stroke.editedAttributes == null ? List.of()
                    : List.of(stroke.id + ": settings now " + stroke.editedAttributes));
        }
        List<AnchoredStroke> created = new ArrayList<>();
        List<String> addedIds = stableIds(added, addedLayers, keptIds);
        for (int index = 0; index < added.size(); index++) {
            String shape = added.get(index);
            AnchoredStroke stroke = new AnchoredStroke();
            stroke.id = addedIds.get(index);
            stroke.layer = addedLayers.get(index);
            stroke.polygon = shape.startsWith("<" + StrokeAnchors.POLYGON);
            stroke.cell = sketch.shapeCell(shape);
            stroke.attributes = settings(shape);
            if (stroke.cell < 0) {
                continue;
            }
            float[] pixels = sketch.cellPixels(shape, stroke.cell);
            if (stroke.silhouette()) {
                stroke.pixels = pixels;
            } else {
                // A new shape is lifted onto the base exactly as stroke_sculpt lifts an SVG with no
                // sidecar, whatever the last sculpt did to the surface under it: cast onto that
                // sculpt, a stroke drawn over an earlier pit lands on the pit's floor or through it.
                StrokeSketch single = StrokeSketch.parse(sketch.text, stroke.id);
                single.shapes = new ArrayList<>(List.of(shape));
                try {
                    single.lift(base);
                } catch (IllegalArgumentException refused) {
                    refusals.add("new stroke in cell " + stroke.cell + ": " + refused.getMessage());
                    continue;
                }
                float[] onBase = stroke.polygon ? castAll(baseTriangles, stroke.cell, pixels, refusals,
                        "the new polygon") : single.liftedXyzByShape[0];
                if (onBase.length == 0) {
                    refusals.add("the new " + (stroke.polygon ? "polygon" : "polyline") + " in cell " + stroke.cell
                            + " lands nowhere on the base its cell sees face-on: move it onto the surface");
                    continue;
                }
                stroke.anchors = new float[onBase.length / 3 * FaceAnchors.FIELDS];
                for (int point = 0; point < onBase.length / 3; point++) {
                    baseAnchors.anchor(onBase[point * 3], onBase[point * 3 + 1], onBase[point * 3 + 2], stroke.anchors,
                            point * FaceAnchors.FIELDS);
                }
            }
            created.add(stroke);
            changes.add(stroke.id + (deletionById.remove(stroke.id) == null ? ": added from cell "
                    : ": redrawn, held on the base anew from cell ") + stroke.cell);
        }
        changes.addAll(deletionById.values());
        if (!refusals.isEmpty()) {
            throw new IllegalArgumentException("sculpt-sync refused, nothing written:" + LINE_BREAK
                    + String.join(LINE_BREAK, refusals));
        }
        for (Map.Entry<AnchoredStroke, float[]> entry : movedAnchors.entrySet()) {
            AnchoredStroke stroke = entry.getKey();
            stroke.attributes = stroke.editedAttributes == null ? stroke.attributes : stroke.editedAttributes;
            if (stroke.silhouette()) {
                stroke.pixels = entry.getValue();
            } else {
                stroke.anchors = entry.getValue();
            }
        }
        kept.addAll(created);
        anchors.strokes = kept;
        anchors.baseVertices = base.vertexCount();
        anchors.baseFaces = base.faceCount();
    }

    /**
     * Draw every stroke into every cell that sees some of it on the sculpt, each drawing under the
     * stroke's id with data-cell and data-pts; hidden spans dashed and marked data-hidden, silhouettes
     * in their own cell only. Layers become {@code <g id>} groups.
     *
     * @param anchors the layer's strokes
     * @return the SVG text, with the layer's cameras on its root
     */
    public String draw(StrokeAnchors anchors) {
        StrokeSketch drawing = StrokeSketch.parse(sketch.text, "sculpt-sync");
        drawing.shapes = new ArrayList<>();
        drawing.shapes.add("<!-- Written by ixdar-cli sculpt-sync from the strokes beside this file: every stroke in "
                + "every cell that sees it, under one data-id; data-pts names each point's index on the stroke and "
                + "dashed data-hidden spans run behind the surface. Move points, add shapes with no data-id, or "
                + "delete one, in any one cell per stroke, then sync again. -->");
        float extent = sculpted.boundsMax(new Vector3f()).distance(sculpted.boundsMin(new Vector3f()));
        Vector3f position = new Vector3f();
        float[] anchor = new float[FaceAnchors.FIELDS];
        String layer = "";
        for (AnchoredStroke stroke : anchors.strokes) {
            if (!stroke.layer.equals(layer)) {
                drawing.shapes.addAll(layer.isEmpty() ? List.of() : List.of(GROUP_CLOSE));
                drawing.shapes.addAll(stroke.layer.isEmpty() ? List.of() : List.of("<g id=\"" + stroke.layer + "\">"));
                layer = stroke.layer;
            }
            int count = stroke.pointCount();
            if (stroke.silhouette()) {
                drawing.shapes.add(span(drawing, stroke, stroke.cell, stroke.pixels, indexRange(count), count, false));
                continue;
            }
            double[] onBase = stroke.curve(base).positions;
            float[] onSculpt = new float[count * 3];
            for (int point = 0; point < count; point++) {
                refinedAnchors.anchor((float) onBase[point * 3], (float) onBase[point * 3 + 1],
                        (float) onBase[point * 3 + 2], anchor, 0);
                anchor[0] = sculptedFaceByRefinedFace[(int) anchor[0]];
                FaceAnchors.position(sculpted, anchor, 0, position);
                onSculpt[point * 3] = position.x;
                onSculpt[point * 3 + 1] = position.y;
                onSculpt[point * 3 + 2] = position.z;
            }
            for (int cell = 0; cell < drawing.viewCameras.length; cell++) {
                Camera3D camera = drawing.cellCamera(cell);
                float[] pixels = new float[count * 2];
                int[] state = new int[count];
                boolean seen = false;
                for (int point = 0; point < count; point++) {
                    position.set(onSculpt[point * 3], onSculpt[point * 3 + 1], onSculpt[point * 3 + 2]);
                    float[] pixel = new float[2];
                    boolean inFront = drawing.cellPixel(camera, position, pixel);
                    pixels[point * 2] = pixel[0];
                    pixels[point * 2 + 1] = pixel[1];
                    // Hidden when the sculpt lies between the point and the eye, past a sliver of slack
                    // for the point's own faces.
                    Vector3f toEye = drawing.orthographic ? new Vector3f(camera.position).sub(camera.target)
                            : new Vector3f(camera.position).sub(position);
                    float[] origin = { position.x, position.y, position.z };
                    float[] direction = { toEye.x, toEye.y, toEye.z };
                    float slack = HIDDEN_SLACK_FRACTION * extent / toEye.length();
                    for (int axis = 0; axis < 3; axis++) {
                        origin[axis] += direction[axis] * slack;
                    }
                    state[point] = !inFront || pixel[0] < 0f || pixel[1] < 0f || pixel[0] >= drawing.cellWidth
                            || pixel[1] >= drawing.cellHeight ? OUTSIDE
                                    : sculptedTriangles.nearestHit(origin, direction, drawing.orthographic
                                            ? Double.POSITIVE_INFINITY : 1.0, stack) >= 0 ? HIDDEN : VISIBLE;
                    seen |= state[point] == VISIBLE;
                }
                if (!seen) {
                    continue;
                }
                boolean whole = stroke.polygon && Arrays.stream(state).allMatch(value -> value == VISIBLE);
                int segments = stroke.polygon && !whole ? count : count - 1;
                int[] order = new int[count + 1];
                int length = 0;
                boolean spanHidden = false;
                for (int segment = 0; segment < segments || whole && segment == 0; segment++) {
                    int from = segment;
                    int to = (segment + 1) % count;
                    if (whole) {
                        drawing.shapes.add(span(drawing, stroke, cell, pixels, indexRange(count), count, false)
                                .replaceFirst("^<polyline", "<polygon"));
                        break;
                    }
                    boolean outside = state[from] == OUTSIDE || state[to] == OUTSIDE;
                    boolean hidden = state[from] == HIDDEN || state[to] == HIDDEN;
                    if (length > 0 && (outside || hidden != spanHidden)) {
                        drawing.shapes.add(span(drawing, stroke, cell, pixels, order, length, spanHidden));
                        length = 0;
                    }
                    if (!outside) {
                        order[length == 0 ? length++ : length - 1] = from;
                        order[length++] = to;
                        spanHidden = hidden;
                    }
                }
                if (length > 0) {
                    drawing.shapes.add(span(drawing, stroke, cell, pixels, order, length, spanHidden));
                }
            }
        }
        drawing.shapes.addAll(layer.isEmpty() ? List.of() : List.of(GROUP_CLOSE));
        return drawing.toSvg();
    }

    /**
     * Points on the sculpt held on the base: each located on the sculpt, carried to the same place
     * on the refined surface, which lies on the base, and anchored there.
     *
     * @param onSculpt packed xyz on {@link #sculpted}
     * @return {@link FaceAnchors#FIELDS} floats per point, on {@link #base}
     */
    private float[] toBase(float[] onSculpt) {
        float[] anchors = new float[onSculpt.length / 3 * FaceAnchors.FIELDS];
        Vector3f position = new Vector3f();
        for (int point = 0; point < onSculpt.length / 3; point++) {
            int at = point * FaceAnchors.FIELDS;
            sculptedAnchors.anchor(onSculpt[point * 3], onSculpt[point * 3 + 1], onSculpt[point * 3 + 2], anchors, at);
            anchors[at] = refinedFaceBySculptedFace[(int) anchors[at]];
            FaceAnchors.position(refined, anchors, at, position);
            baseAnchors.anchor(position.x, position.y, position.z, anchors, at);
        }
        return anchors;
    }

    /**
     * Cast cell pixels through the cell's camera onto a surface: the sculpt for a moved point, the
     * base for a new shape.
     *
     * @param surface  the surface's triangle tree
     * @param cell     the cell
     * @param pixels   packed xy from the cell's top left
     * @param refusals receives a refusal per pixel that misses
     * @param what     what the pixels are, for the refusal
     * @return packed xyz of the hits, empty when any missed
     */
    private float[] castAll(TriangleBvh surface, int cell, float[] pixels, List<String> refusals, String what) {
        Camera3D camera = sketch.cellCamera(cell);
        float[] hits = new float[pixels.length / 2 * 3];
        float[] origin = new float[3];
        float[] direction = new float[3];
        int[] surfaceStack = new int[Math.max(1, surface.nodeCount)];
        for (int point = 0; point < pixels.length / 2; point++) {
            camera.rayThroughPixel(new Matrix4f(), pixels[point * 2], pixels[point * 2 + 1], sketch.cellWidth,
                    sketch.cellHeight, origin, direction);
            if (surface.nearestHit(origin, direction, Double.POSITIVE_INFINITY, surfaceStack) < 0) {
                refusals.add(String.format(Locale.ROOT, "%s in cell %d: pixel %.1f,%.1f misses the surface; move it "
                        + "onto the surface", what, cell, pixels[point * 2], pixels[point * 2 + 1]));
                return new float[0];
            }
            for (int axis = 0; axis < 3; axis++) {
                hits[point * 3 + axis] = origin[axis] + (float) surface.hitParameter * direction[axis];
            }
        }
        return hits;
    }

    /**
     * One drawn span of a stroke in a cell: a polyline under the stroke's id, its points' indices on
     * the stroke in data-pts.
     *
     * @param drawing the sketch whose cells place the points
     * @param stroke  the stroke
     * @param cell    the cell
     * @param pixels  every point of the stroke in the cell's pixels, packed xy
     * @param order   the span's point indices
     * @param length  how many of {@code order} are in use
     * @param hidden  whether the span runs behind the surface
     * @return the polyline tag
     */
    private static String span(StrokeSketch drawing, AnchoredStroke stroke, int cell, float[] pixels, int[] order,
            int length, boolean hidden) {
        float[] spanPixels = new float[length * 2];
        StringBuilder indices = new StringBuilder();
        for (int index = 0; index < length; index++) {
            spanPixels[index * 2] = pixels[order[index] * 2];
            spanPixels[index * 2 + 1] = pixels[order[index] * 2 + 1];
            indices.append(index == 0 ? "" : " ").append(order[index]);
        }
        return String.format(Locale.ROOT, "<polyline %s=\"%s\" %s=\"%d\" %s=\"%s\" %s%s points=\"%s\"/>", ID_ATTRIBUTE,
                stroke.id, CELL_ATTRIBUTE, cell, POINT_INDEX_ATTRIBUTE, indices, stroke.attributes,
                hidden ? HIDDEN_STYLE : "", drawing.gridPoints(cell, spanPixels, length));
    }

    /**
     * The stable ids of an SVG's shapes with no data-id, in document order: the {@code <g id>} and
     * the shape's place among the group's id-less shapes ({@code eyes-2}), {@code s<n>} outside any
     * group, passing over ids still held. A drawing regenerated without ids gets the same ids again.
     *
     * @param svg  the SVG text
     * @param held ids of strokes the SVG still draws, which no new shape takes
     * @return one id per id-less shape
     */
    public static List<String> stableIds(String svg, Set<String> held) {
        List<String> layerByShape = new ArrayList<>();
        List<String> idless = new ArrayList<>();
        List<String> layers = new ArrayList<>();
        List<String> shapes = groupedShapes(svg, layerByShape);
        for (int index = 0; index < shapes.size(); index++) {
            if (attribute(shapes.get(index), ID_ATTRIBUTE) == null) {
                idless.add(shapes.get(index));
                layers.add(layerByShape.get(index));
            }
        }
        return stableIds(idless, layers, held);
    }

    /**
     * The stable ids of id-less shapes, as {@link #stableIds(String, Set)} gives them.
     *
     * @param shapes the id-less shapes, in document order
     * @param layers each one's {@code <g id>}, empty for none
     * @param held   ids no new shape takes
     * @return one id per shape
     */
    private static List<String> stableIds(List<String> shapes, List<String> layers, Set<String> held) {
        Set<String> taken = new HashSet<>(held);
        Map<String, Integer> placeByPrefix = new HashMap<>();
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < shapes.size(); index++) {
            String layer = layers.get(index);
            String prefix = layer.isEmpty() ? StrokeAnchors.ID_PREFIX : layer + StrokeAnchors.LAYER_ID_SEPARATOR;
            int place = placeByPrefix.getOrDefault(prefix, 0) + 1;
            while (taken.contains(prefix + place)) {
                place++;
            }
            placeByPrefix.put(prefix, place);
            taken.add(prefix + place);
            ids.add(prefix + place);
        }
        return ids;
    }

    /**
     * The shapes of an SVG in document order, comments dropped, and the {@code <g id>} each sits in.
     *
     * @param svg          the SVG text
     * @param layerByShape receives each shape's enclosing group id, empty for none, or null
     * @return the shape tags
     */
    private static List<String> groupedShapes(String svg, List<String> layerByShape) {
        List<String> shapes = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        Matcher token = TOKEN.matcher(StrokeSketch.COMMENT.matcher(svg).replaceAll(""));
        while (token.find()) {
            String tag = token.group();
            Matcher group = GROUP_ID.matcher(tag);
            if (tag.startsWith("</g")) {
                groups.remove(groups.size() - 1);
            } else if (tag.startsWith("<g")) {
                groups.add(group.find() ? group.group(1) : "");
            } else {
                shapes.add(tag);
                if (layerByShape != null) {
                    layerByShape.add(groups.isEmpty() ? "" : groups.get(groups.size() - 1));
                }
            }
        }
        return shapes;
    }

    /**
     * The points the spans of a stroke drawn in one cell carry, by their index on the stroke.
     *
     * @param layer    the layer's SVG, for its cells
     * @param spans    the stroke's shapes, every cell's
     * @param cell     the cell to read
     * @param byIndex  receives each index's cell pixel
     * @param refusals receives a refusal per span whose data-pts does not match its points, or null
     * @return how many spans the cell holds
     */
    public static int cellPoints(StrokeSketch layer, List<String> spans, int cell, Map<Integer, float[]> byIndex,
            List<String> refusals) {
        int count = 0;
        for (String span : spans) {
            if (cellOf(span) != cell) {
                continue;
            }
            count++;
            float[] pixels = layer.cellPixels(span, cell);
            String indexText = attribute(span, POINT_INDEX_ATTRIBUTE);
            String[] indices = indexText == null || indexText.isBlank() ? new String[0]
                    : indexText.trim().split(WHITESPACE);
            if (indices.length != pixels.length / 2) {
                if (refusals != null) {
                    refusals.add("stroke " + attribute(span, ID_ATTRIBUTE) + " in cell " + cell + " has " + pixels.length / 2
                            + " points but data-pts lists " + indices.length + ": move points without adding or "
                            + "removing any, or remove data-id and data-pts to make the shape a new stroke");
                }
                continue;
            }
            for (int index = 0; index < indices.length; index++) {
                byIndex.put(Integer.parseInt(indices[index]), new float[] { pixels[index * 2], pixels[index * 2 + 1] });
            }
        }
        return count;
    }

    /**
     * The cell a written span says it is drawn in.
     *
     * @param span the span's tag
     * @return its data-cell, -1 without one
     */
    public static int cellOf(String span) {
        String cell = attribute(span, CELL_ATTRIBUTE);
        return cell == null ? -1 : Integer.parseInt(cell.trim());
    }

    /**
     * A shape's own settings: its attributes less the points and what sculpt-sync writes, whitespace
     * collapsed.
     *
     * @param shape the shape's tag
     * @return the settings text
     */
    public static String settings(String shape) {
        return TAG_ENDS.matcher(WRITTEN_ATTRIBUTES.matcher(shape).replaceAll("")).replaceAll("").trim()
                .replaceAll(WHITESPACE, " ");
    }

    /**
     * One attribute of a tag.
     *
     * @param tag  the tag
     * @param name the attribute's name
     * @return its value, or null
     */
    private static String attribute(String tag, String name) {
        Matcher value = Pattern.compile("\\b" + name + "\\s*=\\s*\"([^\"]*)\"").matcher(tag);
        return value.find() ? value.group(1) : null;
    }

    /**
     * The indices 0 to count - 1.
     *
     * @param count how many
     * @return the indices
     */
    private static int[] indexRange(int count) {
        int[] indices = new int[count];
        Arrays.setAll(indices, index -> index);
        return indices;
    }
}
