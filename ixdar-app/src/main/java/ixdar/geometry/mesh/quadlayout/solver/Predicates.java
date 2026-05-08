package ixdar.geometry.mesh.quadlayout.solver;

/**
 * Robust geometry predicates. {@link #orient2d} and {@link #orient3d} return
 * the sign of the corresponding determinant exactly: +1 if positively oriented,
 * −1 if negatively oriented, 0 if exactly degenerate. Implementation follows
 * Shewchuk's adaptive scheme ("Adaptive Precision Floating-Point Arithmetic and
 * Fast Robust Geometric Predicates", 1997). The fast static-error filter
 * answers most queries; degenerate or near-degenerate inputs fall through to a
 * BigDecimal-precision recompute.
 *
 * <p>Accuracy contract:
 * <ul>
 *   <li>If the four-/five-tuple input fits in normal IEEE-754 doubles
 *       (no NaN/Inf), the returned sign is the exact sign of the determinant.
 *   <li>Throws on NaN/Inf input rather than returning a junk sign.
 * </ul>
 *
 * <p>The static-error bound constants come directly from Shewchuk's paper
 * §4.2 (orient2d) and §4.3 (orient3d) using IEEE-754 double precision
 * (ε = 2^-53). The slow path uses {@code java.math.BigDecimal} for an exact
 * (rational-equivalent) recompute — no native libraries.
 */
public final class Predicates {
    public static final double NUM_0_5 = 0.5;
    public static final double NUM_3_0 = 3.0;
    public static final double NUM_16_0 = 16.0;
    public static final double NUM_7_0 = 7.0;
    public static final double NUM_56_0 = 56.0;

    private static final double EPSILON;
    private static final double O2D_ERR_BOUND_A;
    private static final double O3D_ERR_BOUND_A;

    static {
        // Shewchuk's machine epsilon: largest x with (1 + x) == 1 in round-to-nearest.
        double e = 1.0;
        do { e *= NUM_0_5; } while ((1.0 + e * NUM_0_5) > 1.0);
        EPSILON = e;
        // Shewchuk's static filter constants (paper Tables 1-2):
        O2D_ERR_BOUND_A = (NUM_3_0 + NUM_16_0 * e) * e;
        O3D_ERR_BOUND_A = (NUM_7_0 + NUM_56_0 * e) * e;
    }

    private Predicates() {}

    /**
     * Sign of the 2D orientation determinant
     * <pre>
     *  | a.x - c.x   a.y - c.y |
     *  | b.x - c.x   b.y - c.y |
     * </pre>.
     *
     * @param ax TODO: describe
     * @param ay TODO: describe
     * @param bx TODO: describe
     * @param by TODO: describe
     * @param cx TODO: describe
     * @param cy TODO: describe
     * @return +1 if (a, b, c) is counter-clockwise, −1 if clockwise, 0 if collinear.
     */
    public static int orient2d(double ax, double ay, double bx, double by, double cx, double cy) {
        checkFinite(ax, ay, bx, by, cx, cy);

        double acx = ax - cx;
        double bcx = bx - cx;
        double acy = ay - cy;
        double bcy = by - cy;
        double detLeft = acx * bcy;
        double detRight = acy * bcx;
        double det = detLeft - detRight;

        double detSum;
        if (detLeft > 0.0) {
            if (detRight <= 0.0) return signum(det);
            detSum = detLeft + detRight;
        } else if (detLeft < 0.0) {
            if (detRight >= 0.0) return signum(det);
            detSum = -detLeft - detRight;
        } else {
            return signum(det);
        }
        double errBound = O2D_ERR_BOUND_A * detSum;
        if (det >= errBound || -det >= errBound) {
            return signum(det);
        }
        // Slow path: exact recompute via BigDecimal.
        return orient2dExact(ax, ay, bx, by, cx, cy);
    }

    /**
     * Sign of the 3D orientation determinant of the tetrahedron (a,b,c,d).
     * Positive if d is on the negative side of the oriented plane through a,b,c
     * (matching Shewchuk's convention).
     *
     * @param ax TODO: describe
     * @param ay TODO: describe
     * @param az TODO: describe
     * @param bx TODO: describe
     * @param by TODO: describe
     * @param bz TODO: describe
     * @param cx TODO: describe
     * @param cy TODO: describe
     * @param cz TODO: describe
     * @param dx TODO: describe
     * @param dy TODO: describe
     * @param dz TODO: describe
     * @return TODO: describe
     */
    public static int orient3d(double ax, double ay, double az,
                               double bx, double by, double bz,
                               double cx, double cy, double cz,
                               double dx, double dy, double dz) {
        checkFinite(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz);

        double adx = ax - dx, bdx = bx - dx, cdx = cx - dx;
        double ady = ay - dy, bdy = by - dy, cdy = cy - dy;
        double adz = az - dz, bdz = bz - dz, cdz = cz - dz;

        double bdxcdy = bdx * cdy, cdxbdy = cdx * bdy;
        double cdxady = cdx * ady, adxcdy = adx * cdy;
        double adxbdy = adx * bdy, bdxady = bdx * ady;

        double det = adz * (bdxcdy - cdxbdy)
                   + bdz * (cdxady - adxcdy)
                   + cdz * (adxbdy - bdxady);

        double permanent =
              (Math.abs(bdxcdy) + Math.abs(cdxbdy)) * Math.abs(adz)
            + (Math.abs(cdxady) + Math.abs(adxcdy)) * Math.abs(bdz)
            + (Math.abs(adxbdy) + Math.abs(bdxady)) * Math.abs(cdz);
        double errBound = O3D_ERR_BOUND_A * permanent;
        if (det > errBound || -det > errBound) {
            return signum(det);
        }
        return orient3dExact(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz);
    }

    // ---- Exact slow paths (BigDecimal-based) -------------------------------

    private static int orient2dExact(double ax, double ay, double bx, double by, double cx, double cy) {
        java.math.BigDecimal Ax = bd(ax), Ay = bd(ay);
        java.math.BigDecimal Bx = bd(bx), By = bd(by);
        java.math.BigDecimal Cx = bd(cx), Cy = bd(cy);
        java.math.BigDecimal acx = Ax.subtract(Cx), bcx = Bx.subtract(Cx);
        java.math.BigDecimal acy = Ay.subtract(Cy), bcy = By.subtract(Cy);
        java.math.BigDecimal det = acx.multiply(bcy).subtract(acy.multiply(bcx));
        return det.signum();
    }

    private static int orient3dExact(double ax, double ay, double az,
                                     double bx, double by, double bz,
                                     double cx, double cy, double cz,
                                     double dx, double dy, double dz) {
        java.math.BigDecimal Ax = bd(ax), Ay = bd(ay), Az = bd(az);
        java.math.BigDecimal Bx = bd(bx), By = bd(by), Bz = bd(bz);
        java.math.BigDecimal Cx = bd(cx), Cy = bd(cy), Cz = bd(cz);
        java.math.BigDecimal Dx = bd(dx), Dy = bd(dy), Dz = bd(dz);
        java.math.BigDecimal adx = Ax.subtract(Dx), bdx = Bx.subtract(Dx), cdx = Cx.subtract(Dx);
        java.math.BigDecimal ady = Ay.subtract(Dy), bdy = By.subtract(Dy), cdy = Cy.subtract(Dy);
        java.math.BigDecimal adz = Az.subtract(Dz), bdz = Bz.subtract(Dz), cdz = Cz.subtract(Dz);
        java.math.BigDecimal m = adz.multiply(bdx.multiply(cdy).subtract(cdx.multiply(bdy)))
            .add(bdz.multiply(cdx.multiply(ady).subtract(adx.multiply(cdy))))
            .add(cdz.multiply(adx.multiply(bdy).subtract(bdx.multiply(ady))));
        return m.signum();
    }

    private static java.math.BigDecimal bd(double v) {
        return new java.math.BigDecimal(v);
    }

    private static int signum(double x) {
        if (x > 0.0) return +1;
        if (x < 0.0) return -1;
        return 0;
    }

    private static void checkFinite(double... vs) {
        for (double v : vs) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("non-finite predicate input: " + v);
            }
        }
    }

    /**
     * Convenience: machine epsilon used by the static filters. Public for tests.
     *
     * @return TODO: describe
     */
    public static double machineEpsilon() { return EPSILON; }
}
