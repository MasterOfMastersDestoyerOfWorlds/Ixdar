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
 *   <li>{@link #setA(NormalMatrix)} cold-factors the SPD system (no
 *       fill-reduction permutation yet — the matrix is factored in natural
 *       order so caller-supplied DOF indices remain the column indices of
 *       {@code L}).</li>
 *   <li>{@link #pinDof(int, double)} rank-1 updates L in place.</li>
 *   <li>{@link #solve(double[], double[])} forward-substitutes with
 *       {@code L} then back-substitutes with {@code L}<sup>T</sup>.</li>
 * </ul>
 *
 * <p>The fill-reduction permutation that {@link DirectSolver} applies via
 * reverse Cuthill-McKee makes the cold factor faster and L sparser, but it
 * requires translating DOF indices through the permutation on every pin.
 * For the first cut we accept the slower cold factor in exchange for
 * keeping the pin API trivial; an RCM-aware variant is a follow-up.
 */
public final class IncrementalCholeskySolver {

    private final CholeskyUpdate_DSCC factor = new CholeskyUpdate_DSCC();
    private int dimension;

    /**
     * Cold-factor the SPD system. Must be called before any {@link #pinDof}
     * or {@link #solve}. Existing factor state is discarded.
     *
     * @param matrix the SPD system to factor; only the upper triangle is
     *               used by the Cholesky decomposition
     * @return {@code true} iff the matrix was positive definite and the
     *         factor succeeded
     */
    public boolean setA(NormalMatrix matrix) {
        dimension = matrix.size();
        int[] identityPerm = new int[dimension];
        for (int i = 0; i < dimension; i++) {
            identityPerm[i] = i;
        }
        boolean[] noneFixed = new boolean[dimension];
        NormalMatrix.CompressedSparseColumnArrays csc =
                matrix.toPermutedUpperCompressedSparseColumn(
                        dimension, noneFixed,
                        identityPerm, identityPerm, identityPerm, identityPerm);
        DMatrixSparseCSC ejmlCsc = new DMatrixSparseCSC(
                dimension, dimension, csc.values().length);
        ejmlCsc.col_idx = csc.colPtr();
        ejmlCsc.nz_rows = csc.rowIdx();
        ejmlCsc.nz_values = csc.values();
        ejmlCsc.nz_length = csc.values().length;
        return factor.decompose(ejmlCsc);
    }

    /**
     * Apply {@code A' = A + μ · e_col · e_colᵀ} to the existing L factor in
     * place. The factor matrix's structure is unchanged; only values along
     * the path from {@code col} to the elimination tree's root are touched.
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
        return factor.pinDiagonal(col, mu);
    }

    /**
     * Solve {@code A x = b} using the current L factor: forward-substitute
     * {@code L y = b} then back-substitute {@code Lᵀ x = y}, in place if
     * {@code rhs == out}.
     *
     * @param rhs right-hand-side vector, length {@link #dimension}
     * @param out destination for the solution, length {@link #dimension};
     *            may alias {@code rhs}
     */
    public void solve(double[] rhs, double[] out) {
        if (out != rhs) {
            System.arraycopy(rhs, 0, out, 0, dimension);
        }
        DMatrixSparseCSC lFactor = factor.getL();
        TriangularSolver_DSCC.solveL(lFactor, out);
        TriangularSolver_DSCC.solveTranL(lFactor, out);
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
