package ixdar.geometry.mesh.quadlayout.embedding;

/**
 * Inverts a face's chart, and answers which corner a point sits on and whether
 * two points are one point. The tolerance is
 * {@link FaceChordWalk#MINIMUM_SEPARATION}, the carve's own snap rule.
 */
public final class ChartBarycentric {

    /** Corners of a triangle. */
    public static final int CORNERS = 3;

    /** Pure static utility; never instantiated. */
    private ChartBarycentric() {
    }

    /**
     * The barycentric of a chart point in the face whose corner UVs are given.
     *
     * @param cornerUv the face's corner UVs, {@code [u0, v0, u1, v1, u2, v2]}
     * @param pointU   chart u of the point
     * @param pointV   chart v of the point
     * @return its barycentric triple, or {@code null} when the chart is degenerate
     */
    public static double[] ofChartPoint(double[] cornerUv, double pointU, double pointV) {
        double firstU = cornerUv[2] - cornerUv[0];
        double firstV = cornerUv[3] - cornerUv[1];
        double secondU = cornerUv[4] - cornerUv[0];
        double secondV = cornerUv[5] - cornerUv[1];
        double determinant = firstU * secondV - firstV * secondU;
        if (determinant == 0.0) {
            return null;
        }
        double offsetU = pointU - cornerUv[0];
        double offsetV = pointV - cornerUv[1];
        double second = (offsetU * secondV - offsetV * secondU) / determinant;
        double third = (firstU * offsetV - firstV * offsetU) / determinant;
        return new double[] { 1.0 - second - third, second, third };
    }

    /**
     * The corner of the face a barycentric sits on, or -1 when it is clear of all
     * three. A chart inversion of a corner's own UV lands a few ulps off it, which
     * is why this is a tolerance and not an equality.
     *
     * @param barycentric the point's barycentric in the face
     * @return the local corner index the point sits on, or -1
     */
    public static int cornerHolding(double[] barycentric) {
        for (int corner = 0; corner < CORNERS; corner++) {
            if (Math.abs(barycentric[(corner + 1) % CORNERS]) < FaceChordWalk.MINIMUM_SEPARATION
                    && Math.abs(barycentric[(corner + 2) % CORNERS])
                            < FaceChordWalk.MINIMUM_SEPARATION) {
                return corner;
            }
        }
        return -1;
    }

    /**
     * The edge of the face a barycentric lies on, as the local index of the corner
     * it runs from, or -1 when the point is off every edge. A point on a corner
     * lies on two edges and is reported by {@link #cornerHolding} instead.
     *
     * @param barycentric the point's barycentric in the face
     * @return the local edge index the point lies on, or -1
     */
    public static int edgeHolding(double[] barycentric) {
        if (cornerHolding(barycentric) >= 0) {
            return -1;
        }
        for (int edge = 0; edge < CORNERS; edge++) {
            if (Math.abs(barycentric[(edge + 2) % CORNERS]) < FaceChordWalk.MINIMUM_SEPARATION) {
                return edge;
            }
        }
        return -1;
    }

    /**
     * How far along one of the face's edges a barycentric on it sits, measured
     * from the corner the edge runs from.
     *
     * @param barycentric the point's barycentric in the face
     * @param localEdge   local index of the edge the point lies on
     * @return the parameter along that edge, in {@code [0, 1]}
     */
    public static double parameterAlongEdge(double[] barycentric, int localEdge) {
        double from = barycentric[localEdge];
        double to = barycentric[(localEdge + 1) % CORNERS];
        double span = from + to;
        return span == 0.0 ? 0.0 : to / span;
    }

    /**
     * Whether two parameters along one mesh edge name the same point on it, so
     * that carving both would split the edge twice at one place.
     *
     * @param first  one point's parameter along the edge
     * @param second the other point's parameter along the edge
     * @return whether the two are the same point
     */
    public static boolean sameEdgePoint(double first, double second) {
        return Math.abs(first - second) < FaceChordWalk.MINIMUM_SEPARATION;
    }

    /**
     * The same barycentric with a coordinate a rounding step below zero pulled
     * onto the triangle's boundary, renormalized. A coordinate further out is left
     * alone, so a point that really is outside its face still reads as outside.
     *
     * @param barycentric the point's barycentric, clamped in place
     * @return the same array
     */
    public static double[] clampOntoTriangle(double[] barycentric) {
        double total = 0.0;
        boolean clamped = false;
        for (int corner = 0; corner < CORNERS; corner++) {
            if (barycentric[corner] < 0.0
                    && barycentric[corner] > -FaceChordWalk.MINIMUM_SEPARATION) {
                barycentric[corner] = 0.0;
                clamped = true;
            }
            total += barycentric[corner];
        }
        if (!clamped || total <= 0.0) {
            return barycentric;
        }
        for (int corner = 0; corner < CORNERS; corner++) {
            barycentric[corner] /= total;
        }
        return barycentric;
    }

    /**
     * Whether two barycentrics of one face name the same point, meaning the carve
     * would place the second on the vertex it minted for the first.
     *
     * @param first  one point's barycentric
     * @param second the other point's barycentric
     * @return whether the two are the same point
     */
    public static boolean sameChartPoint(double[] first, double[] second) {
        for (int corner = 0; corner < CORNERS; corner++) {
            if (Math.abs(first[corner] - second[corner]) >= FaceChordWalk.MINIMUM_SEPARATION) {
                return false;
            }
        }
        return true;
    }
}
