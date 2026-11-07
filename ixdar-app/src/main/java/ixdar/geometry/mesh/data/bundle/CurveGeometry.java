package ixdar.geometry.mesh.data.bundle;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import org.joml.Vector3f;

/**
 * The one curve type: packed xyz polylines with a closed flag and optional float attributes per
 * curve and per point. A closed curve stores each point once; picture outlines lie in z = 0.
 */
public final class CurveGeometry {

    public static final String SLOT = "_curve";

    public static final float MIN_SEGMENT_LENGTH = 1e-20f;

    public static final float MIN_RESAMPLED_SEGMENT_LENGTH = 1e-10f;

    public static final int XYZ = 3;

    public static final int XY = 2;

    public static final String POSITIONS = "positions";

    /** Packed xyz of every point of every curve. */
    public final float[] positions;

    /** {@code curveOffsets[k]} is the first point index of curve k; the last entry is the point count. */
    public final int[] curveOffsets;

    /** Per curve, whether its last point joins back to its first. */
    public final boolean[] closed;

    /** Per-point attributes by name, one value per point. */
    public final Map<String, float[]> pointAttributes;

    /** Per-curve attributes by name, one value per curve. */
    public final Map<String, float[]> curveAttributes;

    /**
     * Wrap the arrays directly (no defensive copy); the attribute maps are copied, not their arrays.
     *
     * @param positions       packed xyz; length must be divisible by 3
     * @param curveOffsets    curve start offsets; at least 2 entries (one curve)
     * @param closed          one flag per curve
     * @param pointAttributes per-point attributes, each one value per point
     * @param curveAttributes per-curve attributes, each one value per curve
     * @throws IllegalArgumentException if an array is null or has the wrong length
     */
    public CurveGeometry(float[] positions, int[] curveOffsets, boolean[] closed, Map<String, float[]> pointAttributes,
            Map<String, float[]> curveAttributes) {
        if (positions == null || positions.length % XYZ != 0) {
            throw new IllegalArgumentException(POSITIONS);
        }
        if (curveOffsets == null || curveOffsets.length < 2) {
            throw new IllegalArgumentException("curveOffsets");
        }
        if (closed == null || closed.length != curveOffsets.length - 1) {
            throw new IllegalArgumentException("closed");
        }
        for (float[] values : pointAttributes.values()) {
            if (values.length != positions.length / XYZ) {
                throw new IllegalArgumentException("pointAttributes");
            }
        }
        for (float[] values : curveAttributes.values()) {
            if (values.length != closed.length) {
                throw new IllegalArgumentException("curveAttributes");
            }
        }
        this.positions = positions;
        this.curveOffsets = curveOffsets;
        this.closed = closed;
        this.pointAttributes = Collections.unmodifiableMap(new TreeMap<>(pointAttributes));
        this.curveAttributes = Collections.unmodifiableMap(new TreeMap<>(curveAttributes));
    }

    /**
     * Open curves without attributes over the arrays (no defensive copy).
     *
     * @param positions    packed xyz; length must be divisible by 3
     * @param curveOffsets curve start offsets; at least 2 entries (one curve)
     * @throws IllegalArgumentException if either array is null or has the wrong shape
     */
    public CurveGeometry(float[] positions, int[] curveOffsets) {
        this(positions, curveOffsets, new boolean[Math.max(0, curveOffsets == null ? 0 : curveOffsets.length - 1)],
                Map.of(), Map.of());
    }

    /**
     * Build a single-curve geometry by copying {@code positions} into a fresh array.
     *
     * @param positions packed xyz of the polyline, a closed one's first point not repeated
     * @param closed    whether the last point joins back to the first
     * @return geometry with one curve covering all points
     */
    public static CurveGeometry singlePolyline(float[] positions, boolean closed) {
        return new CurveGeometry(Arrays.copyOf(positions, positions.length),
                new int[] { 0, positions.length / XYZ }, new boolean[] { closed }, Map.of(), Map.of());
    }

    /**
     * A single curve in the z = 0 plane, such as a picture outline in pixel units.
     *
     * @param xy     packed x then y, a closed curve's first point not repeated
     * @param closed whether the last point joins back to the first
     * @return geometry with one curve covering all points
     */
    public static CurveGeometry planar(float[] xy, boolean closed) {
        float[] xyz = new float[xy.length / XY * XYZ];
        for (int point = 0; point < xy.length / XY; point++) {
            xyz[point * XYZ] = xy[point * XY];
            xyz[point * XYZ + 1] = xy[point * XY + 1];
        }
        return new CurveGeometry(xyz, new int[] { 0, xy.length / XY }, new boolean[] { closed }, Map.of(), Map.of());
    }

    /**
     * A single closed curve in the z = 0 plane from a loop drawn with its first point repeated last,
     * the form SVG polylines and picture outlines are written in.
     *
     * @param drawnXy packed x then y, its last point a copy of its first
     * @return geometry with one closed curve, the repeat dropped
     */
    public static CurveGeometry planarLoop(float[] drawnXy) {
        return planar(Arrays.copyOf(drawnXy, Math.max(0, drawnXy.length - XY)), true);
    }

    /**
     * Number of polylines packed in this geometry.
     *
     * @return {@code curveOffsets.length - 1}
     */
    public int curveCount() {
        return curveOffsets.length - 1;
    }

    /**
     * Total number of 3D points across all curves.
     *
     * @return {@code positions.length / 3}
     */
    public int pointCount() {
        return positions.length / XYZ;
    }

    /**
     * Index of a curve's first point among all points.
     *
     * @param curve curve index
     * @return the point index, into {@link #positions} times three
     */
    public int firstPoint(int curve) {
        return curveOffsets[curve];
    }

    /**
     * How many points one curve has.
     *
     * @param curve curve index
     * @return its point count, a closed curve's first point counted once
     */
    public int pointCount(int curve) {
        return curveOffsets[curve + 1] - curveOffsets[curve];
    }

    /**
     * A copy of one curve's points.
     *
     * @param curve curve index
     * @return packed xyz, {@code 3 * pointCount(curve)} floats
     */
    public float[] curvePositions(int curve) {
        return Arrays.copyOfRange(positions, curveOffsets[curve] * XYZ, curveOffsets[curve + 1] * XYZ);
    }

    /**
     * One curve's points as drawn: a closed curve's first point written again last, the form GL
     * line strips and SVG polylines take.
     *
     * @param curve curve index
     * @return packed xyz
     */
    public float[] drawnPositions(int curve) {
        int points = pointCount(curve);
        float[] drawn = Arrays.copyOf(curvePositions(curve), (points + (closed[curve] && points > 0 ? 1 : 0)) * XYZ);
        if (drawn.length > points * XYZ) {
            System.arraycopy(drawn, 0, drawn, points * XYZ, XYZ);
        }
        return drawn;
    }

    /**
     * One picture-plane curve's x and y as drawn, a closed curve's first point written again last.
     *
     * @param curve curve index
     * @return packed x then y; z is dropped
     */
    public float[] drawnPlanar(int curve) {
        float[] drawn = drawnPositions(curve);
        float[] xy = new float[drawn.length / XYZ * XY];
        for (int point = 0; point < drawn.length / XYZ; point++) {
            xy[point * XY] = drawn[point * XYZ];
            xy[point * XY + 1] = drawn[point * XYZ + 1];
        }
        return xy;
    }

    /**
     * The same curves, flags and attributes over moved points.
     *
     * @param movedPositions packed xyz, as many points as this geometry has
     * @return a new geometry sharing this one's offsets, flags and attribute arrays
     * @throws IllegalArgumentException if the point count differs
     */
    public CurveGeometry withPositions(float[] movedPositions) {
        if (movedPositions.length != positions.length) {
            throw new IllegalArgumentException(POSITIONS);
        }
        return new CurveGeometry(movedPositions, curveOffsets, closed, pointAttributes, curveAttributes);
    }

    /**
     * Every curve resampled to {@code samples} points evenly spaced by arc length, per-point
     * attributes interpolated with the positions and per-curve ones kept.
     *
     * @param samples points per curve; a curve without points stays empty
     * @return a new geometry with the same curve count and closed flags
     */
    public CurveGeometry resampled(int samples) {
        int curves = curveCount();
        int[] offsets = new int[curves + 1];
        for (int curve = 0; curve < curves; curve++) {
            offsets[curve + 1] = offsets[curve] + (pointCount(curve) == 0 ? 0 : samples);
        }
        int[] segment = new int[samples];
        float[] along = new float[samples];
        float[] placed = new float[offsets[curves] * XYZ];
        Map<String, float[]> placedAttributes = new TreeMap<>();
        for (Map.Entry<String, float[]> attribute : pointAttributes.entrySet()) {
            placedAttributes.put(attribute.getKey(), new float[offsets[curves]]);
        }
        for (int curve = 0; curve < curves; curve++) {
            int points = pointCount(curve);
            if (points == 0) {
                continue;
            }
            // Where each sample falls: along of the way from point segment to the next. Open, the
            // first and last samples are the ends exactly; closed, the closing segment counts.
            int segments = closed[curve] ? points : points - 1;
            float[] lengthBefore = new float[segments + 1];
            for (int span = 0; span < segments; span++) {
                int from = (firstPoint(curve) + span) * XYZ;
                int to = (firstPoint(curve) + (span + 1) % points) * XYZ;
                double squared = 0.0;
                for (int axis = 0; axis < XYZ; axis++) {
                    float delta = positions[to + axis] - positions[from + axis];
                    squared += delta * delta;
                }
                lengthBefore[span + 1] = lengthBefore[span] + (float) Math.sqrt(squared);
            }
            float total = lengthBefore[segments];
            int span = 0;
            for (int sample = 0; sample < samples; sample++) {
                boolean end = !closed[curve] && sample == samples - 1 && samples > 1;
                if (segments == 0 || sample == 0 || end) {
                    segment[sample] = end ? points - 1 : 0;
                    along[sample] = 0f;
                    continue;
                }
                float target = closed[curve] ? total * sample / samples : total * sample / (samples - 1);
                while (span < segments - 1 && lengthBefore[span + 1] < target) {
                    span++;
                }
                float spanLength = lengthBefore[span + 1] - lengthBefore[span];
                segment[sample] = span;
                along[sample] = spanLength < MIN_RESAMPLED_SEGMENT_LENGTH ? 0f
                        : (target - lengthBefore[span]) / spanLength;
            }
            System.arraycopy(interpolated(positions, firstPoint(curve), points, XYZ, segment, along), 0, placed,
                    offsets[curve] * XYZ, samples * XYZ);
            for (Map.Entry<String, float[]> attribute : pointAttributes.entrySet()) {
                System.arraycopy(interpolated(attribute.getValue(), firstPoint(curve), points, 1, segment, along), 0,
                        placedAttributes.get(attribute.getKey()), offsets[curve], samples);
            }
        }
        return new CurveGeometry(placed, offsets, closed.clone(), placedAttributes, curveAttributes);
    }

    /**
     * Every segment split into {@code ceil(length / spacing)} equal pieces, every input point kept;
     * a segment shorter than {@link #MIN_SEGMENT_LENGTH} adds nothing. Attributes follow as in
     * {@link #resampled}; a curve with fewer than two points is dropped to no points.
     *
     * @param spacing longest piece a segment is split into
     * @return a new geometry with the same curve count and closed flags
     */
    public CurveGeometry subdivided(float spacing) {
        int curves = curveCount();
        int[] offsets = new int[curves + 1];
        int[][] steps = new int[curves][];
        Vector3f from = new Vector3f();
        Vector3f to = new Vector3f();
        for (int curve = 0; curve < curves; curve++) {
            int points = pointCount(curve);
            int placedCount = 0;
            if (points >= 2) {
                int segments = closed[curve] ? points : points - 1;
                steps[curve] = new int[segments];
                for (int segment = 0; segment < segments; segment++) {
                    int start = (firstPoint(curve) + segment) * XYZ;
                    int end = (firstPoint(curve) + (segment + 1) % points) * XYZ;
                    from.set(positions[start], positions[start + 1], positions[start + 2]);
                    to.set(positions[end], positions[end + 1], positions[end + 2]);
                    float length = from.distance(to);
                    steps[curve][segment] = length < MIN_SEGMENT_LENGTH ? 0
                            : Math.max(1, (int) Math.ceil(length / spacing));
                    placedCount += steps[curve][segment];
                }
                // The first point, then each segment's pieces; a closed curve's last piece ends on its first point.
                placedCount += closed[curve] ? 0 : 1;
            }
            offsets[curve + 1] = offsets[curve] + placedCount;
        }
        float[] placed = new float[offsets[curves] * XYZ];
        Map<String, float[]> placedAttributes = new TreeMap<>();
        for (Map.Entry<String, float[]> attribute : pointAttributes.entrySet()) {
            placedAttributes.put(attribute.getKey(), new float[offsets[curves]]);
        }
        for (int curve = 0; curve < curves; curve++) {
            int placedCount = offsets[curve + 1] - offsets[curve];
            if (steps[curve] == null || placedCount == 0) {
                continue;
            }
            int[] segment = new int[placedCount];
            float[] along = new float[placedCount];
            int written = 1;
            for (int span = 0; span < steps[curve].length; span++) {
                for (int piece = 1; piece <= steps[curve][span] && written < placedCount; piece++) {
                    segment[written] = span;
                    along[written++] = piece / (float) steps[curve][span];
                }
            }
            System.arraycopy(interpolated(positions, firstPoint(curve), pointCount(curve), XYZ, segment, along), 0,
                    placed, offsets[curve] * XYZ, placedCount * XYZ);
            for (Map.Entry<String, float[]> attribute : pointAttributes.entrySet()) {
                System.arraycopy(interpolated(attribute.getValue(), firstPoint(curve), pointCount(curve), 1, segment,
                        along), 0, placedAttributes.get(attribute.getKey()), offsets[curve], placedCount);
            }
        }
        return new CurveGeometry(placed, offsets, closed.clone(), placedAttributes, curveAttributes);
    }

    /**
     * Every curve simplified by Douglas-Peucker (Cartographica 1973), its ends kept: the point
     * farthest from the chord between kept ends is kept while beyond the tolerance, recursively. A
     * closed curve is simplified as its drawn loop. Kept points keep their attribute values.
     *
     * @param tolerance largest distance a dropped point may lie from the kept polyline
     * @return a new geometry with the same curve count and closed flags
     */
    public CurveGeometry simplified(float tolerance) {
        int curves = curveCount();
        boolean[] kept = new boolean[pointCount()];
        int[] offsets = new int[curves + 1];
        for (int curve = 0; curve < curves; curve++) {
            int points = pointCount(curve);
            float[] loop = drawnPositions(curve);
            int loopPoints = loop.length / XYZ;
            boolean[] keep = new boolean[loopPoints];
            if (loopPoints < 3) {
                Arrays.fill(keep, true);
            } else {
                keep[0] = true;
                keep[loopPoints - 1] = true;
                int[] ranges = new int[2 * loopPoints];
                int pending = 0;
                ranges[pending++] = 0;
                ranges[pending++] = loopPoints - 1;
                while (pending > 0) {
                    int last = ranges[--pending];
                    int first = ranges[--pending];
                    double chordSquared = 0.0;
                    for (int axis = 0; axis < XYZ; axis++) {
                        double chord = loop[last * XYZ + axis] - loop[first * XYZ + axis];
                        chordSquared += chord * chord;
                    }
                    float farthest = 0f;
                    int farthestPoint = -1;
                    for (int point = first + 1; point < last; point++) {
                        double dot = 0.0;
                        for (int axis = 0; axis < XYZ; axis++) {
                            dot += (loop[point * XYZ + axis] - loop[first * XYZ + axis])
                                    * (double) (loop[last * XYZ + axis] - loop[first * XYZ + axis]);
                        }
                        double along = chordSquared == 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, dot / chordSquared));
                        double squared = 0.0;
                        for (int axis = 0; axis < XYZ; axis++) {
                            double offset = loop[point * XYZ + axis] - loop[first * XYZ + axis]
                                    - along * (loop[last * XYZ + axis] - loop[first * XYZ + axis]);
                            squared += offset * offset;
                        }
                        float distance = (float) Math.sqrt(squared);
                        if (distance > farthest) {
                            farthest = distance;
                            farthestPoint = point;
                        }
                    }
                    if (farthestPoint >= 0 && farthest > tolerance) {
                        keep[farthestPoint] = true;
                        ranges[pending++] = first;
                        ranges[pending++] = farthestPoint;
                        ranges[pending++] = farthestPoint;
                        ranges[pending++] = last;
                    }
                }
            }
            int keptCount = 0;
            for (int point = 0; point < points; point++) {
                kept[firstPoint(curve) + point] = keep[point];
                keptCount += keep[point] ? 1 : 0;
            }
            offsets[curve + 1] = offsets[curve] + keptCount;
        }
        float[] keptPositions = new float[offsets[curves] * XYZ];
        Map<String, float[]> keptAttributes = new TreeMap<>();
        for (Map.Entry<String, float[]> attribute : pointAttributes.entrySet()) {
            keptAttributes.put(attribute.getKey(), new float[offsets[curves]]);
        }
        int written = 0;
        for (int point = 0; point < kept.length; point++) {
            if (!kept[point]) {
                continue;
            }
            System.arraycopy(positions, point * XYZ, keptPositions, written * XYZ, XYZ);
            for (Map.Entry<String, float[]> attribute : pointAttributes.entrySet()) {
                keptAttributes.get(attribute.getKey())[written] = attribute.getValue()[point];
            }
            written++;
        }
        return new CurveGeometry(keptPositions, offsets, closed.clone(), keptAttributes, curveAttributes);
    }

    /**
     * Values carried on one curve's points, read at sample placements: a copy of the point where
     * {@code along} is 0, else linear toward the next point, the last wrapping to the first.
     *
     * @param values  the carried values, {@code stride} floats per point
     * @param first   index of the curve's first point in {@code values}
     * @param points  how many points the curve has
     * @param stride  floats per point
     * @param segment per sample, the curve-local index of the point it starts from
     * @param along   per sample, the fraction toward the next point
     * @return {@code segment.length * stride} floats
     */
    private static float[] interpolated(float[] values, int first, int points, int stride, int[] segment,
            float[] along) {
        float[] result = new float[segment.length * stride];
        for (int sample = 0; sample < segment.length; sample++) {
            int out = sample * stride;
            int from = (first + segment[sample]) * stride;
            if (along[sample] == 0f) {
                System.arraycopy(values, from, result, out, stride);
                continue;
            }
            int to = (first + (segment[sample] + 1) % points) * stride;
            for (int axis = 0; axis < stride; axis++) {
                result[out + axis] = values[from + axis] + along[sample] * (values[to + axis] - values[from + axis]);
            }
        }
        return result;
    }
}
