package ixdar.geometry.mesh.quadlayout.solver;

import no.uib.cipr.matrix.DenseVector;
import no.uib.cipr.matrix.sparse.CompRowMatrix;
import no.uib.cipr.matrix.sparse.FlexCompRowMatrix;
import no.uib.cipr.matrix.sparse.SparseVector;

/**
 * Real-valued sparse matrix backed by MTJ's CSR-family classes. Used by the QGP
 * pipeline whenever {@link SparseMatrix}'s ojAlgo backend would overflow its
 * 32-bit flat element index — i.e. for {@code stride > sqrt(Integer.MAX_VALUE)
 * (~46340)}, which is the case for any IGM Hessian or cross-field LSQ from a
 * mesh of more than a few thousand faces.
 *
 * <p>Build-time storage is {@link FlexCompRowMatrix} (per-row hash-style
 * sparse vectors): supports cheap {@link #set} / {@link #add} regardless of
 * which entries already exist. For the final solve we materialize a static
 * {@link CompRowMatrix} (CSR with fixed nonzero pattern) which is what MTJ's
 * iterative solvers consume.
 *
 * <p>API mirrors {@link SparseMatrix} for drop-in substitution at IGM /
 * cross-field call sites: {@code set(i, j, v)}, {@code add(i, j, v)},
 * {@code multiply(rhs)}, {@code transpose()}.
 */
public final class MtjSparseMatrix {

    private final int rows;
    private final int cols;
    private final FlexCompRowMatrix store;

    private CompRowMatrix compactCache;
    private boolean dirty;

    public MtjSparseMatrix(int rows, int cols) {
        if (rows <= 0 || cols <= 0) {
            throw new IllegalArgumentException("rows/cols must be positive");
        }
        this.rows = rows;
        this.cols = cols;
        this.store = new FlexCompRowMatrix(rows, cols);
        this.dirty = true;
    }

    public static MtjSparseMatrix identity(int n) {
        MtjSparseMatrix m = new MtjSparseMatrix(n, n);
        for (int i = 0; i < n; i++) m.set(i, i, 1.0);
        return m;
    }

    public static MtjSparseMatrix fromTriplets(int rows, int cols, int[] is, int[] js, double[] vs) {
        if (is.length != js.length || is.length != vs.length) {
            throw new IllegalArgumentException("triplet arrays must match length");
        }
        MtjSparseMatrix m = new MtjSparseMatrix(rows, cols);
        for (int k = 0; k < is.length; k++) m.add(is[k], js[k], vs[k]);
        return m;
    }

    public static MtjSparseMatrix fromDense(double[][] dense) {
        int r = dense.length;
        int c = dense[0].length;
        MtjSparseMatrix m = new MtjSparseMatrix(r, c);
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < c; j++) {
                if (dense[i][j] != 0.0) m.set(i, j, dense[i][j]);
            }
        }
        return m;
    }

    public int rows() { return rows; }
    public int cols() { return cols; }

    public void set(int i, int j, double v) {
        boundsCheck(i, j);
        store.set(i, j, v);
        dirty = true;
    }

    public void add(int i, int j, double v) {
        boundsCheck(i, j);
        if (v == 0.0) return;
        store.add(i, j, v);
        dirty = true;
    }

    public double get(int i, int j) {
        boundsCheck(i, j);
        return store.get(i, j);
    }

    public int countNonzeros() {
        int total = 0;
        for (int i = 0; i < rows; i++) total += store.getRow(i).getUsed();
        return total;
    }

    /** y = this * x. */
    public double[] multiply(double[] x) {
        if (x.length != cols) {
            throw new IllegalArgumentException("vector length mismatch: " + x.length + " vs " + cols);
        }
        DenseVector xv = new DenseVector(x);
        DenseVector yv = new DenseVector(rows);
        store.mult(xv, yv);
        return yv.getData().clone();
    }

    /** Returns a fresh transpose. */
    public MtjSparseMatrix transpose() {
        MtjSparseMatrix t = new MtjSparseMatrix(cols, rows);
        for (int i = 0; i < rows; i++) {
            SparseVector row = store.getRow(i);
            int[] idx = row.getIndex();
            double[] data = row.getData();
            int used = row.getUsed();
            for (int k = 0; k < used; k++) {
                t.set(idx[k], i, data[k]);
            }
        }
        return t;
    }

    /**
     * Materialize the current state as an MTJ {@link CompRowMatrix} (static-
     * pattern CSR). Cached and reused as long as the matrix isn't mutated;
     * subsequent calls to {@link #set} / {@link #add} invalidate the cache.
     */
    public CompRowMatrix toCompRow() {
        if (compactCache != null && !dirty) return compactCache;

        // Build the column-index pattern row by row.
        int[][] pattern = new int[rows][];
        for (int i = 0; i < rows; i++) {
            SparseVector row = store.getRow(i);
            int used = row.getUsed();
            int[] idx = row.getIndex();
            int[] cols = new int[used];
            System.arraycopy(idx, 0, cols, 0, used);
            // CompRowMatrix requires sorted column indices per row.
            java.util.Arrays.sort(cols);
            pattern[i] = cols;
        }
        CompRowMatrix m = new CompRowMatrix(rows, cols, pattern);
        for (int i = 0; i < rows; i++) {
            SparseVector row = store.getRow(i);
            int[] idx = row.getIndex();
            double[] data = row.getData();
            int used = row.getUsed();
            for (int k = 0; k < used; k++) {
                m.set(i, idx[k], data[k]);
            }
        }
        compactCache = m;
        dirty = false;
        return m;
    }

    /** Direct access to the build-time backing matrix. */
    public FlexCompRowMatrix mtjStore() { return store; }

    /** Solve Ax = b via CG + Jacobi preconditioner, falling back to BiCGstab. */
    public double[] solveLeft(double[] b) {
        if (rows != cols) {
            throw new IllegalArgumentException("solveLeft requires square matrix");
        }
        if (b.length != rows) {
            throw new IllegalArgumentException("rhs length mismatch");
        }
        return IterativeSolver.solve(this, b, IterativeSolver.DEFAULT_TOL,
                IterativeSolver.DEFAULT_MAX_ITER);
    }

    /** Materialize to a dense double[][] (small matrices / tests only). */
    public double[][] toDense() {
        double[][] dense = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            SparseVector row = store.getRow(i);
            int[] idx = row.getIndex();
            double[] data = row.getData();
            int used = row.getUsed();
            for (int k = 0; k < used; k++) dense[i][idx[k]] = data[k];
        }
        return dense;
    }

    private void boundsCheck(int i, int j) {
        if (i < 0 || i >= rows || j < 0 || j >= cols) {
            throw new IndexOutOfBoundsException("(" + i + "," + j + ") out of " + rows + "x" + cols);
        }
    }
}
