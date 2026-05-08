package ixdar.geometry.mesh.quadlayout.solver;

import java.util.Arrays;

import org.ojalgo.matrix.store.MatrixStore;
import org.ojalgo.matrix.store.PhysicalStore;
import org.ojalgo.matrix.store.R064CSC;
import org.ojalgo.matrix.store.R064CSR;
import org.ojalgo.matrix.store.R064Store;
import org.ojalgo.matrix.store.SparseStore;

/**
 * Real-valued sparse matrix for the QGP linear-algebra stack. Backed by
 * ojAlgo's {@link SparseStore} for storage; exposes a compact Java-flavoured
 * surface (set/add/multiply/transpose/toCsr/toCsc/solveLeft) that the rest of
 * the QGP pipeline talks to. Built incrementally in COO-style; finalize by
 * calling {@link #toCsr()} or {@link #toCsc()}.
 */
public final class SparseMatrix {
    public static final int NUM_12 = 12;

    private final int rows;
    private final int cols;
    private final SparseStore<Double> store;

    /**
     * TODO: document {@code SparseMatrix}.
     *
     * @param rows TODO: describe
     * @param cols TODO: describe
     * @throws IllegalArgumentException TODO: describe
     */
    public SparseMatrix(int rows, int cols) {
        if (rows <= 0 || cols <= 0) {
            throw new IllegalArgumentException("rows/cols must be positive");
        }
        this.rows = rows;
        this.cols = cols;
        this.store = SparseStore.R064.make(rows, cols);
    }

    /**
     * TODO: document {@code identity}.
     *
     * @param n TODO: describe
     * @return TODO: describe
     */
    public static SparseMatrix identity(int n) {
        SparseMatrix m = new SparseMatrix(n, n);
        for (int i = 0; i < n; i++) {
            m.set(i, i, 1.0);
        }
        return m;
    }

    /**
     * TODO: document {@code fromTriplets}.
     *
     * @param rows TODO: describe
     * @param cols TODO: describe
     * @param is TODO: describe
     * @param js TODO: describe
     * @param vs TODO: describe
     * @throws IllegalArgumentException TODO: describe
     * @return TODO: describe
     */
    public static SparseMatrix fromTriplets(int rows, int cols, int[] is, int[] js, double[] vs) {
        if (is.length != js.length || is.length != vs.length) {
            throw new IllegalArgumentException("triplet arrays must match length");
        }
        SparseMatrix m = new SparseMatrix(rows, cols);
        for (int k = 0; k < is.length; k++) {
            m.add(is[k], js[k], vs[k]);
        }
        return m;
    }

    /**
     * TODO: document {@code fromDense}.
     *
     * @param dense TODO: describe
     * @return TODO: describe
     */
    public static SparseMatrix fromDense(double[][] dense) {
        int r = dense.length;
        int c = dense[0].length;
        SparseMatrix m = new SparseMatrix(r, c);
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < c; j++) {
                if (dense[i][j] != 0.0) {
                    m.set(i, j, dense[i][j]);
                }
            }
        }
        return m;
    }

    /**
     * TODO: document {@code rows}.
     *
     * @return TODO: describe
     */
    public int rows() { return rows; }
    /**
     * TODO: document {@code cols}.
     *
     * @return TODO: describe
     */
    public int cols() { return cols; }

    /**
     * TODO: document {@code set}.
     *
     * @param i TODO: describe
     * @param j TODO: describe
     * @param v TODO: describe
     */
    public void set(int i, int j, double v) {
        boundsCheck(i, j);
        store.set(i, j, v);
    }

    /**
     * TODO: document {@code add}.
     *
     * @param i TODO: describe
     * @param j TODO: describe
     * @param v TODO: describe
     */
    public void add(int i, int j, double v) {
        boundsCheck(i, j);
        if (v == 0.0) return;
        store.add(i, j, v);
    }

    /**
     * TODO: document {@code get}.
     *
     * @param i TODO: describe
     * @param j TODO: describe
     * @return TODO: describe
     */
    public double get(int i, int j) {
        boundsCheck(i, j);
        return store.doubleValue(i, j);
    }

    /**
     * TODO: document {@code countNonzeros}.
     *
     * @return TODO: describe
     */
    public int countNonzeros() {
        return store.countNonzeros();
    }

    /**
     * y = this * x.
     *
     * @param x TODO: describe
     * @throws IllegalArgumentException TODO: describe
     * @return TODO: describe
     */
    public double[] multiply(double[] x) {
        if (x.length != cols) {
            throw new IllegalArgumentException("vector length mismatch: " + x.length + " vs " + cols);
        }
        R064Store xs = R064Store.FACTORY.column(x);
        R064Store ys = R064Store.FACTORY.make(rows, 1);
        store.multiply(xs, ys);
        double[] out = new double[rows];
        for (int i = 0; i < rows; i++) out[i] = ys.doubleValue(i, 0);
        return out;
    }

    /**
     * Returns a fresh transpose.
     *
     * @return TODO: describe
     */
    public SparseMatrix transpose() {
        SparseMatrix t = new SparseMatrix(cols, rows);
        store.nonzeros().forEach(view -> {
            long row = view.row();
            long col = view.column();
            t.store.set(col, row, view.doubleValue());
        });
        return t;
    }

    /**
     * Solve x A = b on the LEFT (i.e. compute x such that A^T x = b^T using a
     * dense LU on A^T). Used by callers that want a row-vector solve and don't
     * want to construct a SparseLu themselves; for repeated solves use
     * {@link SparseLu} directly.
     *
     * @param b TODO: describe
     * @throws IllegalArgumentException TODO: describe
     * @return TODO: describe
     */
    public double[] solveLeft(double[] b) {
        if (rows != cols) {
            throw new IllegalArgumentException("solveLeft requires square matrix");
        }
        if (b.length != rows) {
            throw new IllegalArgumentException("rhs length mismatch");
        }
        SparseMatrix at = transpose();
        SparseLu lu = new SparseLu();
        lu.decompose(at);
        return lu.solve(b);
    }

    /**
     * Direct access for solver wrappers.
     *
     * @return TODO: describe
     */
    public SparseStore<Double> ojAlgoStore() {
        return store;
    }

    /**
     * TODO: document {@code toCsr}.
     *
     * @return TODO: describe
     */
    public R064CSR toCsr() {
        return store.toCSR();
    }

    /**
     * TODO: document {@code toCsc}.
     *
     * @return TODO: describe
     */
    public R064CSC toCsc() {
        return store.toCSC();
    }

    /**
     * Materialize to a dense double[][] (small matrices / tests only).
     *
     * @return TODO: describe
     */
    public double[][] toDense() {
        double[][] dense = new double[rows][cols];
        store.nonzeros().forEach(view -> dense[(int) view.row()][(int) view.column()] = view.doubleValue());
        return dense;
    }

    /**
     * y = M * x using dense temporary; primarily for tests.
     *
     * @param M TODO: describe
     * @param x TODO: describe
     * @throws IllegalArgumentException TODO: describe
     * @return TODO: describe
     */
    public static double[] denseMultiply(double[][] M, double[] x) {
        int r = M.length;
        int c = M[0].length;
        if (x.length != c) throw new IllegalArgumentException("vector length mismatch");
        double[] y = new double[r];
        for (int i = 0; i < r; i++) {
            double s = 0.0;
            for (int j = 0; j < c; j++) s += M[i][j] * x[j];
            y[i] = s;
        }
        return y;
    }

    /**
     * Pretty-print for tiny test matrices.
     *
     * @return TODO: describe
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SparseMatrix ").append(rows).append('x').append(cols)
                .append(" nnz=").append(countNonzeros()).append('\n');
        if (rows <= NUM_12 && cols <= NUM_12) {
            double[][] d = toDense();
            for (int i = 0; i < rows; i++) {
                sb.append(Arrays.toString(d[i])).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Allow internal helpers to grab a fresh ojAlgo PhysicalStore for solve work.
     *
     * @param data TODO: describe
     * @return TODO: describe
     */
    PhysicalStore<Double> newColumn(double[] data) {
        return R064Store.FACTORY.column(data);
    }

    /**
     * Internal: copy this matrix to a dense MatrixStore for ojAlgo decomposition feeds.
     *
     * @return TODO: describe
     */
    MatrixStore<Double> asMatrixStore() {
        return store;
    }

    private void boundsCheck(int i, int j) {
        if (i < 0 || i >= rows || j < 0 || j >= cols) {
            throw new IndexOutOfBoundsException("(" + i + "," + j + ") out of " + rows + "x" + cols);
        }
    }
}
