package ixdar.geometry.mesh.quadlayout.embedding;

/**
 * Sign-exact orientation predicate for points in barycentric coordinates of a source
 * face, used by the strictly-inside assertion of face splits.
 *
 * <p>Evaluates the 3x3 determinant in floating point under a forward error bound,
 * falling back to exact expansion arithmetic when the bound cannot certify the sign.
 *
 * <p>See also: LCBK19 Section 6.1
 */
public final class ExactBarycentricOrient {

    /** Exact evaluations taken since startup, a filter-miss diagnostic. */
    public static long exactSignCallCount;

    /** Machine epsilon for IEEE 754 double precision, {@code 2^-53}. */
    private static final double EPSILON = Math.ulp(1.0) / 2.0;

    /**
     * Conservative forward error bound for the floating-point determinant, relative to
     * the sum of the magnitudes of its six products. Deliberately loose: certifying a
     * sign too rarely only costs an exact evaluation, whereas certifying one too often
     * is a wrong answer.
     */
    private static final double ERROR_BOUND = 16.0 * EPSILON;

    /**
     * Expansion component bound: six triple products of four components each, with
     * headroom — grow-expansion never lengthens past one component per addend.
     */
    private static final int EXPANSION_CAPACITY = 26;

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
     * Sign of the orientation determinant evaluated exactly in floating-point
     * expansion arithmetic (Shewchuk 1997): each of the six triple products is
     * decomposed into exact double components via fused multiply-add, and all
     * components are folded into one nonoverlapping expansion whose largest
     * component carries the sign.
     *
     * @param first  first point's barycentric triple
     * @param second second point's barycentric triple
     * @param third  third point's barycentric triple
     * @return the exact sign of the determinant
     */
    private static int exactSign(double[] first, double[] second, double[] third) {
        exactSignCallCount++;
        double[] expansion = new double[EXPANSION_CAPACITY];
        int size = 0;
        size = accumulateTripleProduct(expansion, size, first[0], second[1], third[2], 1.0);
        size = accumulateTripleProduct(expansion, size, first[0], second[2], third[1], -1.0);
        size = accumulateTripleProduct(expansion, size, first[1], second[0], third[2], -1.0);
        size = accumulateTripleProduct(expansion, size, first[1], second[2], third[0], 1.0);
        size = accumulateTripleProduct(expansion, size, first[2], second[0], third[1], 1.0);
        size = accumulateTripleProduct(expansion, size, first[2], second[1], third[0], -1.0);
        return size == 0 ? 0 : expansion[size - 1] > 0.0 ? 1 : -1;
    }

    /**
     * Fold one signed triple product into the expansion as four exact double
     * components: {@code a*b = p + pError} by fused multiply-add, then each half
     * times {@code c} splits the same way.
     *
     * @param expansion nonoverlapping expansion accumulator, ascending magnitude
     * @param size      live component count of {@code expansion}
     * @param factorA   first factor
     * @param factorB   second factor
     * @param factorC   third factor
     * @param sign      {@code 1.0} to add the product, {@code -1.0} to subtract it
     * @return the expansion's new component count
     */
    private static int accumulateTripleProduct(double[] expansion, int size, double factorA,
            double factorB, double factorC, double sign) {
        double product = factorA * factorB;
        double productError = Math.fma(factorA, factorB, -product);
        double high = product * factorC;
        double highError = Math.fma(product, factorC, -high);
        double low = productError * factorC;
        double lowError = Math.fma(productError, factorC, -low);
        int grown = growExpansion(expansion, size, sign * lowError);
        grown = growExpansion(expansion, grown, sign * low);
        grown = growExpansion(expansion, grown, sign * highError);
        return growExpansion(expansion, grown, sign * high);
    }

    /**
     * Shewchuk's GROW-EXPANSION with zero elimination: add one double to a
     * nonoverlapping expansion, keeping it nonoverlapping and zero-free.
     *
     * @param expansion expansion components in ascending magnitude order
     * @param size      live component count of {@code expansion}
     * @param value     double to add
     * @return the expansion's new component count
     */
    private static int growExpansion(double[] expansion, int size, double value) {
        double carry = value;
        int written = 0;
        for (int index = 0; index < size; index++) {
            double component = expansion[index];
            double sum = carry + component;
            double componentVirtual = sum - carry;
            double carryVirtual = sum - componentVirtual;
            double error = (carry - carryVirtual) + (component - componentVirtual);
            if (error != 0.0) {
                expansion[written++] = error;
            }
            carry = sum;
        }
        if (carry != 0.0) {
            expansion[written++] = carry;
        }
        return written;
    }

}
