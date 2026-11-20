package ixdar.geometry.mesh.data.representation;

import java.util.stream.IntStream;

import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Triangle measurements over {@link PackedPoints} positions addressed by corner index, the
 * representation every mesh op holds before it commits to a topology.
 */
public final class TriangleGeometry {

    public static final int COMPONENTS = 3;

    public static final int CROSSES_FROM_ORIGIN = 1;

    public static final int CROSSES_FROM_BEHIND = 2;

    private static final double RAY_EPSILON = 1e-12;

    private TriangleGeometry() {
    }

    /**
     * The unit normal of a triangle, counter-clockwise corners facing the viewer.
     *
     * @param points positions
     * @param a      first corner
     * @param b      second corner
     * @param c      third corner
     * @return a new unit vector, zero for a degenerate triangle
     */
    public static Vector3f normal(PackedPoints points, int a, int b, int c) {
        Vector3d cross = cross(points.array(), points.first(), points.stride(), a, b, c);
        double length = cross.length();
        return length == 0 ? new Vector3f() : new Vector3f(cross.div(length));
    }

    /**
     * The area of a triangle, worked in double.
     *
     * @param points positions
     * @param a      first corner
     * @param b      second corner
     * @param c      third corner
     * @return its area
     */
    public static double area(PackedPoints points, int a, int b, int c) {
        return 0.5 * cross(points.array(), points.first(), points.stride(), a, b, c).length();
    }

    /**
     * The interior angle of a triangle at one corner.
     *
     * @param points positions
     * @param at     the corner the angle sits at
     * @param other  one other corner
     * @param third  the remaining corner
     * @return the angle in radians, 0 when an edge is degenerate
     */
    public static double cornerAngle(PackedPoints points, int at, int other, int third) {
        Vector3d apex = new Vector3d(points.at(at));
        Vector3d toOther = new Vector3d(points.at(other)).sub(apex);
        Vector3d toThird = new Vector3d(points.at(third)).sub(apex);
        return toOther.lengthSquared() == 0 || toThird.lengthSquared() == 0 ? 0.0 : toOther.angle(toThird);
    }

    /**
     * A triangle's unnormalised normal, the cross of its first two edges: twice its area vector,
     * worked in double.
     *
     * @param points positions
     * @param a      first corner
     * @param b      second corner
     * @param c      third corner
     * @return a new vector, zero for a degenerate triangle
     */
    public static Vector3d cross(PackedPoints points, int a, int b, int c) {
        return cross(points.array(), points.first(), points.stride(), a, b, c);
    }

    /**
     * A triangle's centre, the mean of its corners, worked in float as {@link #centres} does.
     *
     * @param points positions
     * @param a      first corner
     * @param b      second corner
     * @param c      third corner
     * @return a new vector
     */
    public static Vector3f centre(PackedPoints points, int a, int b, int c) {
        float[] xyz = points.array();
        int atA = points.first() + a * points.stride();
        int atB = points.first() + b * points.stride();
        int atC = points.first() + c * points.stride();
        return new Vector3f((xyz[atA] + xyz[atB] + xyz[atC]) / COMPONENTS,
                (xyz[atA + 1] + xyz[atB + 1] + xyz[atC + 1]) / COMPONENTS,
                (xyz[atA + 2] + xyz[atB + 2] + xyz[atC + 2]) / COMPONENTS);
    }

    /**
     * The squared length of a triangle's longest edge, worked in double.
     *
     * @param points positions
     * @param a      first corner
     * @param b      second corner
     * @param c      third corner
     * @return the squared length
     */
    public static double longestEdgeSquared(PackedPoints points, int a, int b, int c) {
        float[] xyz = points.array();
        int atA = points.first() + a * points.stride();
        int atB = points.first() + b * points.stride();
        int atC = points.first() + c * points.stride();
        return Math.max(squaredGap(xyz, atA, atB), Math.max(squaredGap(xyz, atA, atC), squaredGap(xyz, atB, atC)));
    }

    private static double squaredGap(float[] xyz, int from, int to) {
        double dx = (double) xyz[to] - xyz[from];
        double dy = (double) xyz[to + 1] - xyz[from + 1];
        double dz = (double) xyz[to + 2] - xyz[from + 2];
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Every triangle's unit normal, worked in parallel.
     *
     * @param points  positions
     * @param corners three point indices per triangle
     * @return a new buffer, one normal per triangle, zero for a degenerate one
     */
    public static PackedPoints normals(PackedPoints points, int[] corners) {
        return normals(points, corners, new Vector3f());
    }

    /**
     * Every triangle's unit normal, worked in parallel, with a stand-in for degenerate triangles.
     *
     * @param points   positions
     * @param corners  three point indices per triangle
     * @param fallback the normal a degenerate triangle gets, such as {@code +y}
     * @return a new buffer, one normal per triangle
     */
    public static PackedPoints normals(PackedPoints points, int[] corners, Vector3f fallback) {
        float[] xyz = points.array();
        int first = points.first();
        int stride = points.stride();
        PackedPoints normals = new PackedPoints(corners.length / COMPONENTS);
        IntStream.range(0, normals.size()).parallel().forEach(triangle -> {
            int corner = triangle * COMPONENTS;
            Vector3d cross = cross(xyz, first, stride, corners[corner], corners[corner + 1], corners[corner + 2]);
            double length = cross.length();
            if (length > 0) {
                normals.set(triangle, (float) (cross.x / length), (float) (cross.y / length),
                        (float) (cross.z / length));
            } else {
                normals.set(triangle, fallback);
            }
        });
        return normals;
    }

    /**
     * Every triangle's area, worked in double.
     *
     * @param points  positions
     * @param corners three point indices per triangle
     * @return a new array, one area per triangle
     */
    public static double[] areas(PackedPoints points, int[] corners) {
        float[] xyz = points.array();
        int first = points.first();
        int stride = points.stride();
        double[] areas = new double[corners.length / COMPONENTS];
        for (int triangle = 0; triangle < areas.length; triangle++) {
            int corner = triangle * COMPONENTS;
            areas[triangle] = 0.5 * cross(xyz, first, stride, corners[corner], corners[corner + 1],
                    corners[corner + 2]).length();
        }
        return areas;
    }

    /**
     * Every triangle's centre, the mean of its corners.
     *
     * @param points  positions
     * @param corners three point indices per triangle
     * @return a new buffer, one centre per triangle
     */
    public static PackedPoints centres(PackedPoints points, int[] corners) {
        float[] xyz = points.array();
        int first = points.first();
        int stride = points.stride();
        PackedPoints centres = new PackedPoints(corners.length / COMPONENTS);
        for (int triangle = 0; triangle < centres.size(); triangle++) {
            int a = first + corners[triangle * COMPONENTS] * stride;
            int b = first + corners[triangle * COMPONENTS + 1] * stride;
            int c = first + corners[triangle * COMPONENTS + 2] * stride;
            centres.set(triangle, (xyz[a] + xyz[b] + xyz[c]) / COMPONENTS,
                    (xyz[a + 1] + xyz[b + 1] + xyz[c + 1]) / COMPONENTS,
                    (xyz[a + 2] + xyz[b + 2] + xyz[c + 2]) / COMPONENTS);
        }
        return centres;
    }

    /**
     * Every triangle's axis-aligned box.
     *
     * @param points  positions
     * @param corners three point indices per triangle
     * @return a new buffer of two points per triangle, the low corner at {@code 2t} and the high at
     *         {@code 2t + 1}
     */
    public static PackedPoints boxes(PackedPoints points, int[] corners) {
        float[] xyz = points.array();
        int first = points.first();
        int stride = points.stride();
        PackedPoints boxes = new PackedPoints(2 * (corners.length / COMPONENTS));
        for (int triangle = 0; triangle < corners.length / COMPONENTS; triangle++) {
            int a = first + corners[triangle * COMPONENTS] * stride;
            int b = first + corners[triangle * COMPONENTS + 1] * stride;
            int c = first + corners[triangle * COMPONENTS + 2] * stride;
            boxes.set(2 * triangle, Math.min(xyz[a], Math.min(xyz[b], xyz[c])),
                    Math.min(xyz[a + 1], Math.min(xyz[b + 1], xyz[c + 1])),
                    Math.min(xyz[a + 2], Math.min(xyz[b + 2], xyz[c + 2])));
            boxes.set(2 * triangle + 1, Math.max(xyz[a], Math.max(xyz[b], xyz[c])),
                    Math.max(xyz[a + 1], Math.max(xyz[b + 1], xyz[c + 1])),
                    Math.max(xyz[a + 2], Math.max(xyz[b + 2], xyz[c + 2])));
        }
        return boxes;
    }

    /**
     * Where a ray crosses a triangle grown by a barycentric slack (Moller and Trumbore 1997), so a
     * ray down a shared edge cannot slip between two triangles' separately rounded tests.
     *
     * @param points    positions
     * @param a         first corner
     * @param b         second corner
     * @param c         third corner
     * @param origin    ray origin
     * @param direction ray direction
     * @param slack     how far outside the triangle, in barycentric units, still counts
     * @param minimum   ray parameter below which a crossing does not count
     * @return the ray parameter of the crossing, or positive infinity
     */
    public static double rayParameter(PackedPoints points, int a, int b, int c, Vector3f origin,
            Vector3f direction, double slack, double minimum) {
        float[] xyz = points.array();
        int atA = points.first() + a * points.stride();
        int atB = points.first() + b * points.stride();
        int atC = points.first() + c * points.stride();
        double edgeOneX = xyz[atB] - (double) xyz[atA];
        double edgeOneY = xyz[atB + 1] - (double) xyz[atA + 1];
        double edgeOneZ = xyz[atB + 2] - (double) xyz[atA + 2];
        double edgeTwoX = xyz[atC] - (double) xyz[atA];
        double edgeTwoY = xyz[atC + 1] - (double) xyz[atA + 1];
        double edgeTwoZ = xyz[atC + 2] - (double) xyz[atA + 2];
        double crossX = direction.y * edgeTwoZ - direction.z * edgeTwoY;
        double crossY = direction.z * edgeTwoX - direction.x * edgeTwoZ;
        double crossZ = direction.x * edgeTwoY - direction.y * edgeTwoX;
        double determinant = edgeOneX * crossX + edgeOneY * crossY + edgeOneZ * crossZ;
        if (determinant == 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        double toOriginX = origin.x - (double) xyz[atA];
        double toOriginY = origin.y - (double) xyz[atA + 1];
        double toOriginZ = origin.z - (double) xyz[atA + 2];
        double firstWeight = (toOriginX * crossX + toOriginY * crossY + toOriginZ * crossZ) / determinant;
        double turnX = toOriginY * edgeOneZ - toOriginZ * edgeOneY;
        double turnY = toOriginZ * edgeOneX - toOriginX * edgeOneZ;
        double turnZ = toOriginX * edgeOneY - toOriginY * edgeOneX;
        double secondWeight = (direction.x * turnX + direction.y * turnY + direction.z * turnZ) / determinant;
        double parameter = (edgeTwoX * turnX + edgeTwoY * turnY + edgeTwoZ * turnZ) / determinant;
        return firstWeight >= -slack && secondWeight >= -slack && firstWeight + secondWeight <= 1.0 + slack
                && parameter >= minimum ? parameter : Double.POSITIVE_INFINITY;
    }

    /**
     * Whether a direction points out of a triangle's front, the side its counter-clockwise
     * winding faces.
     *
     * @param points    positions
     * @param a         first corner
     * @param b         second corner
     * @param c         third corner
     * @param direction the direction
     * @return true when it runs along the normal
     */
    public static boolean facesAlong(PackedPoints points, int a, int b, int c, Vector3f direction) {
        Vector3d normal = cross(points.array(), points.first(), points.stride(), a, b, c);
        return direction.x * normal.x + direction.y * normal.y + direction.z * normal.z > 0;
    }

    /**
     * Squared distance from a position to the segment between two points, or infinity when the gap
     * along one axis alone already exceeds {@code bound}, so a nearest-segment scan skips far
     * segments cheaply.
     *
     * @param points   positions
     * @param a        one end
     * @param b        the other end
     * @param position the position
     * @param bound    squared distance past which the segment is not measured; infinity measures
     *                 every segment
     * @return the squared distance in double, or positive infinity when culled
     */
    public static double segmentDistanceSquared(PackedPoints points, int a, int b, Vector3f position,
            double bound) {
        float[] xyz = points.array();
        int atA = points.first() + a * points.stride();
        int atB = points.first() + b * points.stride();
        double x = position.x;
        double y = position.y;
        double z = position.z;
        double gapX = axisGap(x, xyz[atA], xyz[atB]);
        double gapY = axisGap(y, xyz[atA + 1], xyz[atB + 1]);
        double gapZ = axisGap(z, xyz[atA + 2], xyz[atB + 2]);
        if (gapX * gapX + gapY * gapY + gapZ * gapZ > bound) {
            return Double.POSITIVE_INFINITY;
        }
        double spanX = (double) xyz[atB] - xyz[atA];
        double spanY = (double) xyz[atB + 1] - xyz[atA + 1];
        double spanZ = (double) xyz[atB + 2] - xyz[atA + 2];
        double squared = spanX * spanX + spanY * spanY + spanZ * spanZ;
        double along = squared <= 0.0 ? 0.0
                : ((x - xyz[atA]) * spanX + (y - xyz[atA + 1]) * spanY + (z - xyz[atA + 2]) * spanZ) / squared;
        along = Math.max(0.0, Math.min(1.0, along));
        double dx = x - (xyz[atA] + along * spanX);
        double dy = y - (xyz[atA + 1] + along * spanY);
        double dz = z - (xyz[atA + 2] + along * spanZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axisGap(double at, double from, double to) {
        return Math.max(0.0, Math.max(Math.min(from, to) - at, at - Math.max(from, to)));
    }

    /**
     * Where a ray enters an axis-aligned box held as two points (the slab test), its low corner at
     * {@code low} and its high corner next.
     *
     * @param boxes   box corners, two points per box
     * @param low     the low corner's point index
     * @param origin  ray origin
     * @param inverse reciprocals of the ray direction's components
     * @param from    ray parameter the ray starts at; also returned when the origin lies on a
     *                face the ray runs along
     * @param to      ray parameter past which the box does not count
     * @return the entry parameter, or positive infinity when the ray misses the box in range
     */
    public static double boxEntry(PackedPoints boxes, int low, Vector3f origin, Vector3d inverse, double from,
            double to) {
        float[] xyz = boxes.array();
        int at = boxes.first() + low * boxes.stride();
        int high = at + boxes.stride();
        double lowX = (xyz[at] - origin.x) * inverse.x;
        double highX = (xyz[high] - origin.x) * inverse.x;
        double lowY = (xyz[at + 1] - origin.y) * inverse.y;
        double highY = (xyz[high + 1] - origin.y) * inverse.y;
        double lowZ = (xyz[at + 2] - origin.z) * inverse.z;
        double highZ = (xyz[high + 2] - origin.z) * inverse.z;
        double enter = Math.max(from, Math.max(Math.min(lowX, highX),
                Math.max(Math.min(lowY, highY), Math.min(lowZ, highZ))));
        double exit = Math.min(to, Math.min(Math.max(lowX, highX),
                Math.min(Math.max(lowY, highY), Math.max(lowZ, highZ))));
        if (enter > exit) {
            return Double.POSITIVE_INFINITY;
        }
        return Double.isNaN(enter) ? from : enter;
    }

    /**
     * Whether an axis-aligned box held as two points covers a point on two of the axes, as a line
     * along the third does when it passes through the box.
     *
     * @param boxes  box corners, two points per box
     * @param low    the low corner's point index
     * @param point  the point
     * @param first  one axis to test
     * @param second the other
     * @return true when the point lies within the box's span on both axes, edges included
     */
    public static boolean boxCovers(PackedPoints boxes, int low, Vector3d point, int first, int second) {
        float[] xyz = boxes.array();
        int at = boxes.first() + low * boxes.stride();
        int high = at + boxes.stride();
        double onFirst = point.get(first);
        double onSecond = point.get(second);
        return onFirst >= xyz[at + first] && onFirst <= xyz[high + first]
                && onSecond >= xyz[at + second] && onSecond <= xyz[high + second];
    }

    private static Vector3d cross(float[] xyz, int first, int stride, int a, int b, int c) {
        int atA = first + a * stride;
        int atB = first + b * stride;
        int atC = first + c * stride;
        double alongX = (double) xyz[atB] - xyz[atA];
        double alongY = (double) xyz[atB + 1] - xyz[atA + 1];
        double alongZ = (double) xyz[atB + 2] - xyz[atA + 2];
        double acrossX = (double) xyz[atC] - xyz[atA];
        double acrossY = (double) xyz[atC + 1] - xyz[atA + 1];
        double acrossZ = (double) xyz[atC + 2] - xyz[atA + 2];
        return new Vector3d(alongY * acrossZ - alongZ * acrossY, alongZ * acrossX - alongX * acrossZ,
                alongX * acrossY - alongY * acrossX);
    }

    /**
     * Whether the ray running from an origin along one positive axis meets a triangle in front of
     * that origin, by an axis-span reject and then Moller-Trumbore.
     *
     * @param axis 0, 1 or 2 for the {@code +x}, {@code +y} or {@code +z} ray
     * @param origin ray origin
     * @param positions xyz triples
     * @param cornerA first corner's position index
     * @param cornerB second corner's position index
     * @param cornerC third corner's position index
     * @return true when the ray crosses the triangle
     */
    public static boolean positiveAxisRayCrosses(int axis, Vector3d origin, PackedPoints positions, int cornerA,
            int cornerB, int cornerC) {
        double along = axis == 0 ? origin.x : axis == 1 ? origin.y : origin.z;
        return (positiveAxisRaysCross(axis, origin, along, positions, cornerA,
                cornerB, cornerC, positiveAxisInverseDeterminant(axis, positions, cornerA, cornerB,
                        cornerC)) & CROSSES_FROM_ORIGIN) != 0;
    }

    /**
     * The reciprocal of the determinant Moller-Trumbore divides by for a ray along one positive
     * axis, which depends on the triangle alone, so a caller casting many rays reads it once.
     *
     * @param axis 0, 1 or 2 for the {@code +x}, {@code +y} or {@code +z} ray
     * @param positions xyz triples
     * @param cornerA first corner's position index
     * @param cornerB second corner's position index
     * @param cornerC third corner's position index
     * @return the reciprocal, or {@code NaN} when the triangle is edge-on to the axis
     */
    public static double positiveAxisInverseDeterminant(int axis, PackedPoints positions, int cornerA,
            int cornerB, int cornerC) {
        float[] xyz = positions.array();
        int atA = positions.first() + cornerA * positions.stride();
        int atB = positions.first() + cornerB * positions.stride();
        int atC = positions.first() + cornerC * positions.stride();
        double edge1X = (double) xyz[atB] - xyz[atA];
        double edge1Y = (double) xyz[atB + 1] - xyz[atA + 1];
        double edge1Z = (double) xyz[atB + 2] - xyz[atA + 2];
        double edge2X = (double) xyz[atC] - xyz[atA];
        double edge2Y = (double) xyz[atC + 1] - xyz[atA + 1];
        double edge2Z = (double) xyz[atC + 2] - xyz[atA + 2];
        double directionX = axis == 0 ? 1 : 0;
        double directionY = axis == 1 ? 1 : 0;
        double directionZ = axis == 2 ? 1 : 0;
        double determinant = edge1X * (directionY * edge2Z - directionZ * edge2Y)
                + edge1Y * (directionZ * edge2X - directionX * edge2Z)
                + edge1Z * (directionX * edge2Y - directionY * edge2X);
        return Math.abs(determinant) < RAY_EPSILON ? Double.NaN : 1 / determinant;
    }

    /**
     * {@link #positiveAxisRayCrosses} for two rays on one line at once, the origin's and one
     * starting further back along the axis; they share every test but the last.
     *
     * @param axis 0, 1 or 2 for the {@code +x}, {@code +y} or {@code +z} rays
     * @param origin first ray's origin
     * @param behind the second ray's origin coordinate along the axis
     * @param positions xyz triples
     * @param cornerA first corner's position index
     * @param cornerB second corner's position index
     * @param cornerC third corner's position index
     * @param inverse the triangle's {@link #positiveAxisInverseDeterminant} for the axis
     * @return {@link #CROSSES_FROM_ORIGIN} and {@link #CROSSES_FROM_BEHIND} for the rays that
     *         cross the triangle
     */
    public static int positiveAxisRaysCross(int axis, Vector3d origin, double behind, PackedPoints positions,
            int cornerA, int cornerB, int cornerC, double inverse) {
        float[] xyz = positions.array();
        int atA = positions.first() + cornerA * positions.stride();
        int atB = positions.first() + cornerB * positions.stride();
        int atC = positions.first() + cornerC * positions.stride();
        double cornerAX = xyz[atA];
        double cornerAY = xyz[atA + 1];
        double cornerAZ = xyz[atA + 2];
        double cornerBX = xyz[atB];
        double cornerBY = xyz[atB + 1];
        double cornerBZ = xyz[atB + 2];
        double cornerCX = xyz[atC];
        double cornerCY = xyz[atC + 1];
        double cornerCZ = xyz[atC + 2];
        if (axis != 0 && outsideSpan(cornerAX, cornerBX, cornerCX, origin.x)) {
            return 0;
        }
        if (axis != 1 && outsideSpan(cornerAY, cornerBY, cornerCY, origin.y)) {
            return 0;
        }
        if (axis != 2 && outsideSpan(cornerAZ, cornerBZ, cornerCZ, origin.z)
                || Double.isNaN(inverse)) {
            return 0;
        }
        double directionX = axis == 0 ? 1 : 0;
        double directionY = axis == 1 ? 1 : 0;
        double directionZ = axis == 2 ? 1 : 0;
        double edge1X = cornerBX - cornerAX;
        double edge1Y = cornerBY - cornerAY;
        double edge1Z = cornerBZ - cornerAZ;
        double edge2X = cornerCX - cornerAX;
        double edge2Y = cornerCY - cornerAY;
        double edge2Z = cornerCZ - cornerAZ;
        double normalX = directionY * edge2Z - directionZ * edge2Y;
        double normalY = directionZ * edge2X - directionX * edge2Z;
        double normalZ = directionX * edge2Y - directionY * edge2X;
        double toX = origin.x - cornerAX;
        double toY = origin.y - cornerAY;
        double toZ = origin.z - cornerAZ;
        double barycentricU = inverse * (toX * normalX + toY * normalY + toZ * normalZ);
        if (barycentricU < 0 || barycentricU > 1) {
            return 0;
        }
        double crossX = toY * edge1Z - toZ * edge1Y;
        double crossY = toZ * edge1X - toX * edge1Z;
        double crossZ = toX * edge1Y - toY * edge1X;
        double barycentricV = inverse
                * (directionX * crossX + directionY * crossY + directionZ * crossZ);
        if (barycentricV < 0 || barycentricU + barycentricV > 1) {
            return 0;
        }
        int crosses = inverse * (edge2X * crossX + edge2Y * crossY + edge2Z * crossZ) > 0
                ? CROSSES_FROM_ORIGIN : 0;
        toX = (axis == 0 ? behind : origin.x) - cornerAX;
        toY = (axis == 1 ? behind : origin.y) - cornerAY;
        toZ = (axis == 2 ? behind : origin.z) - cornerAZ;
        crossX = toY * edge1Z - toZ * edge1Y;
        crossY = toZ * edge1X - toX * edge1Z;
        crossZ = toX * edge1Y - toY * edge1X;
        return inverse * (edge2X * crossX + edge2Y * crossY + edge2Z * crossZ) > 0
                ? crosses | CROSSES_FROM_BEHIND : crosses;
    }

    /**
     * Whether three coordinates all sit on one side of a value.
     *
     * @param first first corner's coordinate
     * @param second second corner's coordinate
     * @param third third corner's coordinate
     * @param value the coordinate to bracket
     * @return true when the value lies outside the corners' span
     */
    private static boolean outsideSpan(double first, double second, double third, double value) {
        return value < Math.min(first, Math.min(second, third))
                || value > Math.max(first, Math.max(second, third));
    }
}
