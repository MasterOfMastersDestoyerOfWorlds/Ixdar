package ixdar.geometry.mesh.quadlayout.solver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import no.uib.cipr.matrix.AbstractMatrix;
import no.uib.cipr.matrix.DenseVector;
import no.uib.cipr.matrix.DenseVectorSub;
import no.uib.cipr.matrix.MatrixEntry;
import no.uib.cipr.matrix.Vector;
import no.uib.cipr.matrix.sparse.ArpackSym;

/**
 * Generalized eigenproblem solver for symmetric A and SPD B:
 *
 * <pre>
 *   A x = λ B x
 * </pre>
 *
 * Standard transform via inner solver: defining {@code M = B^{-1} A}, the
 * eigenpairs of {@code M} are exactly the generalized eigenpairs of
 * {@code (A, B)}. We don't materialize {@code M}; instead we hand ARPACK an
 * {@link AbstractMatrix} subclass whose {@code mult(x, y)} computes
 * {@code y = B^{-1} (A x)} via {@link SparseQDLDL} on B and {@link SparseMatrix}
 * on A.
 *
 * <p>The Lanczos iteration in ARPACK only requests matrix-vector products; this
 * is the same shift-invert recipe Bommes IGM and the KCPS cross-field code use,
 * just with different inner solvers.
 *
 * <p>If B is not provided (single-matrix overload), this falls back to plain
 * symmetric ARPACK on A.
 */
public final class GeneralizedEigen {

    public static final class Result {
        public final double[] eigenvalues;
        public final double[][] eigenvectors;

        public Result(double[] eigenvalues, double[][] eigenvectors) {
            this.eigenvalues = eigenvalues;
            this.eigenvectors = eigenvectors;
        }
    }

    public Result solve(SparseMatrix a, SparseMatrix b, int numEigenpairs) {
        return solve(a, b, numEigenpairs, ArpackSym.Ritz.LM);
    }

    /**
     * @param a              symmetric matrix
     * @param b              SPD matrix (regularized Laplacian etc.); may be null
     *                       to compute eigenpairs of A directly
     * @param numEigenpairs  number of eigenpairs to return
     * @param ritz           which Ritz values to extract; default LM (largest
     *                       magnitude of B^{-1}A) corresponds to SMALLEST
     *                       generalized eigenvalues of (A,B) under shift-invert
     *                       at zero — but here we are computing eigenvalues of
     *                       B^{-1}A directly, so SA / LA / SM / LM apply to
     *                       those values
     */
    public Result solve(SparseMatrix a, SparseMatrix b, int numEigenpairs, ArpackSym.Ritz ritz) {
        if (a.rows() != a.cols()) {
            throw new IllegalArgumentException("A must be square");
        }
        if (b != null && (b.rows() != a.rows() || b.cols() != a.cols())) {
            throw new IllegalArgumentException("A and B must have matching dims");
        }
        int n = a.rows();
        if (numEigenpairs <= 0 || numEigenpairs >= n) {
            throw new IllegalArgumentException("0 < numEigenpairs < n required");
        }

        AbstractMatrix arpackInput;
        if (b == null) {
            arpackInput = new SparseMatrixOp(a);
        } else {
            SparseQDLDL bSolver = new SparseQDLDL();
            if (!bSolver.decompose(b)) {
                throw new IllegalStateException("B factorization failed (not SPD or singular?)");
            }
            arpackInput = new BInverseTimesA(a, bSolver);
        }

        ArpackSym arpack = new ArpackSym(arpackInput);
        Map<Double, DenseVectorSub> pairs = arpack.solve(numEigenpairs, ritz);

        List<Map.Entry<Double, DenseVectorSub>> sorted = new ArrayList<>(pairs.entrySet());
        sorted.sort((e1, e2) -> Double.compare(e1.getKey(), e2.getKey()));

        double[] vals = new double[sorted.size()];
        double[][] vecs = new double[sorted.size()][n];
        for (int k = 0; k < sorted.size(); k++) {
            vals[k] = sorted.get(k).getKey();
            DenseVectorSub v = sorted.get(k).getValue();
            for (int i = 0; i < n; i++) vecs[k][i] = v.get(i);
        }
        return new Result(vals, vecs);
    }

    /**
     * Operator-only Matrix base class for ArpackSym. We override
     * {@link #iterator()} to return empty so the constructor's symmetry check
     * passes vacuously; ARPACK's solve loop only calls {@link #mult(Vector,
     * Vector)}, which subclasses provide. {@link #get(int, int)} is never
     * reached on the operator-only path.
     */
    private static abstract class OperatorMatrix extends AbstractMatrix {
        OperatorMatrix(int n) { super(n, n); }
        @Override
        public double get(int row, int column) { return 0.0; }
        @Override
        public Iterator<MatrixEntry> iterator() {
            return Collections.<MatrixEntry>emptyList().iterator();
        }
    }

    /** A x as an MTJ Matrix operator. */
    private static final class SparseMatrixOp extends OperatorMatrix {
        private final SparseMatrix a;
        SparseMatrixOp(SparseMatrix a) {
            super(a.rows());
            this.a = a;
        }
        @Override
        public Vector mult(Vector x, Vector y) {
            double[] xa = vecToArray(x);
            double[] ya = a.multiply(xa);
            for (int i = 0; i < ya.length; i++) y.set(i, ya[i]);
            return y;
        }
    }

    /** y = B^{-1} A x. Composes one sparse multiply with one back-solve per ARPACK iteration. */
    private static final class BInverseTimesA extends OperatorMatrix {
        private final SparseMatrix a;
        private final SparseQDLDL bSolver;
        BInverseTimesA(SparseMatrix a, SparseQDLDL bSolver) {
            super(a.rows());
            this.a = a;
            this.bSolver = bSolver;
        }
        @Override
        public Vector mult(Vector x, Vector y) {
            double[] xa = vecToArray(x);
            double[] ax = a.multiply(xa);
            double[] sol = bSolver.solve(ax);
            for (int i = 0; i < sol.length; i++) y.set(i, sol[i]);
            return y;
        }
    }

    private static double[] vecToArray(Vector v) {
        int n = v.size();
        if (v instanceof DenseVector dv) {
            double[] data = dv.getData();
            return data.clone();
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = v.get(i);
        return out;
    }
}
