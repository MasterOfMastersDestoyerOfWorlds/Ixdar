package ixdar.geometry.mesh.quadlayout.embedding;

import java.math.BigDecimal;

/**
 * Sign-exact orientation predicate for points in barycentric coordinates of a source
 * face, deciding every geometric case of the {@link FaceChordWalk} carve.
 *
 * <p>Evaluates the 3x3 determinant in floating point under a forward error bound,
 * falling back to exact {@link BigDecimal} arithmetic when the bound cannot certify the
 * sign.
 *
 * <p>See also: LCBK19 Section 6.1
 */
public final class ExactBarycentricOrient {

    /** Machine epsilon for IEEE 754 double precision, {@code 2^-53}. */
    private static final double EPSILON = Math.ulp(1.0) / 2.0;

    /**
     * Conservative forward error bound for the floating-point determinant, relative to
     * the sum of the magnitudes of its six products. Deliberately loose: certifying a
     * sign too rarely only costs an exact evaluation, whereas certifying one too often
     * is a wrong answer.
     */
    private static final double ERROR_BOUND = 16.0 * EPSILON;

    private ExactBarycentricOrient() {
    }

    /**
     * Sign of the orientation of three points of a source face, each given by its
     * barycentric triple against that face's corners.
     *
     * @param first  first point's barycentric triple
     * @param second second point's barycentric triple
     * @param third  third point's barycentric triple
     * @return {@code 1} when the triple winds counter-clockwise, {@code -1} when it
     *         winds clockwise, and {@code 0} when the three points are exactly
     *         collinear — in particular when the third lies on the line through the
     *         first two
     */
    public static int sign(double[] first, double[] second, double[] third) {
        double minorA = second[1] * third[2] - second[2] * third[1];
        double minorB = second[0] * third[2] - second[2] * third[0];
        double minorC = second[0] * third[1] - second[1] * third[0];
        double determinant = first[0] * minorA - first[1] * minorB + first[2] * minorC;
        double magnitude = Math.abs(first[0]) * (Math.abs(second[1] * third[2])
                        + Math.abs(second[2] * third[1]))
                + Math.abs(first[1]) * (Math.abs(second[0] * third[2])
                        + Math.abs(second[2] * third[0]))
                + Math.abs(first[2]) * (Math.abs(second[0] * third[1])
                        + Math.abs(second[1] * third[0]));
        if (Math.abs(determinant) > ERROR_BOUND * magnitude) {
            return determinant > 0.0 ? 1 : -1;
        }
        if (first[0] == 0.0 && second[0] == 0.0 && third[0] == 0.0
                || first[1] == 0.0 && second[1] == 0.0 && third[1] == 0.0
                || first[2] == 0.0 && second[2] == 0.0 && third[2] == 0.0) {
            return 0;
        }
        if (samePoint(first, second) || samePoint(second, third) || samePoint(first, third)) {
            return 0;
        }
        return exactSign(first, second, third);
    }

    /**
     * Whether two barycentric triples are element-wise equal, making any determinant
     * containing both rows exactly zero without exact evaluation.
     *
     * @param left  first triple
     * @param right second triple
     * @return true when all three components are equal
     */
    private static boolean samePoint(double[] left, double[] right) {
        return left[0] == right[0] && left[1] == right[1] && left[2] == right[2];
    }

    /**
     * Twice the signed area of the three points, evaluated in floating point. Used only
     * to interpolate a crossing parameter whose sign classification {@link #sign} has
     * already settled exactly; it is never itself compared against a threshold.
     *
     * @param first  first point's barycentric triple
     * @param second second point's barycentric triple
     * @param third  third point's barycentric triple
     * @return the signed area, positive when the points wind counter-clockwise
     */
    public static double area(double[] first, double[] second, double[] third) {
        return first[0] * (second[1] * third[2] - second[2] * third[1])
                - first[1] * (second[0] * third[2] - second[2] * third[0])
                + first[2] * (second[0] * third[1] - second[1] * third[0]);
    }

    /**
     * Sign of the orientation determinant evaluated in exact arithmetic, for the triples
     * the floating-point filter could not certify.
     *
     * @param first  first point's barycentric triple
     * @param second second point's barycentric triple
     * @param third  third point's barycentric triple
     * @return the exact sign of the determinant
     */
    private static int exactSign(double[] first, double[] second, double[] third) {
        BigDecimal minorA = product(second[1], third[2]).subtract(product(second[2], third[1]));
        BigDecimal minorB = product(second[0], third[2]).subtract(product(second[2], third[0]));
        BigDecimal minorC = product(second[0], third[1]).subtract(product(second[1], third[0]));
        return new BigDecimal(first[0]).multiply(minorA)
                .subtract(new BigDecimal(first[1]).multiply(minorB))
                .add(new BigDecimal(first[2]).multiply(minorC))
                .signum();
    }

    /**
     * Exact product of two doubles.
     *
     * @param left  first factor
     * @param right second factor
     * @return their product, without rounding
     */
    private static BigDecimal product(double left, double right) {
        return new BigDecimal(left).multiply(new BigDecimal(right));
    }
}
