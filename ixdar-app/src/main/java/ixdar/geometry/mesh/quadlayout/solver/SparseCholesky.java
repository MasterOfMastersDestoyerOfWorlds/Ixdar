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
     * TODO: document {@code SparseCholesky}.
     */
    public SparseCholesky() {
    }

    /**
     * Decompose the symmetric matrix {@code m}. Tries Cholesky first; if the
     * matrix is not positive definite, transparently falls back to LDLT.
     *
     * @param m TODO: describe
     * @throws IllegalArgumentException TODO: describe
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
     * @param m TODO: describe
     * @throws IllegalArgumentException TODO: describe
     * @return TODO: describe
     */
    public boolean refactor(SparseMatrix m) {
        if (m.rows() != n || m.cols() != n) {
            throw new IllegalArgumentException("refactor matrix dims must match prior decompose");
        }
        return decompose(m);
    }

    /**
     * TODO: document {@code isSolvable}.
     *
     * @return TODO: describe
     */
    public boolean isSolvable() {
        if (usingLdl) {
            return ldl != null && ldl.isSolvable();
        }
        return cholesky != null && cholesky.isSolvable();
    }

    /**
     * TODO: document {@code solve}.
     *
     * @param rhs TODO: describe
     * @throws IllegalStateException TODO: describe
     * @throws IllegalArgumentException TODO: describe
     * @return TODO: describe
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
     * TODO: document {@code usingLdlFallback}.
     *
     * @return TODO: describe
     */
    public boolean usingLdlFallback() {
        return usingLdl;
    }

    /**
     * TODO: document {@code dimension}.
     *
     * @return TODO: describe
     */
    public int dimension() {
        return n;
    }
}