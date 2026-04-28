package unit.quadlayout.solver;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.solver.GeneralizedEigen;
import ixdar.geometry.mesh.quadlayout.solver.SparseMatrix;

public class GeneralizedEigenTest {

    /**
     * Cycle-graph Laplacian on n vertices has known eigenvalues
     * λ_k = 2 − 2 cos(2π k / n) for k = 0, 1, ..., n-1.
     * The smallest non-trivial pair is at k=1 and k=n-1, both equal to
     * 2 − 2 cos(2π/n). Reference: any standard graph-theory text;
     * also matches numpy.linalg.eigvalsh on a small instance.
     */
    @Test
    void cycleLaplacianSmallestNonTrivial() {
        int n = 32;
        SparseMatrix L = buildCycleLaplacian(n);
        // Add tiny regularization so 0 eigenvalue doesn't break shift-invert.
        for (int i = 0; i < n; i++) L.add(i, i, 1e-6);
        // Solve A x = λ I x  (B == identity scaled by 1) — easiest reference.
        SparseMatrix I = SparseMatrix.identity(n);
        GeneralizedEigen eig = new GeneralizedEigen();
        // Smallest 4 eigenvalues. ARPACK's "SA" gives smallest algebraic.
        GeneralizedEigen.Result r = eig.solve(L, I, 4, no.uib.cipr.matrix.sparse.ArpackSym.Ritz.SA);
        assertTrue(r.eigenvalues.length >= 1, "no eigenvalues returned");
        // Smallest is ~ regularization (1e-6); next is 2 - 2*cos(2π/n).
        double expected = 2.0 - 2.0 * Math.cos(2.0 * Math.PI / n) + 1e-6;
        // Find the eigenvalue closest to expected (skip the ~1e-6 zero).
        double bestDelta = Double.POSITIVE_INFINITY;
        for (double v : r.eigenvalues) {
            if (v < 1e-4) continue;
            bestDelta = Math.min(bestDelta, Math.abs(v - expected));
        }
        assertTrue(bestDelta < 5e-3,
                "no eigenvalue near " + expected + "; got " + java.util.Arrays.toString(r.eigenvalues));
    }

    /**
     * Generalized eigen with B = sigma * I (scalar mass matrix): eigenvalues
     * should be exactly the cycle-Laplacian eigenvalues divided by sigma.
     * This is the realistic case for cross-field stages where B is a
     * lumped-area mass matrix; full general non-diagonal B requires a
     * Cholesky-transform we'll layer on in a later ticket.
     */
    @Test
    void generalizedDiagonalB() {
        int n = 32;
        SparseMatrix A = buildCycleLaplacian(n);
        for (int i = 0; i < n; i++) A.add(i, i, 1e-6);
        SparseMatrix B = new SparseMatrix(n, n);
        double sigma = 2.0;
        for (int i = 0; i < n; i++) B.set(i, i, sigma);
        GeneralizedEigen eig = new GeneralizedEigen();
        GeneralizedEigen.Result r = eig.solve(A, B, 4, no.uib.cipr.matrix.sparse.ArpackSym.Ritz.SA);
        assertTrue(r.eigenvalues.length >= 1);
        // Smallest real eigenvalue (above the regularization floor) of A is
        // 2 - 2 cos(2π/n); divided by sigma for the generalized problem.
        double expected = (2.0 - 2.0 * Math.cos(2.0 * Math.PI / n) + 1e-6) / sigma;
        double bestDelta = Double.POSITIVE_INFINITY;
        for (double v : r.eigenvalues) {
            if (v < (1e-6 / sigma) * 1.5) continue;
            bestDelta = Math.min(bestDelta, Math.abs(v - expected));
        }
        assertTrue(bestDelta < 5e-3, "no eigenvalue near " + expected
                + "; got " + java.util.Arrays.toString(r.eigenvalues));
    }

    private static SparseMatrix buildCycleLaplacian(int n) {
        SparseMatrix L = new SparseMatrix(n, n);
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            L.add(i, i, 1.0);
            L.add(j, j, 1.0);
            L.add(i, j, -1.0);
            L.add(j, i, -1.0);
        }
        return L;
    }
}
