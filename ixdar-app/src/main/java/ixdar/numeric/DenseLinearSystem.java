package ixdar.numeric;

import ixdar.numeric.sparse.chol.CholeskyBackend;

/**
 * Small dense symmetric positive semi-definite systems, the normal equations of a local
 * least-squares fit, solved by Cholesky. Too small for {@link CholeskyBackend}'s sparse
 * factorisation to pay off, but singular systems follow its policy.
 */
public final class DenseLinearSystem {

    private DenseLinearSystem() {
    }

    /**
     * Solve Ax = b by Cholesky. When A is singular to working precision, the factorisation is
     * retried with {@link CholeskyBackend#diagonalShiftFor}'s shift on the diagonal; any pivot still
     * no larger than that shift is a direction the fit cannot determine, and its unknown is zeroed.
     *
     * @param a symmetric coefficient matrix of size {@code n x n}, read only
     * @param b right-hand-side vector of length {@code n}, read only
     * @param n system dimension
     * @return solution vector {@code x}
     */
    public static float[] solve(float[][] a, float[] b, int n) {
        double[] diagonal = new double[n];
        for (int row = 0; row < n; row++) {
            diagonal[row] = a[row][row];
        }
        double shift = CholeskyBackend.diagonalShiftFor(diagonal, new boolean[n]);
        double[][] lower = factor(a, n, shift, false);
        if (lower == null) {
            lower = factor(a, n, shift, true);
        }
        double[] y = new double[n];
        for (int row = 0; row < n; row++) {
            if (lower[row][row] == 0.0) {
                continue;
            }
            double sum = b[row];
            for (int column = 0; column < row; column++) {
                sum -= lower[row][column] * y[column];
            }
            y[row] = sum / lower[row][row];
        }
        float[] x = new float[n];
        double[] solution = new double[n];
        for (int row = n - 1; row >= 0; row--) {
            if (lower[row][row] == 0.0) {
                continue;
            }
            double sum = y[row];
            for (int below = row + 1; below < n; below++) {
                sum -= lower[below][row] * solution[below];
            }
            solution[row] = sum / lower[row][row];
            x[row] = (float) solution[row];
        }
        return x;
    }

    /**
     * The lower Cholesky factor of {@code a}, or of {@code a + shift I} when shifted.
     *
     * @param a       symmetric matrix, read only
     * @param n       its dimension
     * @param shift   the smallest pivot accepted, and the diagonal shift when {@code shifted}
     * @param shifted whether {@code shift} is added to the diagonal
     * @return the factor; unshifted, null at the first pivot no larger than {@code shift}; shifted,
     *         a zero row and column for every such pivot
     */
    private static double[][] factor(float[][] a, int n, double shift, boolean shifted) {
        double[][] lower = new double[n][n];
        for (int column = 0; column < n; column++) {
            double pivot = a[column][column] + (shifted ? shift : 0.0);
            for (int left = 0; left < column; left++) {
                pivot -= lower[column][left] * lower[column][left];
            }
            if (!(pivot > shift)) {
                if (!shifted) {
                    return null;
                }
                continue;
            }
            double root = Math.sqrt(pivot);
            lower[column][column] = root;
            for (int row = column + 1; row < n; row++) {
                double sum = a[row][column];
                for (int left = 0; left < column; left++) {
                    sum -= lower[row][left] * lower[column][left];
                }
                lower[row][column] = sum / root;
            }
        }
        return lower;
    }
}
