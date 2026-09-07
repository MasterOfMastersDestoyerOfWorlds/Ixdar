package ixdar.geometry.mesh.data;

/**
 * Triangle measurements over a flat {@code float[]} of xyz positions addressed by corner index,
 * the representation every mesh op holds before it commits to a topology.
 */
public final class TriangleGeometry {

    /** Components of one position. */
    public static final int COMPONENTS = 3;

    /** Half, for triangle areas. */
    private static final double HALF = 0.5;

    /** Barycentric tolerance below which a ray counts as parallel to the triangle. */
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
            return false;
        }
        if (axis != 1 && outsideSpan(cornerAY, cornerBY, cornerCY, originY)) {
            return false;
        }
        if (axis != 2 && outsideSpan(cornerAZ, cornerBZ, cornerCZ, originZ)) {
            return false;
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
        double determinant = edge1X * normalX + edge1Y * normalY + edge1Z * normalZ;
        if (Math.abs(determinant) < RAY_EPSILON) {
            return false;
        }
        double inverse = 1 / determinant;
        double toX = originX - cornerAX;
        double toY = originY - cornerAY;
        double toZ = originZ - cornerAZ;
        double barycentricU = inverse * (toX * normalX + toY * normalY + toZ * normalZ);
        if (barycentricU < 0 || barycentricU > 1) {
            return false;
        }
        double crossX = toY * edge1Z - toZ * edge1Y;
        double crossY = toZ * edge1X - toX * edge1Z;
        double crossZ = toX * edge1Y - toY * edge1X;
        double barycentricV = inverse
                * (directionX * crossX + directionY * crossY + directionZ * crossZ);
        if (barycentricV < 0 || barycentricU + barycentricV > 1) {
            return false;
        }
        return inverse * (edge2X * crossX + edge2Y * crossY + edge2Z * crossZ) > 0;
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
