package ixdar.geometry.mesh.quadlayout.solver;

import org.ojalgo.matrix.decomposition.LU;
import org.ojalgo.matrix.store.MatrixStore;
import org.ojalgo.matrix.store.R064Store;

/**
 * Sparse LU decomposition wrapper around ojAlgo's {@code LU.R064} family.
 * Exposes a Bommes-IGM-friendly {@code refactor + solve} loop: keep one
 * decomposition object alive across parameter changes; only the solve cost is
 * paid on each resolve.
 *
 * Note on the underlying ojAlgo class: the package-private
 * {@code SparseLU} implementation requires CSC inputs through internal calls
 * we cannot reach. The public {@code LU.R064} factory dispatches to the
 * sparsity-aware path automatically, so we use that.
 */
public final class SparseLu {

    private LU<Double> lu;
    private int n;

    public SparseLu() { }

    public boolean decompose(SparseMatrix m) {
        if (m.rows() != m.cols()) {
            throw new IllegalArgumentException("LU requires square matrix");
        }
        this.n = m.rows();
        this.lu = LU.newSparseR064();
        return lu.decompose(m.ojAlgoStore());
    }

    /**
     * Cheap re-factorization for the same nonzero pattern (Bommes IGM resolve
     * path). ojAlgo doesn't expose pattern-preserving refactor symbolic+numeric
     * separation; this currently re-decomposes from scratch but keeps the
     * decomposition object so allocations are reused.
     */
    public boolean refactor(SparseMatrix m) {
        if (m.rows() != n || m.cols() != n) {
            throw new IllegalArgumentException("refactor matrix dims must match prior decompose");
        }
        return lu.decompose(m.ojAlgoStore());
    }

    public boolean isSolvable() {
        return lu != null && lu.isSolvable();
    }

    public double[] solve(double[] rhs) {
        if (lu == null) throw new IllegalStateException("decompose() not called");
        if (rhs.length != n) throw new IllegalArgumentException("rhs length mismatch");
        R064Store rhsStore = R064Store.FACTORY.column(rhs);
        R064Store xStore = R064Store.FACTORY.make(n, 1);
        MatrixStore<Double> sol = lu.getSolution(rhsStore, xStore);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = sol.doubleValue(i, 0);
        return out;
    }

    public int dimension() { return n; }
}
