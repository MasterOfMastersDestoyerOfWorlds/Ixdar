package ixdar.geometry.mesh.quadlayout.solver;

import org.ejml.data.DMatrixSparseCSC;
import org.ejml.data.DMatrixSparseTriplet;
import org.ejml.ops.DConvertMatrixStruct;
import org.ejml.sparse.csc.CommonOps_DSCC;
import org.ejml.sparse.csc.factory.LinearSolverFactory_DSCC;
import org.ejml.interfaces.linsol.LinearSolverSparse;
import org.ejml.sparse.FillReducing;
import org.ejml.data.DMatrixRMaj;

/**
 * Sparse direct solver via EJML's Cholesky (up-looking, sparse-CSC) with AMD
 * fill-reducing ordering. Class name is "SparseLu" for backwards compatibility
 * with existing callers; the actual factorization is sparse Cholesky.
 *
 * <p>EJML's sparse Cholesky requires symmetric positive definite input. The
 * BZK09 cross-field smoothness Hessian is SPD after the Voronoi-forest reduction
 * pins one θ per connected component.
 */
public final class SparseLu {
    public static final double NUM_1e9 = 1e9;

    private LinearSolverSparse<DMatrixSparseCSC, DMatrixRMaj> solver;
    private int n;

    /**
     * TODO: document {@code SparseLu}.
     */
    public SparseLu() {
    }

    /**
     * TODO: document {@code decompose}.
     *
     * @param m TODO: describe
     * @throws IllegalArgumentException TODO: describe
     * @return TODO: describe
     */
    public boolean decompose(SparseMatrix m) {
        if (m.rows() != m.cols()) {
            throw new IllegalArgumentException("requires square matrix");
        }
        this.n = m.rows();
        System.err.println("[sparse-chol] convert start n=" + n);
    
        long t0 = System.nanoTime();
        DMatrixSparseCSC ejml = toEjml(m);
        System.err.printf("[sparse-chol] convert done nnz=%d time=%.2fs%n",
                ejml.nz_length, (System.nanoTime() - t0) / NUM_1e9);
    
        System.err.println("[sparse-chol] factory start");
        solver = LinearSolverFactory_DSCC.cholesky(FillReducing.NONE);
        System.err.println("[sparse-chol] factory done");
    
        System.err.println("[sparse-chol] setA start");
        long t1 = System.nanoTime();
        boolean ok = solver.setA(ejml);
        System.err.printf("[sparse-chol] setA done ok=%s time=%.2fs%n",
                ok, (System.nanoTime() - t1) / NUM_1e9);
    
        return ok;
    }


    /**
     * TODO: document {@code refactor}.
     *
     * @param m TODO: describe
     * @return TODO: describe
     */
    public boolean refactor(SparseMatrix m) {
        return decompose(m);
    }

    /**
     * TODO: document {@code isSolvable}.
     *
     * @return TODO: describe
     */
    public boolean isSolvable() {
        return solver != null;
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
        if (solver == null) {
            throw new IllegalStateException("decompose() not called");
        }
        if (rhs.length != n) {
            throw new IllegalArgumentException("rhs length mismatch");
        }
        DMatrixRMaj b = new DMatrixRMaj(n, 1, true, rhs);
        DMatrixRMaj x = new DMatrixRMaj(n, 1);
        solver.solve(b, x);
        return x.getData().clone();
    }

    /**
     * TODO: document {@code dimension}.
     *
     * @return TODO: describe
     */
    public int dimension() {
        return n;
    }

    private static DMatrixSparseCSC toEjml(SparseMatrix m) {
        int n = m.rows();
        DMatrixSparseTriplet triplets = new DMatrixSparseTriplet(n, n, 0);
    
        // Diagonal: emit once each
        for (int i = 0; i < n; i++) {
            double d = m.get(i, i);
            if (d != 0.0) {
                triplets.addItem(i, i, d);
            }
        }
    
        // Off-diagonal: emit only the lower triangle (r > c).
        // The matrix is symmetric so the upper triangle is implied.
        org.ojalgo.matrix.store.SparseStore<Double> store = m.ojAlgoStore();
        store.nonzeros().forEach(view -> {
            int r = (int) view.row();
            int c = (int) view.column();
            if (r < c) {              // was: r > c
                triplets.addItem(r, c, view.doubleValue());
            }
        });
    
        DMatrixSparseCSC csc = new DMatrixSparseCSC(n, n, triplets.nz_length);
        DConvertMatrixStruct.convert(triplets, csc);
        return csc;
    }
}