package ixdar.geometry.mesh.quadlayout.solver;

import org.ojalgo.matrix.decomposition.Cholesky;
import org.ojalgo.matrix.decomposition.LDL;
import org.ojalgo.matrix.store.MatrixStore;
import org.ojalgo.matrix.store.R064Store;

/**
 * Sparse Cholesky decomposition wrapper around ojAlgo's {@code Cholesky.R064}
 * family. Mirrors the {@link SparseLu} API: keep one decomposition object alive
 * across right-hand-side changes and only pay the solve cost on each resolve.
 *
 * <p>Cholesky requires the input to be symmetric positive definite. The cross-field
 * smoothness Hessian (BZK09 eq. 1) is symmetric positive <em>semi</em>-definite by
 * construction; pinning at least one θ per connected component (which the BZK09
 * Voronoi-forest reduction already does) raises it to PD. If decomposition fails
 * with a non-positive pivot we fall back to LDLT, which handles indefinite cases
 * provided the leading principal minors are non-singular.
 *
 * <p>BZK09 §4 Local Search Singularity Optimization explicitly relies on this
 * pattern: factor once, then solve repeatedly for changing right-hand-sides while
 * the matrix structure is fixed.
 */
public final class SparseCholesky {

    private Cholesky<Double> cholesky;
    private LDL<Double> ldl;          // fallback when matrix is PSD but not PD
    private boolean usingLdl;
    private int n;

    /**
     * Creates an empty Cholesky wrapper. Call {@link #decompose(SparseMatrix)} before
     * any solve.
     */
    public SparseCholesky() {
    }

    /**
     * Decompose the symmetric matrix {@code m}. Tries Cholesky first; if the
     * matrix is not positive definite, transparently falls back to LDLT.
     *
     * @param m square symmetric matrix to factor
     * @throws IllegalArgumentException if {@code m} is not square
     * @return true if either Cholesky or LDLT produced a usable factorization
     */
    public boolean decompose(SparseMatrix m) {
        if (m.rows() != m.cols()) {
            throw new IllegalArgumentException("Cholesky requires square matrix");
        }
        this.n = m.rows();
        this.cholesky = Cholesky.R064.make(m.ojAlgoStore());
        boolean ok = cholesky.decompose(m.ojAlgoStore());
        if (ok && cholesky.isSolvable()) {
            this.usingLdl = false;
            this.ldl = null;
            return true;
        }
        // Cholesky rejected the matrix (non-positive pivot). Try LDLT, which
        // handles symmetric indefinite matrices with non-singular leading minors.
        this.cholesky = null;
        this.ldl = LDL.R064.make(m.ojAlgoStore());
        boolean ldlOk = ldl.decompose(m.ojAlgoStore());
        this.usingLdl = ldlOk;
        return ldlOk && ldl.isSolvable();
    }

    /**
     * Re-decompose for the same nonzero pattern. ojAlgo doesn't expose a separate
     * symbolic/numeric phase; this re-decomposes from scratch but keeps the
     * factor object so per-solve allocations are reused.
     *
     * @param m matrix with the same dimensions as the prior {@link #decompose(SparseMatrix)} call
     * @throws IllegalArgumentException if dimensions differ from the prior decomposition
     * @return true if the new factorization is solvable
     */
    public boolean refactor(SparseMatrix m) {
        if (m.rows() != n || m.cols() != n) {
            throw new IllegalArgumentException("refactor matrix dims must match prior decompose");
        }
        return decompose(m);
    }

    /**
     * Whether a factorization is currently held.
     *
     * @return true if the held factorization (Cholesky or LDLT fallback) can be used to solve
     */
    public boolean isSolvable() {
        if (usingLdl) {
            return ldl != null && ldl.isSolvable();
        }
        return cholesky != null && cholesky.isSolvable();
    }

    /**
     * Solve {@code A x = rhs} using the held factorization.
     *
     * @param rhs right-hand-side vector of length {@link #dimension()}
     * @throws IllegalStateException if {@link #decompose(SparseMatrix)} has not been called
     * @throws IllegalArgumentException if {@code rhs.length} differs from the matrix dimension
     * @return solution vector {@code x}
     */
    public double[] solve(double[] rhs) {
        if (cholesky == null && ldl == null) {
            throw new IllegalStateException("decompose() not called");
        }
        if (rhs.length != n) {
            throw new IllegalArgumentException("rhs length mismatch");
        }
        R064Store rhsStore = R064Store.FACTORY.column(rhs);
        R064Store xStore = R064Store.FACTORY.make(n, 1);
        MatrixStore<Double> sol = usingLdl
                ? ldl.getSolution(rhsStore, xStore)
                : cholesky.getSolution(rhsStore, xStore);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = sol.doubleValue(i, 0);
        }
        return out;
    }

    /**
     * Whether the most recent decomposition fell back to LDLT.
     *
     * @return true if the last successful decomposition fell back to LDLT (matrix was not PD)
     */
    public boolean usingLdlFallback() {
        return usingLdl;
    }

    /**
     * Dimension of the matrix that was last decomposed.
     *
     * @return the dimension {@code n} of the most recently decomposed {@code n × n} matrix
     */
    public int dimension() {
        return n;
    }
}