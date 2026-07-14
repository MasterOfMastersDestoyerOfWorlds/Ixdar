package ixdar.geometry.mesh.quadlayout.embedding;

import java.math.BigDecimal;

/**
 * Sign-exact orientation predicate for points given in barycentric coordinates of a
 * source face, used by {@link FaceChordWalk} to decide every geometric case of the
 * LCBK19 §6.1 carve.
 *
 * <p>Neither LCBK19 nor LCK21a discusses numerics; this class is an engineering
 * addition, and it exists because both of the obvious alternatives are unsound.
 *
 * <p>The first alternative — comparing a signed area against an absolute constant, as
 * the carve used to — is scale-dependent nonsense. A signed area scales with the
 * product of the two edge lengths it spans, so in a triangle of barycentric extent
 * {@code 1e-7} every orientation value falls below a {@code 1e-9} threshold and the
 * triangle "contains" every point in the plane.
 *
 * <p>The second alternative — projecting to 2D by dropping the first barycentric
 * coordinate, then running an exact 2D predicate — is exact about the wrong thing. The
 * information that a point lies on the face's boundary is carried by a coordinate being
 * exactly zero, and the projection throws that coordinate away. A crossing on the edge
 * opposite corner 0 is built as {@code (0, 1 - p, p)}, but {@code (1 - p) + p} is not
 * {@code 1.0} in IEEE arithmetic, so the projected point misses the edge by an ulp. An
 * exact predicate then faithfully reports it as interior, the walk splits a face rather
 * than an edge, and the resulting vertex is registered in only one of the two source
 * faces that share the crossing — which is a crash, one carve step later, in a
 * different face.
 *
 * <p>The full 3x3 determinant of the three barycentric triples has neither defect. It
 * is the same signed area, but computed in coordinates that keep the constraint: the
 * orientation of the two corners opposite corner {@code k} against a point {@code b}
 * evaluates to exactly {@code b[k]}. So "on that edge" is exactly "{@code b[k] == 0}",
 * which is representable, is what the carve actually constructs, and is preserved
 * exactly by the interpolation that mints split vertices.
 *
 * <p>Evaluation is a floating-point determinant guarded by its own forward error bound,
 * falling back to exact {@link BigDecimal} arithmetic when the bound cannot certify the
 * sign. Doubles convert to {@code BigDecimal} without loss, so the fallback is exact
 * rather than merely more precise.
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
        return exactSign(first, second, third);
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
