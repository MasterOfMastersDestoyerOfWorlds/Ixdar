package ixdar.geometry.mesh.data.bundle;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.TriangleGeometry;

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

    /** Every point of every curve, curve by curve. */
    public final PackedPoints positions;

    /** {@code curveOffsets[k]} is the first point index of curve k; the last entry is the point count. */
    public final int[] curveOffsets;

    /** Per curve, whether its last point joins back to its first. */
    public final boolean[] closed;

    /** Per-point attributes by name, one value per point. */
    public final Map<String, float[]> pointAttributes;

    /** Per-curve attributes by name, one value per curve. */
    public final Map<String, float[]> curveAttributes;

    /**
     * Hold the points and arrays directly (no defensive copy); the attribute maps are copied, not
     * their arrays.
     *
     * @param positions       every point of every curve
     * @param curveOffsets    curve start offsets; at least 2 entries (one curve)
     * @param closed          one flag per curve
     * @param pointAttributes per-point attributes, each one value per point
     * @param curveAttributes per-curve attributes, each one value per curve
     * @throws IllegalArgumentException if an argument is null or has the wrong length
     */
    public CurveGeometry(PackedPoints positions, int[] curveOffsets, boolean[] closed,
            Map<String, float[]> pointAttributes, Map<String, float[]> curveAttributes) {
        if (positions == null) {
            throw new IllegalArgumentException(POSITIONS);
        }
        if (curveOffsets == null || curveOffsets.length < 2) {
            throw new IllegalArgumentException("curveOffsets");
        }
        if (closed == null || closed.length != curveOffsets.length - 1) {
            throw new IllegalArgumentException("closed");
        }
        for (float[] values : pointAttributes.values()) {
            if (values.length != positions.size()) {
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
     * Open curves without attributes over the points (no defensive copy).
     *
     * @param positions    every point of every curve
     * @param curveOffsets curve start offsets; at least 2 entries (one curve)
     * @throws IllegalArgumentException if an argument is null or has the wrong shape
     */
    public CurveGeometry(PackedPoints positions, int[] curveOffsets) {
        this(positions, curveOffsets, new boolean[Math.max(0, curveOffsets == null ? 0 : curveOffsets.length - 1)],
                Map.of(), Map.of());
    }

    /**
     * A single-curve geometry over {@code positions} (no defensive copy).
     *
     * @param positions the polyline's points, a closed one's first point not repeated
     * @param closed    whether the last point joins back to the first
     * @return geometry with one curve covering all points
     */
    public static CurveGeometry singlePolyline(PackedPoints positions, boolean closed) {
        return new CurveGeometry(positions, new int[] { 0, positions.size() }, new boolean[] { closed }, Map.of(),
                Map.of());
    }

    /**
     * A single curve in the z = 0 plane, such as a picture outline in pixel units.
     *
     * @param xy     packed x then y, a closed curve's first point not repeated
     * @param closed whether the last point joins back to the first
     * @return geometry with one curve covering all points
     */
    public static CurveGeometry planar(float[] xy, boolean closed) {
        PackedPoints points = new PackedPoints(xy.length / XY);
        for (int point = 0; point < points.size(); point++) {
            points.set(point, xy[point * XY], xy[point * XY + 1], 0f);
        }
        return new CurveGeometry(points, new int[] { 0, points.size() }, new boolean[] { closed }, Map.of(),
                Map.of());
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
     * @return {@code positions.size()}
     */
    public int pointCount() {
        return positions.size();
    }

    /**
     * Index of a curve's first point among all points.
     *
     * @param curve curve index
     * @return the point index into {@link #positions}
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
     * @return {@code pointCount(curve)} new points
     */
    public PackedPoints curvePositions(int curve) {
        return positions.slice(curveOffsets[curve], pointCount(curve)).copy();
    }

    /**
     * One curve's points as drawn: a closed curve's first point written again last, the form GL
     * line strips and SVG polylines take.
     *
     * @param curve curve index
     * @return new points
     */
    public PackedPoints drawnPositions(int curve) {
        PackedPoints drawn = curvePositions(curve);
        if (closed[curve] && drawn.size() > 0) {
            drawn.append(drawn.at(0));
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
        PackedPoints drawn = drawnPositions(curve);
        float[] xy = new float[drawn.size() * XY];
        for (int point = 0; point < drawn.size(); point++) {
            xy[point * XY] = drawn.get(point, 0);
            xy[point * XY + 1] = drawn.get(point, 1);
        }
        return xy;
    }

    /**
     * The same curves, flags and attributes over moved points.
     *
     * @param movedPositions as many points as this geometry has
     * @return a new geometry sharing this one's offsets, flags and attribute arrays
     * @throws IllegalArgumentException if the point count differs
     */
    public CurveGeometry withPositions(PackedPoints movedPositions) {
        if (movedPositions.size() != positions.size()) {
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
        PackedPoints placed = new PackedPoints(offsets[curves]);
        Map<String, float[]> placedAttributes = new TreeMap<>();
        for (Map.Entry<String, float[]> attribute : pointAttributes.entrySet()) {
            placedAttributes.put(attribute.getKey(), new float[offsets[curves]]);
        }
        for (int curve = 0; curve < curves; curve++) {
            int points = pointCount(curve);
            if (points == 0) {
                continue;
            }
            int segments = closed[curve] ? points : points - 1;
            float[] lengthBefore = new float[segments + 1];
            for (int span = 0; span < segments; span++) {
                int from = firstPoint(curve) + span;
                int to = firstPoint(curve) + (span + 1) % points;
                lengthBefore[span + 1] = lengthBefore[span]
                        + (float) Math.sqrt(positions.distanceSquared(to, positions.at(from)));
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
            placed.slice(offsets[curve], samples).copyFrom(interpolatedPoints(firstPoint(curve), points, segment, along));
            for (Map.Entry<String, float[]> attribute : pointAttributes.entrySet()) {
                System.arraycopy(interpolated(attribute.getValue(), firstPoint(curve), points, segment, along), 0,
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
        for (int curve = 0; curve < curves; curve++) {
            int points = pointCount(curve);
            int placedCount = 0;
            if (points >= 2) {
                int segments = closed[curve] ? points : points - 1;
                steps[curve] = new int[segments];
                for (int segment = 0; segment < segments; segment++) {
                    int start = firstPoint(curve) + segment;
                    int end = firstPoint(curve) + (segment + 1) % points;
                    float length = positions.at(start).distance(positions.at(end));
                    steps[curve][segment] = length < MIN_SEGMENT_LENGTH ? 0
                            : Math.max(1, (int) Math.ceil(length / spacing));
                    placedCount += steps[curve][segment];
                }
                placedCount += closed[curve] ? 0 : 1;
            }
            offsets[curve + 1] = offsets[curve] + placedCount;
        }
        PackedPoints placed = new PackedPoints(offsets[curves]);
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
            placed.slice(offsets[curve], placedCount)
                    .copyFrom(interpolatedPoints(firstPoint(curve), pointCount(curve), segment, along));
            for (Map.Entry<String, float[]> attribute : pointAttributes.entrySet()) {
                System.arraycopy(interpolated(attribute.getValue(), firstPoint(curve), pointCount(curve), segment,
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
            PackedPoints loop = drawnPositions(curve);
            int loopPoints = loop.size();
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
                    double farthest = 0;
                    int farthestPoint = -1;
                    for (int point = first + 1; point < last; point++) {
                        double squared = TriangleGeometry.segmentDistanceSquared(loop, first, last, loop.at(point),
                                Double.POSITIVE_INFINITY);
                        if (squared > farthest) {
                            farthest = squared;
                            farthestPoint = point;
                        }
                    }
                    if (farthestPoint >= 0 && farthest > (double) tolerance * tolerance) {
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
        int[] keptPoints = IntStream.range(0, kept.length).filter(point -> kept[point]).toArray();
        Map<String, float[]> keptAttributes = new TreeMap<>();
        for (Map.Entry<String, float[]> attribute : pointAttributes.entrySet()) {
            float[] keptValues = new float[keptPoints.length];
            for (int point = 0; point < keptPoints.length; point++) {
                keptValues[point] = attribute.getValue()[keptPoints[point]];
            }
            keptAttributes.put(attribute.getKey(), keptValues);
        }
        return new CurveGeometry(positions.gather(keptPoints), offsets, closed.clone(), keptAttributes,
                curveAttributes);
    }

    /**
     * One curve's points read at sample placements: a copy of the point where {@code along} is 0,
     * else linear toward the next point, the last wrapping to the first.
     *
     * @param first   index of the curve's first point in {@link #positions}
     * @param points  how many points the curve has
     * @param segment per sample, the curve-local index of the point it starts from
     * @param along   per sample, the fraction toward the next point
     * @return {@code segment.length} new points
     */
    private PackedPoints interpolatedPoints(int first, int points, int[] segment, float[] along) {
        PackedPoints result = new PackedPoints(segment.length);
        for (int sample = 0; sample < segment.length; sample++) {
            int from = first + segment[sample];
            int to = first + (segment[sample] + 1) % points;
            float fraction = along[sample];
            result.set(sample, fraction == 0f ? positions.at(from) : positions.at(from).lerp(positions.at(to), fraction));
        }
        return result;
    }

    /**
     * Values carried on one curve's points, one per point, read at sample placements as
     * {@link #interpolatedPoints} reads the points.
     *
     * @param values  the carried values, one per point
     * @param first   index of the curve's first point in {@code values}
     * @param points  how many points the curve has
     * @param segment per sample, the curve-local index of the point it starts from
     * @param along   per sample, the fraction toward the next point
     * @return {@code segment.length} values
     */
    private static float[] interpolated(float[] values, int first, int points, int[] segment, float[] along) {
        float[] result = new float[segment.length];
        for (int sample = 0; sample < segment.length; sample++) {
            int from = first + segment[sample];
            if (along[sample] == 0f) {
                result[sample] = values[from];
                continue;
            }
            int to = first + (segment[sample] + 1) % points;
            result[sample] = values[from] + along[sample] * (values[to] - values[from]);
        }
        return result;
    }
}
