package org.ejml.sparse.csc.decomposition.chol;

/**
 * Sparse Cholesky factorization supporting rank-1 in-place updates of L.
 *
 * <p>Pinning a DOF is a diagonal perturbation, so L is updated along the elimination-tree path
 * from that column to the root, introducing no new fill. Downdate is not implemented.
 *
 * <p>See also: Davis, Direct Methods for Sparse Linear Systems Section 4.10
 */
public class CholeskyUpdate_DSCC extends CholeskyUpLooking_DSCC {

    /** Epsilon under which a working-vector entry is treated as zero. */
    public static final double WORKING_VECTOR_EPS = 1.0e-15;

    /**
     * Apply {@code A' = A + σ · w · wᵀ} to the existing L factor in place, mutating
     * {@code L.nz_values}, by rotating away each non-zero of the working vector in column order.
     * Rotation fill always lands on structurally non-zero rows of {@code L}.
     *
     * @param col column index of the diagonal entry to pin
     * @param mu  positive pin weight; pre-encoded into the working vector as
     *            {@code √μ}
     * @throws UnsupportedOperationException if downdate is requested (not yet
     *         implemented; IGM rounding only updates)
     * @throws IllegalArgumentException      when {@code mu} is non-positive
     * @return {@code true} if the update succeeded; {@code false} if a
     *         downdate produced a non-positive-definite matrix
     */
    public boolean pinDiagonal(int col, double mu) {
        if (mu <= 0.0) {
            throw new IllegalArgumentException("pin weight must be positive");
        }
        int n = L.numCols;
        double[] working = new double[n];
        working[col] = Math.sqrt(mu);
        for (int columnIndex = col; columnIndex < n; columnIndex++) {
            double alpha = working[columnIndex];
            if (Math.abs(alpha) < WORKING_VECTOR_EPS) {
                continue;
            }
            int diagonalEntry = L.col_idx[columnIndex];
            double diagonalValue = L.nz_values[diagonalEntry];
            double radiusSquared = diagonalValue * diagonalValue + alpha * alpha;
            if (radiusSquared <= 0.0) {
                return false;
            }
            double newDiagonal = Math.sqrt(radiusSquared);
            double cosTheta = diagonalValue / newDiagonal;
            double sinTheta = alpha / newDiagonal;
            L.nz_values[diagonalEntry] = newDiagonal;
            int columnEnd = L.col_idx[columnIndex + 1];
            for (int p = diagonalEntry + 1; p < columnEnd; p++) {
                int row = L.nz_rows[p];
                double lValue = L.nz_values[p];
                double wValue = working[row];
                L.nz_values[p] = cosTheta * lValue + sinTheta * wValue;
                working[row] = -sinTheta * lValue + cosTheta * wValue;
            }
        }
        return true;
    }
}
