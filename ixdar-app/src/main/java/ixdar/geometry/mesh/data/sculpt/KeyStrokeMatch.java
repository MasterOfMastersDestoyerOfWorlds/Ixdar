package ixdar.geometry.mesh.data.sculpt;

import java.util.Locale;

import org.joml.Vector3f;

import ixdar.graphics.cameras.Camera3D;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;

/**
 * Another stroke set graded against a stroke key by location and kind: a stroke matches when its
 * points lie, on average, within the larger radius of a key stroke of its kind (or its mirror copy),
 * giving precision per cell and kind; recall asks the same of each key stroke.
 */
public final class KeyStrokeMatch {

    public static final String[] KINDS = { KeyCandidates.SINK_LAYER, KeyCandidates.RAISE_LAYER,
        KeyCandidates.RIDGE_LAYER, KeyCandidates.VALLEY_LAYER };

    public static final String MATCHED_GROUP = "matched";

    public static final String UNMATCHED_GROUP = "unmatched";

    public static final String KEY_UNMATCHED_GROUP = "key_unmatched";

    /** Axis both sets mirror across, -1 for none. */
    public int symmetryAxis = 0;

    /** The other set's strokes that lifted, by cell then kind. */
    public int[][] otherByCellKind = new int[0][];

    /** Of those, the ones lying on a key stroke of their kind. */
    public int[][] matchedOtherByCellKind = new int[0][];

    /** Strokes of the other set lying on a key stroke of any kind, by cell. */
    public int[] placedOtherByCell = new int[0];

    /** The key's strokes, by cell then kind. */
    public int[][] keyByCellKind = new int[0][];

    /** Of those, the ones the other set's strokes of their kind cover. */
    public int[][] matchedKeyByCellKind = new int[0][];

    /** Silhouette strokes of the other set, which the key has no counterpart for. */
    public int silhouettes;

    /** Strokes of the other set that lifted no point. */
    public int unlifted;

    /** The picture: the other set's own shapes grouped matched and unmatched, then the key's uncovered strokes. */
    public String drawing = "";

    /**
     * Grade another stroke set against a key, both lifted onto the base as stroke_sculpt lifts them,
     * and draw the result over the other set's cameras.
     *
     * @param other    the other set's SVG, lifted from its pixels (or its sidecar's strokes)
     * @param key      the key's strokes
     * @param template the key's template
     * @param base     the base both are held on
     */
    public void match(StrokeSketch other, StrokeAnchors key, StrokeSketch template, MeshTopology base) {
        other.lift(base);
        StrokeSketch keySketch = key.sketch(template, base);
        keySketch.lift(base);
        PackedPoints[] otherLifted = other.liftedXyzByShape;
        PackedPoints[] keyLifted = keySketch.liftedXyzByShape;
        int cells = Math.max(other.viewCameras.length, template.viewCameras.length);
        otherByCellKind = new int[cells][KINDS.length];
        matchedOtherByCellKind = new int[cells][KINDS.length];
        placedOtherByCell = new int[cells];
        keyByCellKind = new int[cells][KINDS.length];
        matchedKeyByCellKind = new int[cells][KINDS.length];
        int[] otherKinds = new int[other.shapes.size()];
        int[] keyKinds = new int[keySketch.shapes.size()];
        for (int set = 0; set < 2; set++) {
            StrokeSketch sketch = set == 0 ? other : keySketch;
            int[] kinds = set == 0 ? otherKinds : keyKinds;
            for (int shape = 0; shape < kinds.length; shape++) {
                String kind = sketch.kindByShape[shape];
                boolean sink = sketch.depthByShape[shape] < 0f;
                kinds[shape] = StrokeSketch.FILL.equals(kind) ? sink ? 0 : 1 : StrokeSketch.RIDGE.equals(kind) ? 2
                        : StrokeSketch.VALLEY.equals(kind) ? 3 : -1;
            }
        }
        StringBuilder matched = new StringBuilder();
        StringBuilder unmatched = new StringBuilder();
        for (int shape = 0; shape < otherKinds.length; shape++) {
            PackedPoints points = otherLifted[shape];
            if (otherKinds[shape] < 0 || points.size() == 0) {
                silhouettes += StrokeSketch.SILHOUETTE.equals(other.kindByShape[shape]) ? 1 : 0;
                unlifted += otherKinds[shape] >= 0 ? 1 : 0;
                continue;
            }
            int cell = other.viewByShape[shape];
            otherByCellKind[cell][otherKinds[shape]]++;
            boolean sameKind = false;
            boolean anyKind = false;
            for (int keyShape = 0; keyShape < keyKinds.length; keyShape++) {
                PackedPoints keyPoints = keyLifted[keyShape];
                if (keyPoints.size() == 0) {
                    continue;
                }
                boolean near = meanNearest(points, keyPoints) <= Math.max(other.radiusByShape[shape],
                        keySketch.radiusByShape[keyShape]);
                anyKind |= near;
                sameKind |= near && keyKinds[keyShape] == otherKinds[shape];
            }
            matchedOtherByCellKind[cell][otherKinds[shape]] += sameKind ? 1 : 0;
            placedOtherByCell[cell] += anyKind ? 1 : 0;
            (sameKind ? matched : unmatched).append(other.shapes.get(shape)).append('\n');
        }
        StringBuilder uncovered = new StringBuilder();
        float[] pixel = new float[2];
        Vector3f position = new Vector3f();
        for (int keyShape = 0; keyShape < keyKinds.length; keyShape++) {
            PackedPoints keyPoints = keyLifted[keyShape];
            if (keyKinds[keyShape] < 0 || keyPoints.size() == 0) {
                continue;
            }
            int cell = keySketch.viewByShape[keyShape];
            keyByCellKind[cell][keyKinds[keyShape]]++;
            PackedPoints union = new PackedPoints();
            for (int shape = 0; shape < otherKinds.length; shape++) {
                PackedPoints covering = otherLifted[shape];
                for (int point = 0; otherKinds[shape] == keyKinds[keyShape] && point < covering.size(); point++) {
                    union.append(covering.get(point, 0), covering.get(point, 1), covering.get(point, 2));
                }
            }
            boolean covered = union.size() > 0 && meanNearest(keyPoints, union) <= keySketch.radiusByShape[keyShape];
            matchedKeyByCellKind[cell][keyKinds[keyShape]] += covered ? 1 : 0;
            if (!covered && cell < other.viewCameras.length) {
                Camera3D camera = other.cellCamera(cell);
                float[] pixels = new float[keyPoints.size() * 2];
                for (int point = 0; point < keyPoints.size(); point++) {
                    other.cellPixel(camera, position.set(keyPoints.get(point, 0), keyPoints.get(point, 1),
                            keyPoints.get(point, 2)), pixel);
                    pixels[point * 2] = pixel[0];
                    pixels[point * 2 + 1] = pixel[1];
                }
                uncovered.append(String.format(Locale.ROOT, "<polyline data-radius=\"%.5f\" points=\"%s\"/>%n",
                        keySketch.radiusByShape[keyShape], other.gridPoints(cell, pixels, pixels.length / 2)));
            }
        }
        String root = StrokeSketch.ROOT_TAG.matcher(other.text).results().map(result -> result.group()).findFirst()
                .orElse("");
        drawing = root + "\n<g id=\"" + MATCHED_GROUP + "\">\n" + matched + "</g>\n<g id=\"" + UNMATCHED_GROUP
                + "\">\n" + unmatched + "</g>\n<g id=\"" + KEY_UNMATCHED_GROUP + "\">\n" + uncovered + "</g>\n</svg>\n";
    }

    /**
     * The mean, over points, of the distance to the nearest of other points or of their mirror image.
     *
     * @param points the points measured
     * @param others the points measured to
     * @return the mean distance
     */
    private float meanNearest(PackedPoints points, PackedPoints others) {
        PackedPoints mirrored = new PackedPoints(others.size());
        for (int point = 0; point < others.size(); point++) {
            Vector3f image = others.at(point);
            if (symmetryAxis >= 0) {
                image.setComponent(symmetryAxis, -image.get(symmetryAxis));
            }
            mirrored.set(point, image);
        }
        float[] direct = SculptStroke.nearestDistances(points, others);
        float[] reflected = SculptStroke.nearestDistances(points, mirrored);
        double total = 0.0;
        for (int point = 0; point < direct.length; point++) {
            total += Math.min(direct[point], reflected[point]);
        }
        return direct.length == 0 ? Float.POSITIVE_INFINITY : (float) (total / direct.length);
    }
}
