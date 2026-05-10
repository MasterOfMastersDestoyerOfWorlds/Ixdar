package ixdar.geometry.mesh.quadlayout.solver;

import org.ejml.data.DMatrixSparseCSC;
import org.ejml.data.DMatrixSparseTriplet;
import org.ejml.ops.DConvertMatrixStruct;
import org.ejml.sparse.csc.CommonOps_DSCC;
import org.ejml.sparse.csc.factory.LinearSolverFactory_DSCC;
import org.ejml.interfaces.linsol.LinearSolverSparse;

import org.ojalgo.matrix.store.SparseStore;
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
     * Creates an empty solver. Call {@link #decompose(SparseMatrix)} before any solve.
     */
    public SparseLu() {
    }

    /**
     * Convert {@code m} to EJML CSC and run sparse Cholesky factorization.
     *
     * @param m square SPD matrix to factor
     * @throws IllegalArgumentException if {@code m} is not square
     * @return true if {@code setA} accepted the factorization
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
     * Re-decompose for the same nonzero pattern; EJML doesn't expose a
     * separate symbolic/numeric phase so this re-runs the full factorization.
     *
     * @param m matrix to refactor
     * @return true if the new factorization is usable
     */
    public boolean refactor(SparseMatrix m) {
        return decompose(m);
    }

    /**
     * Whether a factorization is currently held.
     *
     * @return true if a factorization is held and ready to solve
     */
    public boolean isSolvable() {
        return solver != null;
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
     * Dimension of the matrix that was last decomposed.
     *
     * @return the dimension {@code n} of the most recently decomposed {@code n × n} matrix
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
        SparseStore<Double> store = m.ojAlgoStore();
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