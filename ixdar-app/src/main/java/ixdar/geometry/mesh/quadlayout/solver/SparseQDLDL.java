package ixdar.geometry.mesh.quadlayout.solver;

import org.ojalgo.matrix.decomposition.LDL;
import org.ojalgo.matrix.store.MatrixStore;
import org.ojalgo.matrix.store.R064Store;

/**
 * Wrapper around ojAlgo's {@code LDL.R064} (which dispatches to the
 * quasi-definite sparse path internally). Used by the QGP pipeline as a
 * stand-in for sparse Cholesky on regularized Laplacians {@code L + ε I}: the
 * matrix is symmetric, slightly indefinite is OK (LDL handles negative
 * diagonals), and we get fast forward/back substitution after one decomposition.
 *
 * Numerical contract:
 * <ul>
 *   <li>Input must be symmetric (we don't symmetrize for you).
 *   <li>For SPD inputs, residual ‖A x − b‖ / ‖b‖ &lt; 1e-9 on well-conditioned
 *       problems.
 *   <li>Quasi-definite (block-diagonal sign pattern) is also supported, which
 *       is what makes this useful for KKT systems.
 * </ul>
 */
public final class SparseQDLDL {

    private LDL<Double> ldl;
    private int n;

    /**
     * Create an undecomposed solver. Call {@link #decompose} before {@link #solve}.
     */
    public SparseQDLDL() { }

    /**
     * Factor the symmetric (or quasi-definite) sparse matrix {@code m} as
     * {@code L D L^T}. Subsequent {@link #solve} calls reuse the factorization.
     *
     * @param m square symmetric input
     * @throws IllegalArgumentException if {@code m} is not square
     * @return {@code true} if the factorization succeeded
     */
    public boolean decompose(SparseMatrix m) {
        if (m.rows() != m.cols()) {
            throw new IllegalArgumentException("LDL requires square matrix");
        }
        this.n = m.rows();
        this.ldl = LDL.newSparseR064();
        return ldl.decompose(m.ojAlgoStore());
    }

    /**
     * Whether the most recent {@link #decompose} produced a usable factorization
     * (e.g. no zero pivot encountered).
     *
     * @return {@code true} if {@link #solve} can be called
     */
    public boolean isSolvable() {
        return ldl != null && ldl.isSolvable();
    }

    /**
     * Solve {@code A x = rhs} using the previously-computed LDL factorization
     * via forward then back substitution.
     *
     * @param rhs right-hand side vector of length {@link #dimension}
     * @throws IllegalStateException if {@link #decompose} has not been called
     * @throws IllegalArgumentException if {@code rhs.length != dimension()}
     * @return newly allocated solution vector {@code x}
     */
    public double[] solve(double[] rhs) {
        if (ldl == null) throw new IllegalStateException("decompose() not called");
        if (rhs.length != n) throw new IllegalArgumentException("rhs length mismatch");
        R064Store rhsStore = R064Store.FACTORY.column(rhs);
        R064Store xStore = R064Store.FACTORY.make(n, 1);
        MatrixStore<Double> sol = ldl.getSolution(rhsStore, xStore);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = sol.doubleValue(i, 0);
        return out;
    }

    /**
     * Side length of the most recently decomposed square matrix.
     *
     * @return matrix dimension {@code n}, or 0 if {@link #decompose} has not been called
     */
    public int dimension() { return n; }
}
