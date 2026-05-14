package unit.mesh.quadlayout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.seamless.exact.ExactArithmetic;
import ixdar.geometry.mesh.quadlayout.seamless.exact.IrrefResult;

/**
 * Hand-verifiable unit tests for {@link ExactArithmetic}. Covers the five MC19
 * algorithms in isolation against small systems whose exact answers can be
 * computed by hand.
 */
class ExactArithmeticTest {

    @Test
    void truncateToFdFixesIdempotently() {
        assertEquals(0.0, ExactArithmetic.truncateToFd(0.0, 16.0));
        assertEquals(1.0, ExactArithmetic.truncateToFd(1.0, 16.0));
        assertEquals(-2.0, ExactArithmetic.truncateToFd(-2.0, 16.0));
        double snappedOnce = ExactArithmetic.truncateToFd(0.3, 16.0);
        double snappedTwice = ExactArithmetic.truncateToFd(snappedOnce, 16.0);
        assertEquals(snappedOnce, snappedTwice,
                "F_d truncation must be idempotent");
    }

    @Test
    void chooseFdScalePicksPowerOfTwoAboveMax() {
        double naturalForThree = Math.pow(2.0, 3);
        double headroom = Math.pow(2.0, ExactArithmetic.FLOAT_PRECISION_HEADROOM);
        assertEquals(naturalForThree * headroom,
                ExactArithmetic.chooseFdScale(new double[] { 1.0, 2.0, 3.0 }));
        assertEquals(headroom, ExactArithmetic.chooseFdScale(new double[] { 0.0 }));
        double scale = ExactArithmetic.chooseFdScale(new double[] { -1.5, 0.4 });
        assertTrue(scale >= 1.5, "scale must dominate max |value|");
    }

    @Test
    void reduceToIrrefHandlesFullRank() {
        BigInteger[][] matrix = bi(new long[][] { { 2, 1 }, { 4, 3 } });
        BigInteger[] rhs = bi(new long[] { 3, 7 });

        IrrefResult result = ExactArithmetic.reduceToIrref(matrix, rhs);

        assertEquals(2, result.rank);
        assertArrayEquals(new int[] { 0, 1 }, result.pivotColumns);
        assertTrue(result.matrix[0][0].signum() != 0);
        assertEquals(0, result.matrix[0][1].signum());
        assertEquals(0, result.matrix[1][0].signum());
        assertTrue(result.matrix[1][1].signum() != 0);
        BigInteger ratio0 = result.rhs[0].divide(result.matrix[0][0]);
        BigInteger ratio1 = result.rhs[1].divide(result.matrix[1][1]);
        assertEquals(BigInteger.ONE, ratio0);
        assertEquals(BigInteger.ONE, ratio1);
    }

    @Test
    void reduceToIrrefHandlesRankDeficient() {
        BigInteger[][] matrix = bi(new long[][] { { 1, 2 }, { 2, 4 } });
        BigInteger[] rhs = bi(new long[] { 3, 6 });

        IrrefResult result = ExactArithmetic.reduceToIrref(matrix, rhs);

        assertEquals(1, result.rank);
        assertArrayEquals(new int[] { 0 }, result.pivotColumns);
        assertEquals(0, result.matrix[1][0].signum());
        assertEquals(0, result.matrix[1][1].signum());
        assertEquals(0, result.rhs[1].signum());
    }

    @Test
    void reduceToIrrefDetectsFreeColumn() {
        BigInteger[][] matrix = bi(new long[][] { { 0, 1, 0 }, { 0, 0, 1 } });
        BigInteger[] rhs = bi(new long[] { 5, 7 });

        IrrefResult result = ExactArithmetic.reduceToIrref(matrix, rhs);

        assertEquals(2, result.rank);
        assertArrayEquals(new int[] { 1, 2 }, result.pivotColumns);
    }

    @Test
    void makeDivWithNoDivisorsJustTruncates() {
        double result = ExactArithmetic.makeDiv(3.5, new BigInteger[0], 16.0);
        assertEquals(ExactArithmetic.truncateToFd(3.5, 16.0), result);
    }

    @Test
    void makeDivYieldsValueDivisibleByLcm() {
        BigInteger[] divisors = { BigInteger.valueOf(2), BigInteger.valueOf(3) };
        double result = ExactArithmetic.makeDiv(7.0, divisors, 64.0);
        double quotient = result / 6.0;
        assertEquals(quotient, ExactArithmetic.truncateToFd(quotient, 64.0),
                "result/lcm must already be in F_d");
    }

    @Test
    void safeDotHandlesEmptyAndZeroes() {
        assertEquals(0.0, ExactArithmetic.safeDot(new BigInteger[0], new double[0], 16.0));
        BigInteger[] coefficients = { BigInteger.ONE, BigInteger.ZERO };
        double[] values = { 0.0, 5.0 };
        assertEquals(0.0, ExactArithmetic.safeDot(coefficients, values, 16.0));
    }

    @Test
    void safeDotMatchesNaiveOnSmallCase() {
        BigInteger[] coefficients = { BigInteger.valueOf(3), BigInteger.valueOf(-2) };
        double[] values = { 2.0, 3.0 };
        assertEquals(0.0, ExactArithmetic.safeDot(coefficients, values, 16.0));
    }

    @Test
    void safeDotPreservesAccuracyWithMixedSigns() {
        BigInteger[] coefficients = { BigInteger.valueOf(5), BigInteger.valueOf(-3), BigInteger.valueOf(2) };
        double[] values = { 1.0, 2.0, 1.5 };
        double expected = 5 * 1.0 - 3 * 2.0 + 2 * 1.5;
        assertEquals(expected, ExactArithmetic.safeDot(coefficients, values, 64.0));
    }

    @Test
    void evaluateRecoversExactSolution() {
        BigInteger[][] matrix = bi(new long[][] { { 2, 1 }, { 4, 3 } });
        BigInteger[] rhs = bi(new long[] { 3, 7 });
        IrrefResult result = ExactArithmetic.reduceToIrref(matrix, rhs);

        double[] xBar = { 0.9, 1.1 };
        double d = ExactArithmetic.chooseFdScale(xBar);
        double[] x = ExactArithmetic.evaluate(result, xBar, d);

        assertEquals(1.0, x[0]);
        assertEquals(1.0, x[1]);
    }

    @Test
    void evaluateSnapsFreeVariablesAndImpliesPivots() {
        BigInteger[][] matrix = bi(new long[][] { { 1, -1, 1 } });
        BigInteger[] rhs = bi(new long[] { 0 });
        IrrefResult result = ExactArithmetic.reduceToIrref(matrix, rhs);

        double[] xBar = { 0.3, 0.5, 0.0 };
        double d = ExactArithmetic.chooseFdScale(new double[] { 1.0, 1.0, 1.0 });
        double[] x = ExactArithmetic.evaluate(result, xBar, d);

        assertEquals(x[0] - x[1] + x[2], 0.0,
                "exact solution must satisfy x0 - x1 + x2 = 0");
    }

    private static BigInteger[][] bi(long[][] data) {
        BigInteger[][] out = new BigInteger[data.length][];
        for (int i = 0; i < data.length; i++) {
            out[i] = new BigInteger[data[i].length];
            for (int j = 0; j < data[i].length; j++) {
                out[i][j] = BigInteger.valueOf(data[i][j]);
            }
        }
        return out;
    }

    private static BigInteger[] bi(long[] data) {
        BigInteger[] out = new BigInteger[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = BigInteger.valueOf(data[i]);
        }
        return out;
    }
}
