package ixdar.geometry.mesh.data.sculpt;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.paths.geodesic.TracedSurfacePath;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * One sculpt stroke under its stable id: its SVG settings, the grid cell whose camera it moves the
 * surface through, and its curve held on the base mesh (a silhouette: its pixels in that cell).
 */
public final class AnchoredStroke {

    public static final Pattern SILHOUETTE_KIND = Pattern.compile("\\bdata-kind\\s*=\\s*\"silhouette\"");

    /** Stable id, the data-id every cell's drawing of the stroke carries. */
    public String id;

    /** The {@code <g id>} the stroke is drawn in, empty for none; organisation only. */
    public String layer = "";

    /** Grid cell the stroke was drawn in, whose camera it sees the surface through. */
    public int cell;

    /** Whether the stroke is a polygon fill, its curve closed. */
    public boolean polygon;

    /** The shape's own attributes (data-kind, data-depth, data-radius, ...), as the SVG writes them. */
    public String attributes = "";

    /** The curve on the base, {@link FaceAnchors#FIELDS} floats per point; empty for a silhouette. */
    public float[] anchors = new float[0];

    /** A silhouette's points in its cell's pixels, packed xy; empty otherwise. */
    public float[] pixels = new float[0];

    /** Cells the last {@link #diff} found points moved or settings changed in. */
    public TreeSet<Integer> editedCells = new TreeSet<>();

    /** Cells the last {@link #diff} found a drawing of the stroke gone from. */
    public TreeSet<Integer> deletedCells = new TreeSet<>();

    /** Whether the last {@link #diff} found the stroke deleted, and nothing else edited. */
    public boolean deleted;

    /** Moved points the last {@link #diff} found, by point index times cell count plus cell: the new pixel. */
    public Map<Integer, float[]> moves = new TreeMap<>();

    /** Settings the last {@link #diff} found edited, null when unchanged. */
    public String editedAttributes;

    /**
     * Compare the stroke's drawings now against those sculpt-sync last wrote, cell by cell: spans
     * gone, points moved, settings changed, and spans copied, which are refused.
     *
     * @param sketch   the layer's SVG, for its cells
     * @param before   the stroke's shapes as last written
     * @param now      its shapes as edited
     * @param refusals receives every refusal with its fix
     */
    public void diff(StrokeSketch sketch, List<String> before, List<String> now, List<String> refusals) {
        editedCells.clear();
        deletedCells.clear();
        moves.clear();
        editedAttributes = null;
        int cells = sketch.viewCameras.length;
        String ownSettings = attributes.trim().replaceAll(SculptSync.WHITESPACE, " ");
        for (int cell = 0; cell < cells; cell++) {
            Map<Integer, float[]> drawn = new HashMap<>();
            int drawnSpans = SculptSync.cellPoints(sketch, before, cell, drawn, null);
            Map<Integer, float[]> edited = new HashMap<>();
            int editedSpans = SculptSync.cellPoints(sketch, now, cell, edited, refusals);
            if (editedSpans > drawnSpans) {
                refusals.add(String.format(Locale.ROOT, "stroke %s is drawn %d times in cell %d, %d more than "
                        + "sculpt-sync wrote: a copied shape keeps its data-id; remove data-id and data-pts from the copy "
                        + "to add it as a new stroke", id, editedSpans, cell, editedSpans - drawnSpans));
            }
            if (editedSpans < drawnSpans) {
                deletedCells.add(cell);
            }
            for (Map.Entry<Integer, float[]> point : edited.entrySet()) {
                float[] was = drawn.get(point.getKey());
                if (was == null) {
                    refusals.add("stroke " + id + " in cell " + cell + " lists point " + point.getKey() + " in data-pts, "
                            + "which sculpt-sync never drew there: restore its data-pts, or remove data-id and data-pts "
                            + "to add the shape as a new stroke");
                } else if (Math.abs(was[0] - point.getValue()[0]) > SculptSync.MOVE_TOLERANCE_PIXELS
                        || Math.abs(was[1] - point.getValue()[1]) > SculptSync.MOVE_TOLERANCE_PIXELS) {
                    editedCells.add(cell);
                    moves.put(point.getKey() * cells + cell, point.getValue());
                }
            }
            for (String shape : now) {
                String settings = SculptSync.settings(shape);
                if (cell == SculptSync.cellOf(shape) && !settings.equals(ownSettings)) {
                    editedCells.add(cell);
                    editedAttributes = settings;
                }
            }
        }
        // Deleting a stroke from every cell, or from one, is one edit; a deletion and a move, or moves
        // in two cells, are two.
        deleted = !deletedCells.isEmpty() && editedCells.isEmpty();
        if (editedCells.size() > 1 || !deletedCells.isEmpty() && !editedCells.isEmpty()) {
            TreeSet<Integer> cellsEdited = new TreeSet<>(editedCells);
            cellsEdited.addAll(deletedCells);
            refusals.add("stroke " + id + " was edited in cells " + cellsEdited + " since the last sync, so neither "
                    + "edit can win: undo it in all but one cell (sync rewrites the others) and sync again");
        }
    }

    /**
     * Whether the stroke is a silhouette, a pair of view rays per point rather than a surface curve.
     *
     * @return true for data-kind="silhouette"
     */
    public boolean silhouette() {
        return SILHOUETTE_KIND.matcher(attributes).find();
    }

    /**
     * How many points the stroke holds.
     *
     * @return anchors for a surface curve, pixels for a silhouette
     */
    public int pointCount() {
        return silhouette() ? pixels.length / 2 : anchors.length / FaceAnchors.FIELDS;
    }

    /**
     * The curve as a path traced on the base: every point inside the face it is anchored to, a
     * polygon's closed.
     *
     * @param base the base mesh the anchors index
     * @return the path, positions on the base
     */
    public TracedSurfacePath curve(MeshTopology base) {
        int count = anchors.length / FaceAnchors.FIELDS;
        double[] positions = new double[count * TracedSurfacePath.COORDINATES_PER_POINT];
        int[] faceIds = new int[count];
        int[] none = new int[count];
        Arrays.fill(none, -1);
        double[] fractions = new double[count];
        Arrays.fill(fractions, -1.0);
        Vector3f position = new Vector3f();
        for (int point = 0; point < count; point++) {
            FaceAnchors.position(base, anchors, point * FaceAnchors.FIELDS, position);
            positions[point * 3] = position.x;
            positions[point * 3 + 1] = position.y;
            positions[point * 3 + 2] = position.z;
            faceIds[point] = (int) anchors[point * FaceAnchors.FIELDS];
        }
        return new TracedSurfacePath(positions, positions, none, none.clone(), faceIds, fractions, count, polygon)
                .measure();
    }
}
