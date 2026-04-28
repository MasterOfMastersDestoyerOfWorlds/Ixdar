package unit.quadlayout.solver;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.solver.IterativeSolver;
import ixdar.geometry.mesh.quadlayout.solver.MtjSparseMatrix;

public class MtjSparseMatrixTest {

    @Test
    void identityRoundTrip() {
        MtjSparseMatrix m = MtjSparseMatrix.identity(5);
        double[] x = { 1, 2, 3, 4, 5 };
        double[] y = m.multiply(x);
        assertArrayEquals(x, y, 1e-12);
    }

    @Test
    void setAddGet() {
        MtjSparseMatrix m = new MtjSparseMatrix(3, 3);
        m.set(0, 0, 1.0);
        m.add(0, 0, 2.0);
        m.set(1, 2, 7.0);
        assertEquals(3.0, m.get(0, 0), 1e-12);
        assertEquals(7.0, m.get(1, 2), 1e-12);
        assertEquals(0.0, m.get(2, 1), 1e-12);
    }

    @Test
    void multiplyVsDense() {
        Random rng = new Random(42);
        int n = 30;
        double[][] dense = new double[n][n];
        MtjSparseMatrix m = new MtjSparseMatrix(n, n);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (rng.nextDouble() < 0.2) {
                    double v = rng.nextDouble();
                    dense[i][j] = v;
                    m.set(i, j, v);
                }
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = rng.nextDouble();
        double[] sparseY = m.multiply(x);
        double[] denseY = denseMultiply(dense, x);
        assertArrayEquals(denseY, sparseY, 1e-9);
    }

    @Test
    void transposeRoundTrip() {
        MtjSparseMatrix m = new MtjSparseMatrix(3, 4);
        m.set(0, 1, 2.0);
        m.set(2, 3, -5.0);
        MtjSparseMatrix mt = m.transpose();
        assertEquals(2.0, mt.get(1, 0), 1e-12);
        assertEquals(-5.0, mt.get(3, 2), 1e-12);
    }

    @Test
    void compRowMaterialize() {
        MtjSparseMatrix m = MtjSparseMatrix.identity(4);
        // toCompRow should expose the same nonzero values.
        var csr = m.toCompRow();
        assertEquals(4, csr.numRows());
        assertEquals(4, csr.numColumns());
        for (int i = 0; i < 4; i++) {
            assertEquals(1.0, csr.get(i, i), 1e-12);
        }
        // Mutation invalidates the cache; re-materialize.
        m.set(0, 1, 3.0);
        var csr2 = m.toCompRow();
        assertEquals(3.0, csr2.get(0, 1), 1e-12);
    }

    @Test
    void smallSpdSolveResidualLow() {
        MtjSparseMatrix m = new MtjSparseMatrix(3, 3);
        m.set(0, 0, 4.0); m.set(1, 1, 4.0); m.set(2, 2, 4.0);
        m.set(0, 1, 1.0); m.set(1, 0, 1.0);
        m.set(1, 2, 1.0); m.set(2, 1, 1.0);
        double[] b = { 6, 11, 9 };
        double[] x = IterativeSolver.solve(m, b, 1e-9, 100);
        double[] check = m.multiply(x);
        for (int i = 0; i < 3; i++) {
            assertTrue(Math.abs(check[i] - b[i]) < 1e-6, "residual[" + i + "]=" + (check[i] - b[i]));
        }
    }

    @Test
    void randomLargerSpdSolve() {
        Random rng = new Random(7);
        int n = 60;
        MtjSparseMatrix a = new MtjSparseMatrix(n, n);
        // Build symmetric diagonally-dominant matrix: SPD by construction.
        double[][] dense = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (rng.nextDouble() < 0.1) {
                    double v = rng.nextDouble() - 0.5;
                    a.set(i, j, v);
                    a.set(j, i, v);
                    dense[i][j] = v;
                    dense[j][i] = v;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            double rowSum = 0.0;
            for (int j = 0; j < n; j++) if (i != j) rowSum += Math.abs(dense[i][j]);
            double diag = rowSum + 1.0 + rng.nextDouble();
            a.set(i, i, diag);
            dense[i][i] = diag;
        }
        double[] x0 = new double[n];
        for (int i = 0; i < n; i++) x0[i] = rng.nextDouble() * 10.0 - 5.0;
        double[] b = denseMultiply(dense, x0);
        double[] x = IterativeSolver.solve(a, b, 1e-9, 1000);
        // Compare residual rather than exact x — iterative solver tolerance.
        double[] check = a.multiply(x);
        double err = 0.0;
        for (int i = 0; i < n; i++) err += (check[i] - b[i]) * (check[i] - b[i]);
        assertTrue(Math.sqrt(err) < 1e-6, "residual norm too large: " + Math.sqrt(err));
    }

    /** Direct overflow regression: build a >50k × 50k sparse SPD and confirm solve completes. */
    @Test
    void synthetic50kSparseSpdMatrixSolves() {
        // Pick a stride well above sqrt(MAX_INT) ~ 46340 so the old ojAlgo-backed
        // SparseStore would overflow. 60000 * 60000 = 3.6e9 > 2^31.
        int n = 60_000;
        Random rng = new Random(123);
        MtjSparseMatrix a = new MtjSparseMatrix(n, n);
        // ~10 nnz/row off-diagonal, symmetric.
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < 5; k++) {
                int j = i + 1 + rng.nextInt(20);
                if (j >= n) continue;
                double v = (rng.nextDouble() - 0.5) * 0.1;
                a.set(i, j, v);
                a.set(j, i, v);
            }
        }
        for (int i = 0; i < n; i++) {
            // Diagonal large enough to dominate any plausible row sum (worst case
            // ~ 0.5 * 10 = 5).
            a.set(i, i, 100.0 + rng.nextDouble());
        }

        double[] b = new double[n];
        for (int i = 0; i < n; i++) b[i] = rng.nextDouble();

        long t0 = System.currentTimeMillis();
        double[] x = IterativeSolver.solve(a, b, 1e-6, 1000);
        long elapsed = System.currentTimeMillis() - t0;

        // Sanity: the solve produced finite values.
        for (int i = 0; i < n; i++) {
            assertTrue(Double.isFinite(x[i]), "x[" + i + "] not finite");
        }
        // Spot-check residual on a small sub-sample to keep test cheap.
        double[] check = a.multiply(x);
        double resSq = 0.0;
        double bSq = 0.0;
        for (int i = 0; i < n; i++) {
            double r = check[i] - b[i];
            resSq += r * r;
            bSq += b[i] * b[i];
        }
        double relRes = Math.sqrt(resSq / Math.max(bSq, 1e-30));
        assertTrue(relRes < 1e-3, "relative residual too large: " + relRes);
        assertTrue(elapsed < 30_000, "50k SPD solve took too long: " + elapsed + " ms");
        System.out.println("[MtjSparseMatrixTest] 50k SPD solve: rel_res=" + relRes
                + " time=" + elapsed + " ms");
    }

    private static double[] denseMultiply(double[][] M, double[] x) {
        int r = M.length;
        int c = M[0].length;
        double[] y = new double[r];
        for (int i = 0; i < r; i++) {
            double s = 0.0;
            for (int j = 0; j < c; j++) s += M[i][j] * x[j];
            y[i] = s;
        }
        return y;
    }
}
