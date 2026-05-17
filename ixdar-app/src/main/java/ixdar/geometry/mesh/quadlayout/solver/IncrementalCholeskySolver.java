package ixdar.geometry.mesh.quadlayout.solver;

import org.ejml.data.DMatrixSparseCSC;
import org.ejml.sparse.csc.decomposition.chol.CholeskyUpdate_DSCC;
import org.ejml.sparse.csc.misc.TriangularSolver_DSCC;

import ixdar.geometry.mesh.quadlayout.NormalMatrix;

/**
 * Sparse Cholesky solver that supports per-DOF rank-1 diagonal updates of
 * the L factor in place, for BZK09 greedy mixed-integer rounding.
 *
 * <p>BZK09 §2 / §5 IGM rounding pins one DOF at a time. Each pin is a
 * diagonal perturbation {@code A' = A + μ · eᵢ · eᵢᵀ} of the previous
 * system. Re-factoring per pin is {@code O(N^1.5)}; the equivalent rank-1
 * update of L is {@code O(|elimination-tree path|)} ≈ {@code O(√N)}. For
 * fandisk (~14 k faces, ~600 integer DOFs), this turns minutes per pin
 * into milliseconds.
 *
 * <p>This wrapper:
 * <ul>
 *   <li>{@link #setA(NormalMatrix, OrderingMethod)} cold-factors the SPD
 *       system after applying the caller-chosen fill-reducing permutation
 *       (typically {@link OrderingMethod#AMD} for mesh Laplacians — shorter
 *       columns of L mean cheaper per-pin rank-1 updates).</li>
 *   <li>{@link #pinDof(int, double)} translates the caller's
 *       natural-order DOF index through the permutation and rank-1 updates
 *       L in place.</li>
 *   <li>{@link #solve(double[], double[])} permutes the RHS into the
 *       factored matrix's order, forward + backward substitutes, then
 *       un-permutes the solution back to natural order.</li>
 * </ul>
 */
public final class IncrementalCholeskySolver {

    private final CholeskyUpdate_DSCC factor = new CholeskyUpdate_DSCC();
    private int dimension;
    /** {@code perm[newIndex] = oldIndex} from RCM. */
    private int[] perm;
    /** {@code invPerm[oldIndex] = newIndex}; used to translate caller DOFs. */
    private int[] invPerm;
    /** Reusable RHS scratch in permuted (new-index) order. */
    private double[] permutedRhs;
    /** Reusable solution scratch in permuted (new-index) order. */
    private double[] permutedSolution;

    /**
     * Cold-factor the SPD system. Must be called before any {@link #pinDof}
     * or {@link #solve}. Existing factor state is discarded.
     *
     * <p>Applies the caller-chosen fill-reducing permutation before
     * factoring. The permutation is kept inside this object; callers always
     * use natural DOF indices.
     *
     * @param matrix   the SPD system to factor; only the upper triangle is
     *                 used by the Cholesky decomposition
     * @param ordering fill-reducing column ordering; for the BZK09 IGM
     *                 rounder on mesh Laplacians, {@link OrderingMethod#AMD}
     *                 minimises both cold-factor work and per-pin rank-1
     *                 update cost
     * @return {@code true} iff the matrix was positive definite and the
     *         factor succeeded
     */
    public boolean setA(NormalMatrix matrix, OrderingMethod ordering) {
        dimension = matrix.size();
        boolean[] noneFixed = new boolean[dimension];
        int[] identityCompactOf = new int[dimension];
        int[] identityFullOf = new int[dimension];
        for (int i = 0; i < dimension; i++) {
            identityCompactOf[i] = i;
            identityFullOf[i] = i;
        }

        perm = SolverPermutation.computePermutation(
                matrix, noneFixed, identityCompactOf, dimension, ordering);
        invPerm = new int[dimension];
        for (int i = 0; i < dimension; i++) {
            invPerm[perm[i]] = i;
        }

        NormalMatrix.CompressedSparseColumnArrays csc =
                matrix.toPermutedUpperCompressedSparseColumn(
                        dimension, noneFixed,
                        identityCompactOf, identityFullOf,
                        perm, invPerm);
        DMatrixSparseCSC ejmlCsc = new DMatrixSparseCSC(
                dimension, dimension, csc.values().length);
        ejmlCsc.col_idx = csc.colPtr();
        ejmlCsc.nz_rows = csc.rowIdx();
        ejmlCsc.nz_values = csc.values();
        ejmlCsc.nz_length = csc.values().length;

        permutedRhs = new double[dimension];
        permutedSolution = new double[dimension];
        return factor.decompose(ejmlCsc);
    }

    /**
     * Apply {@code A' = A + μ · e_col · e_colᵀ} to the existing L factor in
     * place, where {@code col} is the caller's natural-order DOF index.
     * The factor matrix's structure is unchanged; only values along the
     * path from the permuted column to the elimination tree's root are
     * touched.
     *
     * @param col column index of the pinned DOF (natural order, same as the
     *            row/column of the {@link NormalMatrix} passed to
     *            {@link #setA})
     * @param mu  positive penalty weight (the squared norm of the rank-1
     *            update vector)
     * @return {@code true} on success; {@code false} if the update produced
     *         a non-positive-definite matrix (cannot happen for {@code μ >
     *         0})
     */
    public boolean pinDof(int col, double mu) {
        return factor.pinDiagonal(invPerm[col], mu);
    }

    /**
     * Solve {@code A x = b} using the current L factor: permute
     * {@code rhs} into factored order, forward-substitute {@code L y = b}
     * then back-substitute {@code Lᵀ x = y}, then un-permute into
     * {@code out}. {@code rhs} and {@code out} are in caller (natural)
     * order; aliasing is allowed.
     *
     * @param rhs right-hand-side vector in natural order, length
     *            {@link #dimension}
     * @param out destination for the solution in natural order, length
     *            {@link #dimension}; may alias {@code rhs}
     */
    public void solve(double[] rhs, double[] out) {
        for (int newIdx = 0; newIdx < dimension; newIdx++) {
            permutedRhs[newIdx] = rhs[perm[newIdx]];
        }
        System.arraycopy(permutedRhs, 0, permutedSolution, 0, dimension);
        DMatrixSparseCSC lFactor = factor.getL();
        TriangularSolver_DSCC.solveL(lFactor, permutedSolution);
        TriangularSolver_DSCC.solveTranL(lFactor, permutedSolution);
        for (int newIdx = 0; newIdx < dimension; newIdx++) {
            out[perm[newIdx]] = permutedSolution[newIdx];
        }
    }

    /**
     * The dimension {@code N} of the factored system, set by the most
     * recent {@link #setA} call.
     *
     * @return matrix dimension
     */
    public int dimension() {
        return dimension;
    }
}
