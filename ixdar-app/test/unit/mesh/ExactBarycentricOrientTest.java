package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.ExactBarycentricOrient;

/**
 * Cross-checks the expansion-arithmetic orientation predicate against a
 * {@link BigDecimal} reference on random, dyadic-interpolated, exactly collinear,
 * and permuted triples — the sign must match bit-for-bit in every case.
 */
class ExactBarycentricOrientTest {

    /** Random triples exercised per scale regime. */
    private static final int RANDOM_TRIALS = 2000;

    /** Nested midpoint interpolations, deep enough to defeat the float filter. */
    private static final int INTERPOLATION_DEPTH = 40;

    /** Fixed seed keeping the trials reproducible. */
    private static final long SEED = 987654321L;

    @Test
    void matchesBigDecimalOnRandomTriples() {
        Random random = new Random(SEED);
        for (int trial = 0; trial < RANDOM_TRIALS; trial++) {
            double scale = Math.pow(10.0, random.nextInt(9) - 4);
            double[] first = randomTriple(random, scale);
            double[] second = randomTriple(random, scale);
            double[] third = randomTriple(random, scale);
            assertAgreesWithReference(first, second, third);
        }
    }

    @Test
    void matchesBigDecimalOnDeepDyadicCollinearBundles() {
        Random random = new Random(SEED);
        for (int trial = 0; trial < RANDOM_TRIALS; trial++) {
            double[] start = randomTriple(random, 1.0);
            double[] end = randomTriple(random, 1.0);
            double[] left = start.clone();
            double[] right = end.clone();
            for (int depth = 0; depth < INTERPOLATION_DEPTH; depth++) {
                double[] midpoint = new double[] { (left[0] + right[0]) / 2,
                        (left[1] + right[1]) / 2, (left[2] + right[2]) / 2 };
                if (random.nextBoolean()) {
                    left = midpoint;
                } else {
                    right = midpoint;
                }
            }
            assertAgreesWithReference(start, left, right);
            assertAgreesWithReference(left, right, end);
            double[] nudged = new double[] { left[0], left[1] + Math.ulp(left[1]), left[2] };
            assertAgreesWithReference(nudged, right, end);
        }
    }

    @Test
    void matchesBigDecimalOnExactZeroAndPermutations() {
        double[] first = new double[] { 0.25, 0.5, 0.25 };
        double[] second = new double[] { 0.125, 0.75, 0.125 };
        double[] third = new double[] { 0.1875, 0.625, 0.1875 };
        assertAgreesWithReference(first, second, third);
        assertAgreesWithReference(second, third, first);
        assertAgreesWithReference(third, first, second);
        assertAgreesWithReference(first, third, second);
        assertAgreesWithReference(first, first, second);
        assertAgreesWithReference(first, second, second);
    }

    /**
     * Assert the production predicate and the {@link BigDecimal} reference agree on
     * one triple.
     *
     * @param first  first point's barycentric triple
     * @param second second point's barycentric triple
     * @param third  third point's barycentric triple
     */
    private void assertAgreesWithReference(double[] first, double[] second, double[] third) {
        assertEquals(referenceSign(first, second, third),
                ExactBarycentricOrient.sign(first, second, third),
                "sign disagrees on " + Arrays.toString(first) + " "
                        + Arrays.toString(second) + " "
                        + Arrays.toString(third));
    }

    /**
     * A random barycentric-like triple; components may be negative to exercise
     * every sign combination of the determinant's products.
     *
     * @param random source of coordinates
     * @param scale  magnitude regime of the triple
     * @return a fresh triple
     */
    private double[] randomTriple(Random random, double scale) {
        return new double[] { (random.nextDouble() - 0.5) * scale,
                (random.nextDouble() - 0.5) * scale, (random.nextDouble() - 0.5) * scale };
    }

    /**
     * The orientation determinant's sign in exact {@link BigDecimal} arithmetic,
     * the reference the expansion evaluation must reproduce.
     *
     * @param first  first point's barycentric triple
     * @param second second point's barycentric triple
     * @param third  third point's barycentric triple
     * @return the exact sign of the determinant
     */
    private int referenceSign(double[] first, double[] second, double[] third) {
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
    private BigDecimal product(double left, double right) {
        return new BigDecimal(left).multiply(new BigDecimal(right));
    }
}
