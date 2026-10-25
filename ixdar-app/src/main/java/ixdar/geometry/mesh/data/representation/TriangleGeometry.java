package ixdar.geometry.mesh.data.representation;

/**
 * Triangle measurements over a flat {@code float[]} of xyz positions addressed by corner index,
 * the representation every mesh op holds before it commits to a topology.
 */
public final class TriangleGeometry {

    public static final int COMPONENTS = 3;

    public static final int CROSSES_FROM_ORIGIN = 1;

    public static final int CROSSES_FROM_BEHIND = 2;

    private static final double HALF = 0.5;

    private static final double RAY_EPSILON = 1e-12;

    private TriangleGeometry() {
    }

    /**
     * Area of the triangle spanned by three positions.
     *
     * @param positions xyz triples
     * @param cornerA first corner's position index
     * @param cornerB second corner's position index
     * @param cornerC third corner's position index
     * @return the area
     */
    public static double area(float[] positions, int cornerA, int cornerB, int cornerC) {
        double firstX = positions[cornerB * COMPONENTS] - positions[cornerA * COMPONENTS];
        double firstY = positions[cornerB * COMPONENTS + 1] - positions[cornerA * COMPONENTS + 1];
        double firstZ = positions[cornerB * COMPONENTS + 2] - positions[cornerA * COMPONENTS + 2];
        double secondX = positions[cornerC * COMPONENTS] - positions[cornerA * COMPONENTS];
        double secondY = positions[cornerC * COMPONENTS + 1] - positions[cornerA * COMPONENTS + 1];
        double secondZ = positions[cornerC * COMPONENTS + 2] - positions[cornerA * COMPONENTS + 2];
        double crossX = firstY * secondZ - firstZ * secondY;
        double crossY = firstZ * secondX - firstX * secondZ;
        double crossZ = firstX * secondY - firstY * secondX;
        return HALF * Math.sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ);
    }

    /**
     * Whether the ray running from an origin along one positive axis meets a triangle in front of
     * that origin, by an axis-span reject and then Moller-Trumbore.
     *
     * @param axis 0, 1 or 2 for the {@code +x}, {@code +y} or {@code +z} ray
     * @param originX ray origin x
     * @param originY ray origin y
     * @param originZ ray origin z
     * @param positions xyz triples
     * @param cornerA first corner's position index
     * @param cornerB second corner's position index
     * @param cornerC third corner's position index
     * @return true when the ray crosses the triangle
     */
    public static boolean positiveAxisRayCrosses(int axis, double originX, double originY,
            double originZ, float[] positions, int cornerA, int cornerB, int cornerC) {
        double along = axis == 0 ? originX : axis == 1 ? originY : originZ;
        return (positiveAxisRaysCross(axis, originX, originY, originZ, along, positions, cornerA,
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
    public static double positiveAxisInverseDeterminant(int axis, float[] positions, int cornerA,
            int cornerB, int cornerC) {
        double edge1X = (double) positions[cornerB * COMPONENTS] - positions[cornerA * COMPONENTS];
        double edge1Y = (double) positions[cornerB * COMPONENTS + 1]
                - positions[cornerA * COMPONENTS + 1];
        double edge1Z = (double) positions[cornerB * COMPONENTS + 2]
                - positions[cornerA * COMPONENTS + 2];
        double edge2X = (double) positions[cornerC * COMPONENTS] - positions[cornerA * COMPONENTS];
        double edge2Y = (double) positions[cornerC * COMPONENTS + 1]
                - positions[cornerA * COMPONENTS + 1];
        double edge2Z = (double) positions[cornerC * COMPONENTS + 2]
                - positions[cornerA * COMPONENTS + 2];
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
     * @param originX first ray's origin x
     * @param originY first ray's origin y
     * @param originZ first ray's origin z
     * @param behind the second ray's origin coordinate along the axis
     * @param positions xyz triples
     * @param cornerA first corner's position index
     * @param cornerB second corner's position index
     * @param cornerC third corner's position index
     * @param inverse the triangle's {@link #positiveAxisInverseDeterminant} for the axis
     * @return {@link #CROSSES_FROM_ORIGIN} and {@link #CROSSES_FROM_BEHIND} for the rays that
     *         cross the triangle
     */
    public static int positiveAxisRaysCross(int axis, double originX, double originY,
            double originZ, double behind, float[] positions, int cornerA, int cornerB,
            int cornerC, double inverse) {
        double cornerAX = positions[cornerA * COMPONENTS];
        double cornerAY = positions[cornerA * COMPONENTS + 1];
        double cornerAZ = positions[cornerA * COMPONENTS + 2];
        double cornerBX = positions[cornerB * COMPONENTS];
        double cornerBY = positions[cornerB * COMPONENTS + 1];
        double cornerBZ = positions[cornerB * COMPONENTS + 2];
        double cornerCX = positions[cornerC * COMPONENTS];
        double cornerCY = positions[cornerC * COMPONENTS + 1];
        double cornerCZ = positions[cornerC * COMPONENTS + 2];
        if (axis != 0 && outsideSpan(cornerAX, cornerBX, cornerCX, originX)) {
            return 0;
        }
        if (axis != 1 && outsideSpan(cornerAY, cornerBY, cornerCY, originY)) {
            return 0;
        }
        if (axis != 2 && outsideSpan(cornerAZ, cornerBZ, cornerCZ, originZ)
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
        double toX = originX - cornerAX;
        double toY = originY - cornerAY;
        double toZ = originZ - cornerAZ;
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
        // The barycentric tests read no coordinate along the axis, so the ray from behind passes
        // them too and differs only in how far along it meets the plane.
        toX = (axis == 0 ? behind : originX) - cornerAX;
        toY = (axis == 1 ? behind : originY) - cornerAY;
        toZ = (axis == 2 ? behind : originZ) - cornerAZ;
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
