package ixdar.geometry.mesh.data.representation;

import java.util.Arrays;

import org.joml.Matrix3fc;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Many xyz points in one float array, owned or viewed, read and written by point index; a strided
 * view reads interleaved records such as GPU vertices.
 */
public final class PackedPoints {
    public static final int XYZ = 3;

    private static final int INITIAL_CAPACITY = 16;

    private static final String ONLY_PLAIN_GROWS = "only a plain xyz buffer grows";

    private float[] xyz;

    private final int first;

    private final int stride;

    private int count;

    /** An empty buffer that grows as points are appended. */
    public PackedPoints() {
        this(new float[INITIAL_CAPACITY * XYZ], 0, XYZ, 0);
    }

    /**
     * A buffer of points at the origin.
     *
     * @param count points held
     */
    public PackedPoints(int count) {
        this(new float[count * XYZ], 0, XYZ, count);
    }

    private PackedPoints(float[] xyz, int first, int stride, int count) {
        this.xyz = xyz;
        this.first = first;
        this.stride = stride;
        this.count = count;
    }

    /**
     * The points of an array of interleaved records, writing through to it: point {@code i} has its
     * x at {@code first + i * stride}.
     *
     * @param array  the records
     * @param first  index of point 0's x
     * @param stride floats from one point's x to the next
     * @return a buffer over {@code array}
     */
    public static PackedPoints view(float[] array, int first, int stride) {
        int count = array.length < first + XYZ ? 0 : (array.length - first - XYZ) / stride + 1;
        return new PackedPoints(array, first, stride, count);
    }

    /**
     * The first points of an array of interleaved records, writing through to it, for an array
     * that runs past its last record.
     *
     * @param array  the records
     * @param first  index of point 0's x
     * @param stride floats from one point's x to the next
     * @param count  points in the view
     * @return a buffer over {@code array}
     */
    public static PackedPoints view(float[] array, int first, int stride, int count) {
        return new PackedPoints(array, first, stride, count);
    }

    /**
     * A run of consecutive points, writing through to this buffer; it stops seeing this buffer once
     * an append or resize grows it.
     *
     * @param start first point of the run
     * @param count points in the run
     * @return a view over the run
     */
    public PackedPoints slice(int start, int count) {
        return new PackedPoints(xyz, first + start * stride, stride, count);
    }

    /**
     * Buffers joined end to end.
     *
     * @param parts the buffers, in order
     * @return a new buffer holding every part's points
     */
    public static PackedPoints concat(PackedPoints... parts) {
        int total = 0;
        for (PackedPoints part : parts) {
            total += part.count;
        }
        PackedPoints joined = new PackedPoints(total);
        int start = 0;
        for (PackedPoints part : parts) {
            joined.slice(start, part.count).copyFrom(part);
            start += part.count;
        }
        return joined;
    }

    /**
     * A point.
     *
     * @param point point index
     * @return a new vector holding it
     */
    public Vector3f at(int point) {
        int at = first + point * stride;
        return new Vector3f(xyz[at], xyz[at + 1], xyz[at + 2]);
    }

    /**
     * One coordinate of a point.
     *
     * @param point point index
     * @param axis  0 for x, 1 for y, 2 for z
     * @return the coordinate
     */
    public float get(int point, int axis) {
        return xyz[first + point * stride + axis];
    }

    /**
     * Overwrite a point.
     *
     * @param point    point index
     * @param position its new position
     */
    public void set(int point, Vector3f position) {
        set(point, position.x, position.y, position.z);
    }

    /**
     * Overwrite a point.
     *
     * @param point point index
     * @param x     new x
     * @param y     new y
     * @param z     new z
     */
    public void set(int point, float x, float y, float z) {
        int at = first + point * stride;
        xyz[at] = x;
        xyz[at + 1] = y;
        xyz[at + 2] = z;
    }

    /**
     * Overwrite one coordinate of a point.
     *
     * @param point point index
     * @param axis  0 for x, 1 for y, 2 for z
     * @param value the new coordinate
     */
    public void set(int point, int axis, float value) {
        xyz[first + point * stride + axis] = value;
    }

    /**
     * Move a point by an offset.
     *
     * @param point  point index
     * @param offset added to it
     */
    public void add(int point, Vector3f offset) {
        int at = first + point * stride;
        xyz[at] += offset.x;
        xyz[at + 1] += offset.y;
        xyz[at + 2] += offset.z;
    }

    /**
     * Add a point after the last, growing the array when full; only a buffer three floats per point
     * from index 0 grows.
     *
     * @param position the new point
     * @return its index
     * @throws IllegalStateException for a strided or offset view
     */
    public int append(Vector3f position) {
        return append(position.x, position.y, position.z);
    }

    /**
     * Add a point after the last, growing the array when full; only a buffer three floats per point
     * from index 0 grows.
     *
     * @param x its x
     * @param y its y
     * @param z its z
     * @return its index
     * @throws IllegalStateException for a strided or offset view
     */
    public int append(float x, float y, float z) {
        if (stride != XYZ || first != 0) {
            throw new IllegalStateException(ONLY_PLAIN_GROWS);
        }
        if ((count + 1) * XYZ > xyz.length) {
            xyz = Arrays.copyOf(xyz, Math.max(INITIAL_CAPACITY, count * 2) * XYZ);
        }
        set(count, x, y, z);
        return count++;
    }

    /**
     * Hold exactly {@code points} points, keeping the first ones; points past the old size start at
     * the origin. A growing array is sized to {@code points} exactly, so a caller with its own
     * growth policy, such as a mesh reserving id slots, pays for one copy per growth.
     *
     * @param points points held afterwards
     * @throws IllegalStateException for a strided or offset view
     */
    public void resize(int points) {
        if (stride != XYZ || first != 0) {
            throw new IllegalStateException(ONLY_PLAIN_GROWS);
        }
        int end = points * XYZ;
        if (end > xyz.length) {
            float[] grown = new float[end];
            System.arraycopy(xyz, 0, grown, 0, Math.min(count, points) * XYZ);
            xyz = grown;
        } else if (points > count) {
            Arrays.fill(xyz, count * XYZ, end, 0f);
        }
        count = points;
    }

    /**
     * Points held.
     *
     * @return the count
     */
    public int size() {
        return count;
    }

    /**
     * The array itself, for an upload or a tight loop; a growing buffer's array may run past its
     * last point.
     *
     * @return the backing array
     */
    public float[] array() {
        return xyz;
    }

    /**
     * A new buffer holding the same points, three floats per point and exactly {@link #size()} of
     * them, independent of this one.
     *
     * @return the copy
     */
    public PackedPoints copy() {
        return new PackedPoints(toArray(), 0, XYZ, count);
    }

    /**
     * The points as a new packed xyz array of exactly {@link #size()} points.
     *
     * @return the copy
     */
    public float[] toArray() {
        if (stride == XYZ && first == 0) {
            return Arrays.copyOf(xyz, count * XYZ);
        }
        float[] packed = new float[count * XYZ];
        for (int point = 0; point < count; point++) {
            System.arraycopy(xyz, first + point * stride, packed, point * XYZ, XYZ);
        }
        return packed;
    }

    /**
     * Index of point 0's x in {@link #array()}, so a bulk loop holds the layout in locals.
     *
     * @return the offset
     */
    public int first() {
        return first;
    }

    /**
     * Floats from one point's x to the next in {@link #array()}, so a bulk loop holds the layout in
     * locals.
     *
     * @return the stride
     */
    public int stride() {
        return stride;
    }

    /**
     * Overwrite every point with another buffer's.
     *
     * @param source points of the same count
     */
    public void copyFrom(PackedPoints source) {
        requireSameSize(source);
        float[] from = source.xyz;
        int fromFirst = source.first;
        int fromStride = source.stride;
        for (int point = 0; point < count; point++) {
            int to = first + point * stride;
            int at = fromFirst + point * fromStride;
            xyz[to] = from[at];
            xyz[to + 1] = from[at + 1];
            xyz[to + 2] = from[at + 2];
        }
    }

    /**
     * The points at some indices, in their order.
     *
     * @param indices point indices, repeats allowed
     * @return a new buffer, point {@code i} a copy of point {@code indices[i]}
     */
    public PackedPoints gather(int[] indices) {
        float[] packed = new float[indices.length * XYZ];
        for (int point = 0; point < indices.length; point++) {
            int at = first + indices[point] * stride;
            packed[point * XYZ] = xyz[at];
            packed[point * XYZ + 1] = xyz[at + 1];
            packed[point * XYZ + 2] = xyz[at + 2];
        }
        return new PackedPoints(packed, 0, XYZ, indices.length);
    }

    /**
     * Move every point by a multiple of the matching point of another buffer.
     *
     * @param offsets points of the same count
     * @param scale   multiplies each offset
     */
    public void addScaled(PackedPoints offsets, float scale) {
        requireSameSize(offsets);
        float[] from = offsets.xyz;
        for (int point = 0; point < count; point++) {
            int to = first + point * stride;
            int at = offsets.first + point * offsets.stride;
            xyz[to] += scale * from[at];
            xyz[to + 1] += scale * from[at + 1];
            xyz[to + 2] += scale * from[at + 2];
        }
    }

    /**
     * Move each point a fraction of the way to the matching point of another buffer.
     *
     * @param target  points of the same count
     * @param weight  0 keeps a point, 1 moves it onto its target
     * @param movable which points move, or null for all
     */
    public void lerpToward(PackedPoints target, float weight, boolean[] movable) {
        requireSameSize(target);
        float[] to = target.xyz;
        for (int point = 0; point < count; point++) {
            if (movable != null && !movable[point]) {
                continue;
            }
            int at = first + point * stride;
            int goal = target.first + point * target.stride;
            xyz[at] += weight * (to[goal] - xyz[at]);
            xyz[at + 1] += weight * (to[goal + 1] - xyz[at + 1]);
            xyz[at + 2] += weight * (to[goal + 2] - xyz[at + 2]);
        }
    }

    /**
     * Map every point through an affine matrix, translation included.
     *
     * @param matrix the transform
     */
    public void transformPositions(Matrix4fc matrix) {
        Vector3f position = new Vector3f();
        for (int point = 0; point < count; point++) {
            int at = first + point * stride;
            matrix.transformPosition(position.set(xyz[at], xyz[at + 1], xyz[at + 2]));
            xyz[at] = position.x;
            xyz[at + 1] = position.y;
            xyz[at + 2] = position.z;
        }
    }

    /**
     * Map every point through a linear matrix, as directions or normals are.
     *
     * @param matrix the transform
     */
    public void transform(Matrix3fc matrix) {
        Vector3f direction = new Vector3f();
        for (int point = 0; point < count; point++) {
            int at = first + point * stride;
            matrix.transform(direction.set(xyz[at], xyz[at + 1], xyz[at + 2]));
            xyz[at] = direction.x;
            xyz[at + 1] = direction.y;
            xyz[at + 2] = direction.z;
        }
    }

    /** Scale every point to unit length, leaving zero points at the origin. */
    public void normalizeEach() {
        for (int point = 0; point < count; point++) {
            int at = first + point * stride;
            float x = xyz[at];
            float y = xyz[at + 1];
            float z = xyz[at + 2];
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            if (length > 0f) {
                xyz[at] = x / length;
                xyz[at + 1] = y / length;
                xyz[at + 2] = z / length;
            }
        }
    }

    /**
     * The axis-aligned box round every point.
     *
     * @return a new two-point buffer, the low corner then the high; infinite and inverted when
     *         empty
     */
    public PackedPoints bounds() {
        return bounds(null);
    }

    /**
     * The axis-aligned box round some points.
     *
     * @param subset point indices to bound, or null for all
     * @return a new two-point buffer, the low corner then the high; infinite and inverted when
     *         empty
     */
    public PackedPoints bounds(int[] subset) {
        float lowX = Float.POSITIVE_INFINITY;
        float lowY = Float.POSITIVE_INFINITY;
        float lowZ = Float.POSITIVE_INFINITY;
        float highX = Float.NEGATIVE_INFINITY;
        float highY = Float.NEGATIVE_INFINITY;
        float highZ = Float.NEGATIVE_INFINITY;
        int points = subset == null ? count : subset.length;
        for (int index = 0; index < points; index++) {
            int at = first + (subset == null ? index : subset[index]) * stride;
            lowX = Math.min(lowX, xyz[at]);
            lowY = Math.min(lowY, xyz[at + 1]);
            lowZ = Math.min(lowZ, xyz[at + 2]);
            highX = Math.max(highX, xyz[at]);
            highY = Math.max(highY, xyz[at + 1]);
            highZ = Math.max(highZ, xyz[at + 2]);
        }
        return new PackedPoints(new float[] {lowX, lowY, lowZ, highX, highY, highZ}, 0, XYZ, 2);
    }

    /**
     * The mean of every point, summed in double.
     *
     * @return a new vector, the origin when empty
     */
    public Vector3f centroid() {
        return centroid(null);
    }

    /**
     * The weighted mean of every point, summed in double.
     *
     * @param weights one per point, or null for equal weights
     * @return a new vector, the origin when the weights sum to zero
     */
    public Vector3f centroid(double[] weights) {
        double sumX = 0;
        double sumY = 0;
        double sumZ = 0;
        double total = 0;
        for (int point = 0; point < count; point++) {
            int at = first + point * stride;
            double weight = weights == null ? 1 : weights[point];
            sumX += weight * xyz[at];
            sumY += weight * xyz[at + 1];
            sumZ += weight * xyz[at + 2];
            total += weight;
        }
        return total == 0 ? new Vector3f()
                : new Vector3f((float) (sumX / total), (float) (sumY / total), (float) (sumZ / total));
    }

    /**
     * The length of the polyline through the points in order.
     *
     * @return the summed segment lengths, in double
     */
    public double arcLength() {
        double length = 0;
        for (int point = 1; point < count; point++) {
            int at = first + point * stride;
            int before = at - stride;
            double dx = xyz[at] - xyz[before];
            double dy = xyz[at + 1] - xyz[before + 1];
            double dz = xyz[at + 2] - xyz[before + 2];
            length += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return length;
    }

    /**
     * Each point's neighbours averaged.
     *
     * @param rings each point's neighbour indices
     * @return a new buffer, a point without neighbours keeping its own position
     */
    public PackedPoints ringMean(int[][] rings) {
        return ringMean(rings, false);
    }

    /**
     * Each point's neighbours averaged, optionally with the point itself counted first.
     *
     * @param rings       each point's neighbour indices
     * @param includeSelf whether the point joins its own mean
     * @return a new buffer, a point without neighbours keeping its own position
     */
    public PackedPoints ringMean(int[][] rings, boolean includeSelf) {
        float[] packed = new float[count * XYZ];
        for (int point = 0; point < count; point++) {
            int[] ring = rings[point];
            int at = first + point * stride;
            int members = ring.length + (includeSelf ? 1 : 0);
            if (ring.length == 0) {
                packed[point * XYZ] = xyz[at];
                packed[point * XYZ + 1] = xyz[at + 1];
                packed[point * XYZ + 2] = xyz[at + 2];
                continue;
            }
            float sumX = includeSelf ? xyz[at] : 0f;
            float sumY = includeSelf ? xyz[at + 1] : 0f;
            float sumZ = includeSelf ? xyz[at + 2] : 0f;
            for (int neighbour : ring) {
                int other = first + neighbour * stride;
                sumX += xyz[other];
                sumY += xyz[other + 1];
                sumZ += xyz[other + 2];
            }
            packed[point * XYZ] = sumX / members;
            packed[point * XYZ + 1] = sumY / members;
            packed[point * XYZ + 2] = sumZ / members;
        }
        return new PackedPoints(packed, 0, XYZ, count);
    }

    /**
     * The mean of each group of points, summed in double.
     *
     * @param groupOf    each point's group, or negative to leave it out
     * @param groupCount groups
     * @return a new buffer, one mean per group, the origin for an empty group
     */
    public PackedPoints groupMeans(int[] groupOf, int groupCount) {
        double[] sums = new double[groupCount * XYZ];
        int[] members = new int[groupCount];
        for (int point = 0; point < count; point++) {
            int group = groupOf[point];
            if (group < 0) {
                continue;
            }
            int at = first + point * stride;
            sums[group * XYZ] += xyz[at];
            sums[group * XYZ + 1] += xyz[at + 1];
            sums[group * XYZ + 2] += xyz[at + 2];
            members[group]++;
        }
        PackedPoints means = new PackedPoints(groupCount);
        for (int group = 0; group < groupCount; group++) {
            if (members[group] > 0) {
                means.set(group, (float) (sums[group * XYZ] / members[group]),
                        (float) (sums[group * XYZ + 1] / members[group]),
                        (float) (sums[group * XYZ + 2] / members[group]));
            }
        }
        return means;
    }

    /**
     * The largest distance from a position to any point, such as a bounding radius round a centre.
     *
     * @param centre the position
     * @return the distance, in double, 0 when empty
     */
    public double maxDistance(Vector3f centre) {
        double largest = 0;
        for (int point = 0; point < count; point++) {
            largest = Math.max(largest, distanceSquared(point, centre));
        }
        return Math.sqrt(largest);
    }

    /**
     * Squared distance from a point to a position.
     *
     * @param point    point index
     * @param position the position
     * @return the squared distance, in double
     */
    public double distanceSquared(int point, Vector3f position) {
        int at = first + point * stride;
        double dx = xyz[at] - (double) position.x;
        double dy = xyz[at + 1] - (double) position.y;
        double dz = xyz[at + 2] - (double) position.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Squared distance between two points.
     *
     * @param a one point's index
     * @param b the other's
     * @return the squared distance, in double
     */
    public double distanceSquared(int a, int b) {
        int atA = first + a * stride;
        int atB = first + b * stride;
        double dx = xyz[atB] - (double) xyz[atA];
        double dy = xyz[atB + 1] - (double) xyz[atA + 1];
        double dz = xyz[atB + 2] - (double) xyz[atA + 2];
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * The point of a run nearest a position.
     *
     * @param position the position
     * @param start    first point of the run
     * @param end      one past its last point
     * @return the nearest point's index, the first on a tie, or -1 for an empty run
     */
    public int nearest(Vector3f position, int start, int end) {
        double x = position.x;
        double y = position.y;
        double z = position.z;
        int nearest = -1;
        double best = Double.POSITIVE_INFINITY;
        for (int point = start; point < end; point++) {
            int at = first + point * stride;
            double dx = xyz[at] - x;
            double dy = xyz[at + 1] - y;
            double dz = xyz[at + 2] - z;
            double squared = dx * dx + dy * dy + dz * dz;
            if (squared < best) {
                best = squared;
                nearest = point;
            }
        }
        return nearest;
    }

    private void requireSameSize(PackedPoints other) {
        if (other.count != count) {
            throw new IllegalArgumentException("size " + other.count + " != " + count);
        }
    }
}
