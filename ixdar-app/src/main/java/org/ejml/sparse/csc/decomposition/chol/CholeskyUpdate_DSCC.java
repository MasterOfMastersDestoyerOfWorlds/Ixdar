/*
 * Copyright (c) 2026.
 *
 * Same-package extension to EJML 0.43.1's CholeskyUpLooking_DSCC, adding
 * incremental rank-1 update of the L factor per Davis ch. 4.10. Lives in
 * org.ejml.sparse.csc.decomposition.chol so it can touch the parent's
 * package-private L, parent[], and gw fields.
 *
 * EJML itself is Apache 2.0 (LICENSE-2.0.txt); same-package extension is
 * mechanically straightforward.
 */

package org.ejml.sparse.csc.decomposition.chol;

import org.ejml.data.DMatrixSparseCSC;

/**
 * Sparse Cholesky factorization supporting rank-1 in-place updates of the
 * L factor, by extending EJML 0.43.1's {@link CholeskyUpLooking_DSCC}.
 *
 * <p>BZK09's greedy mixed-integer rounder pins one DOF at a time. Each pin
 * is a diagonal perturbation {@code A' = A + μ · eᵢ · eᵢᵀ} of the previous
 * system — exactly a rank-1 update. The Cholesky factor L evolves
 * incrementally along a path in the elimination tree from column {@code i}
 * to the root, costing {@code O(|path|)} ≈ {@code O(√N)} rather than the
 * {@code O(N^1.5)} of a fresh factorization. On meshes with hundreds of
 * integer DOFs (fandisk ≈ 600), this turns minutes of re-factorization into
 * seconds of incremental updates.
 *
 * <p>The algorithm is Davis, <em>Direct Methods for Sparse Linear
 * Systems</em>, ch. 4.10 (rank-1 update / downdate of LL<sup>T</sup>):
 * walk the elimination tree from the first non-zero of {@code w} to the
 * root, applying one Givens rotation per visited column. For
 * single-entry {@code w = √μ · eᵢ} (the diagonal-pin case), the rotation
 * only needs to walk L's structurally non-zero rows in each visited column
 * — no new fill is introduced, since {@code A}'s non-zero pattern is
 * unchanged.
 *
 * <p>Downdate ({@code σ = -1}) is supported for completeness but unused by
 * the IGM rounder, which only adds pins.
 */
public class CholeskyUpdate_DSCC extends CholeskyUpLooking_DSCC {

    /** Epsilon under which a working-vector entry is treated as zero. */
    public static final double WORKING_VECTOR_EPS = 1.0e-15;

    /**
     * Apply {@code A' = A + σ · w · wᵀ} to the existing L factor in place,
     * mutating {@code L.nz_values} and the working vector {@code w}. The
     * algorithm walks each row {@code j} where {@code w[j] ≠ 0} in order,
     * applying a Givens (or hyperbolic, for downdate) rotation that zeros
     * {@code w[j]} and updates {@code L}'s column {@code j} below the
     * diagonal. Subsequent rows of {@code w} are filled in by the rotation
     * — they always land on rows where {@code L}'s column already has a
     * structural non-zero, so no new fill is introduced.
     *
     * @param sigma {@code +1.0} for update, {@code -1.0} for downdate
     * @param w     length-{@code N} working vector; mutated in place. For a
     *              diagonal pin at column {@code col} with weight μ, the
     *              caller pre-fills {@code w[col] = √μ} and zero elsewhere.
     * @return {@code true} if the update succeeded; {@code false} if a
     *         downdate produced a non-positive-definite matrix
     * @throws UnsupportedOperationException if {@code sigma != 1.0} (downdate
     *         not yet implemented; IGM rounding only updates)
     */
    public boolean rankOneUpdate(double sigma, double[] w) {
        if (sigma != 1.0) {
            throw new UnsupportedOperationException(
                    "downdate (sigma = -1) not implemented; IGM rounding only updates");
        }
        DMatrixSparseCSC factor = this.L;
        int n = factor.numCols;
        for (int columnIndex = 0; columnIndex < n; columnIndex++) {
            double alpha = w[columnIndex];
            if (Math.abs(alpha) < WORKING_VECTOR_EPS) {
                continue;
            }
            // Apply a Givens rotation that zeros w[columnIndex] against the
            // diagonal of L. For sigma = +1 the rotation is orthogonal:
            //   [c  s] [diagonalValue]   [newDiagonal]
            //   [-s c] [alpha        ] = [  0        ]
            // with c² + s² = 1. Then propagate the rotation to every row of
            // L's column columnIndex below the diagonal.
            int diagonalEntry = factor.col_idx[columnIndex];
            double diagonalValue = factor.nz_values[diagonalEntry];
            double radiusSquared = diagonalValue * diagonalValue + alpha * alpha;
            if (radiusSquared <= 0.0) {
                return false;
            }
            double newDiagonal = Math.sqrt(radiusSquared);
            double cosTheta = diagonalValue / newDiagonal;
            double sinTheta = alpha / newDiagonal;
            factor.nz_values[diagonalEntry] = newDiagonal;
            int columnEnd = factor.col_idx[columnIndex + 1];
            for (int p = diagonalEntry + 1; p < columnEnd; p++) {
                int row = factor.nz_rows[p];
                double lValue = factor.nz_values[p];
                double wValue = w[row];
                factor.nz_values[p] = cosTheta * lValue + sinTheta * wValue;
                w[row] = -sinTheta * lValue + cosTheta * wValue;
            }
            // w[columnIndex] is logically zero now; subsequent iterations
            // advance past columnIndex so we don't need to clear it.
        }
        return true;
    }

    /**
     * Convenience wrapper for the diagonal-pin case
     * {@code A' = A + μ · e_col · e_colᵀ}: builds a single-entry working
     * vector and calls {@link #rankOneUpdate(double, double[])} with
     * {@code σ = +1}. Used by BZK09 greedy MI rounding when a DOF is
     * snapped to an integer at penalty weight {@code μ}.
     *
     * @param col column index of the pinned DOF
     * @param mu  positive penalty weight (the squared norm of the rank-1
     *            update vector)
     * @return {@code true} on success
     * @throws IllegalArgumentException if {@code mu <= 0}
     */
    public boolean pinDiagonal(int col, double mu) {
        if (mu <= 0.0) {
            throw new IllegalArgumentException("pin weight must be positive");
        }
        int n = L.numCols;
        double[] working = new double[n];
        working[col] = Math.sqrt(mu);
        return rankOneUpdate(1.0, working);
    }
}
