package ixdar.geometry.mesh.quadlayout.motorcycle;

/**
 * Robust 2D orientation and intersection predicates for chart-space motorcycle
 * tracing. Uses double arithmetic on MC19 float UV inputs.
 */
public final class UvPredicates {

    public static final double ORIENT_COLLINEAR_EPSILON = 1.0e-12;

    private UvPredicates() {
    }

    /**
     * Signed area of triangle {@code (a, b, c)}; positive iff {@code c} lies to
     * the left of directed line {@code a → b}.
     *
     * @param ax x-coordinate of point a
     * @param ay y-coordinate of point a
     * @param bx x-coordinate of point b
     * @param by y-coordinate of point b
     * @param cx x-coordinate of point c
     * @param cy y-coordinate of point c
     * @return signed doubled triangle area
     */
    public static double orient2d(double ax, double ay, double bx, double by, double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    /**
     * True when {@code p} lies inside or on the boundary of triangle
     * {@code (a, b, c)} in UV space.
     *
     * @param pu u-coordinate of p
     * @param pv v-coordinate of p
     * @param au u-coordinate of corner a
     * @param av v-coordinate of corner a
     * @param bu u-coordinate of corner b
     * @param bv v-coordinate of corner b
     * @param cu u-coordinate of corner c
     * @param cv v-coordinate of corner c
     * @return whether p is in the closed triangle
     */
    public static boolean pointInTriangle(double pu, double pv,
            double au, double av, double bu, double bv, double cu, double cv) {
        double o0 = orient2d(au, av, bu, bv, pu, pv);
        double o1 = orient2d(bu, bv, cu, cv, pu, pv);
        double o2 = orient2d(cu, cv, au, av, pu, pv);
        boolean hasNeg = o0 < -ORIENT_COLLINEAR_EPSILON || o1 < -ORIENT_COLLINEAR_EPSILON
                || o2 < -ORIENT_COLLINEAR_EPSILON;
        boolean hasPos = o0 > ORIENT_COLLINEAR_EPSILON || o1 > ORIENT_COLLINEAR_EPSILON
                || o2 > ORIENT_COLLINEAR_EPSILON;
        return !(hasNeg && hasPos);
    }

    /**
     * Intersect two open segments {@code p0→p1} and {@code q0→q1}. Returns
     * {@code null} when segments are parallel/disjoint or overlap without a
     * unique interior crossing.
     *
     * @param p0x start x of segment p
     * @param p0y start y of segment p
     * @param p1x end x of segment p
     * @param p1y end y of segment p
     * @param q0x start x of segment q
     * @param q0y start y of segment q
     * @param q1x end x of segment q
     * @param q1y end y of segment q
     * @return intersection parameters {@code [tAlongP, tAlongQ, ix, iy]} or
     *         {@code null}
     */
    public static double[] segmentSegmentIntersection(
            double p0x, double p0y, double p1x, double p1y,
            double q0x, double q0y, double q1x, double q1y) {
        double rdx = p1x - p0x;
        double rdy = p1y - p0y;
        double sdx = q1x - q0x;
        double sdy = q1y - q0y;
        double denom = rdx * sdy - rdy * sdx;
        if (Math.abs(denom) < ORIENT_COLLINEAR_EPSILON) {
            return null;
        }
        double t = ((q0x - p0x) * sdy - (q0y - p0y) * sdx) / denom;
        double u = ((q0x - p0x) * rdy - (q0y - p0y) * rdx) / denom;
        if (t < -ORIENT_COLLINEAR_EPSILON || t > 1.0 + ORIENT_COLLINEAR_EPSILON
                || u < -ORIENT_COLLINEAR_EPSILON || u > 1.0 + ORIENT_COLLINEAR_EPSILON) {
            return null;
        }
        double ix = p0x + t * rdx;
        double iy = p0y + t * rdy;
        return new double[] { t, u, ix, iy };
    }

    /**
     * Intersect a ray {@code origin + t * direction} with segment {@code a→b},
     * requiring {@code t > minT}.
     *
     * @param ox ray origin x
     * @param oy ray origin y
     * @param dx ray direction x
     * @param dy ray direction y
     * @param ax segment start x
     * @param ay segment start y
     * @param bx segment end x
     * @param by segment end y
     * @param minT minimum ray parameter (exclusive)
     * @return ray parameter {@code t} and intersection point {@code [t, ix, iy]}
     *         or {@code null}
     */
    public static double[] raySegmentIntersection(
            double ox, double oy, double dx, double dy,
            double ax, double ay, double bx, double by, double minT) {
        double segDx = bx - ax;
        double segDy = by - ay;
        double denom = dx * segDy - dy * segDx;
        if (Math.abs(denom) < ORIENT_COLLINEAR_EPSILON) {
            return null;
        }
        double t = ((ax - ox) * segDy - (ay - oy) * segDx) / denom;
        double u = ((ax - ox) * dy - (ay - oy) * dx) / denom;
        if (t <= minT + ORIENT_COLLINEAR_EPSILON) {
            return null;
        }
        if (u < -ORIENT_COLLINEAR_EPSILON || u > 1.0 + ORIENT_COLLINEAR_EPSILON) {
            return null;
        }
        return new double[] { t, ox + t * dx, oy + t * dy };
    }

    /**
     * Signed angle from direction {@code (ax, ay)} to {@code (bx, by)} in
     * {@code (-π, π]}.
     *
     * @param ax x-component of first direction
     * @param ay y-component of first direction
     * @param bx x-component of second direction
     * @param by y-component of second direction
     * @return signed angle in radians
     */
    public static double signedAngle(double ax, double ay, double bx, double by) {
        return Math.atan2(ax * by - ay * bx, ax * bx + ay * by);
    }

    /**
     * True when {@code c} lies strictly to the left of directed line {@code a → b}.
     *
     * @param ax x-coordinate of point a
     * @param ay y-coordinate of point a
     * @param bx x-coordinate of point b
     * @param by y-coordinate of point b
     * @param cx x-coordinate of point c
     * @param cy y-coordinate of point c
     * @return whether the orientation is positive
     */
    public static boolean isCcw(double ax, double ay, double bx, double by, double cx, double cy) {
        return orient2d(ax, ay, bx, by, cx, cy) > ORIENT_COLLINEAR_EPSILON;
    }

    /**
     * True when vectors {@code (ax, ay)} and {@code (bx, by)} are collinear.
     *
     * @param ax x-component of first vector
     * @param ay y-component of first vector
     * @param bx x-component of second vector
     * @param by y-component of second vector
     * @return whether the vectors are collinear
     */
    public static boolean isCollinear(double ax, double ay, double bx, double by) {
        return Math.abs(orient2d(0.0, 0.0, ax, ay, bx, by)) <= ORIENT_COLLINEAR_EPSILON;
    }

    /**
     * QEx §4.3 {@code POINTS_INTO(d, u, v, w)}: direction {@code d} from corner
     * {@code u} enters triangle {@code (u, v, w)}.
     *
     * @param dx x-component of direction d
     * @param dy y-component of direction d
     * @param uu u-coordinate of corner u
     * @param uv v-coordinate of corner u
     * @param vu u-coordinate of corner v
     * @param vv v-coordinate of corner v
     * @param wu u-coordinate of corner w
     * @param wv v-coordinate of corner w
     * @return whether d points from u into the triangle interior
     */
    public static boolean pointsInto(double dx, double dy,
            double uu, double uv, double vu, double vv, double wu, double wv) {
        return isCcw(uu, uv, vu, vv, uu + dx, uv + dy)
                && isCcw(uu, uv, uu + dx, uv + dy, wu, wv);
    }

    /**
     * QEx direction {@code d(r) = R_90^r · (1, 0)^T}.
     *
     * @param rotation quarter-turn count r
     * @return {@code [dx, dy]}
     */
    public static double[] directionR90(int rotation) {
        int r = ((rotation % 4) + 4) % 4;
        return switch (r) {
        case 0 -> new double[] { 1.0, 0.0 };
        case 1 -> new double[] { 0.0, 1.0 };
        case 2 -> new double[] { -1.0, 0.0 };
        default -> new double[] { 0.0, -1.0 };
        };
    }
}
