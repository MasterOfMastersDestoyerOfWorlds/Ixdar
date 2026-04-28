package unit.quadlayout.solver;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.solver.SparseLu;
import ixdar.geometry.mesh.quadlayout.solver.SparseMatrix;

public class SparseLuTest {

    @Test
    void smallSpdSolve() {
        SparseMatrix m = new SparseMatrix(3, 3);
        m.set(0, 0, 4.0); m.set(1, 1, 4.0); m.set(2, 2, 4.0);
        m.set(0, 1, 1.0); m.set(1, 0, 1.0);
        m.set(1, 2, 1.0); m.set(2, 1, 1.0);
        SparseLu lu = new SparseLu();
        assertTrue(lu.decompose(m));
        double[] b = { 6, 11, 9 };
        double[] x = lu.solve(b);
        // Verify residual
        double[] check = m.multiply(x);
        for (int i = 0; i < 3; i++) {
            assertTrue(Math.abs(check[i] - b[i]) < 1e-9, "residual[" + i + "]=" + (check[i] - b[i]));
        }
    }

    @Test
    void randomLargerSolveResidualLow() {
        Random rng = new Random(7);
        int n = 40;
        SparseMatrix a = new SparseMatrix(n, n);
        // Build diagonally-dominant matrix: well-conditioned and full-rank.
        double[][] dense = new double[n][n];
        for (int i = 0; i < n; i++) {
            double rowSum = 0.0;
            for (int j = 0; j < n; j++) {
                if (i != j && rng.nextDouble() < 0.15) {
                    double v = rng.nextDouble() - 0.5;
                    a.set(i, j, v);
                    dense[i][j] = v;
                    rowSum += Math.abs(v);
                }
            }
            double diag = rowSum + 1.0 + rng.nextDouble();
            a.set(i, i, diag);
            dense[i][i] = diag;
        }
        double[] x0 = new double[n];
        for (int i = 0; i < n; i++) x0[i] = rng.nextDouble() * 10.0 - 5.0;
        double[] b = SparseMatrix.denseMultiply(dense, x0);
        SparseLu lu = new SparseLu();
        assertTrue(lu.decompose(a));
        double[] x = lu.solve(b);
        double maxErr = 0.0;
        for (int i = 0; i < n; i++) maxErr = Math.max(maxErr, Math.abs(x[i] - x0[i]));
        assertTrue(maxErr < 1e-9, "max error too large: " + maxErr);
    }

    @Test
    void refactorSamePattern() {
        SparseMatrix m = new SparseMatrix(3, 3);
        m.set(0, 0, 2.0); m.set(1, 1, 3.0); m.set(2, 2, 4.0);
        SparseLu lu = new SparseLu();
        assertTrue(lu.decompose(m));
        double[] x = lu.solve(new double[]{ 4, 9, 16 });
        // x = b ./ diag = (2, 3, 4)
        assertTrue(Math.abs(x[0] - 2) < 1e-9);
        // Now refactor with different values, same pattern.
        SparseMatrix m2 = new SparseMatrix(3, 3);
        m2.set(0, 0, 10.0); m2.set(1, 1, 10.0); m2.set(2, 2, 10.0);
        assertTrue(lu.refactor(m2));
        double[] x2 = lu.solve(new double[]{ 50, 60, 70 });
        assertTrue(Math.abs(x2[0] - 5) < 1e-9);
    }
}
