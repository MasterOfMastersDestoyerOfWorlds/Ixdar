package unit.quadlayout.solver;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.solver.SparseMatrix;
import ixdar.geometry.mesh.quadlayout.solver.SparseQDLDL;

public class SparseQDLDLTest {

    @Test
    void smallSpdSolve() {
        // 4x4 SPD: 2I plus a tridiagonal off-diagonal.
        SparseMatrix m = new SparseMatrix(4, 4);
        for (int i = 0; i < 4; i++) m.set(i, i, 4.0);
        for (int i = 0; i < 3; i++) {
            m.set(i, i + 1, 1.0);
            m.set(i + 1, i, 1.0);
        }
        SparseQDLDL ldl = new SparseQDLDL();
        assertTrue(ldl.decompose(m));
        double[] b = { 5, 6, 6, 5 };
        double[] x = ldl.solve(b);
        double[] check = m.multiply(x);
        for (int i = 0; i < 4; i++) {
            assertTrue(Math.abs(check[i] - b[i]) < 1e-9, "residual[" + i + "]=" + (check[i] - b[i]));
        }
    }

    @Test
    void regularizedLaplacianResidualLow() {
        Random rng = new Random(11);
        int n = 80;
        // Build a random graph Laplacian L plus eps*I.
        SparseMatrix m = new SparseMatrix(n, n);
        double[][] dense = new double[n][n];
        double eps = 1e-2;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (rng.nextDouble() < 0.05) {
                    double w = 0.5 + rng.nextDouble();
                    m.add(i, j, -w);
                    m.add(j, i, -w);
                    m.add(i, i, w);
                    m.add(j, j, w);
                    dense[i][j] = -w;
                    dense[j][i] = -w;
                    dense[i][i] += w;
                    dense[j][j] += w;
                }
            }
            m.add(i, i, eps);
            dense[i][i] += eps;
        }
        SparseQDLDL ldl = new SparseQDLDL();
        assertTrue(ldl.decompose(m));
        double[] x0 = new double[n];
        for (int i = 0; i < n; i++) x0[i] = rng.nextGaussian();
        double[] b = SparseMatrix.denseMultiply(dense, x0);
        double[] x = ldl.solve(b);
        double maxErr = 0.0;
        for (int i = 0; i < n; i++) maxErr = Math.max(maxErr, Math.abs(x[i] - x0[i]));
        assertTrue(maxErr < 1e-7, "max error: " + maxErr);
    }

    @Test
    void factor10kLaplacianUnder5s() {
        // Sanity: 10k x 10k path-graph Laplacian + eps*I should factor in
        // well under 5s on a current Mac. Path graph is intentionally easy
        // (banded, low fill-in) — we just want to sanity-check the wiring,
        // not benchmark fill-in worst case.
        int n = 10_000;
        SparseMatrix m = new SparseMatrix(n, n);
        for (int i = 0; i < n - 1; i++) {
            m.add(i, i, 1.0);
            m.add(i + 1, i + 1, 1.0);
            m.add(i, i + 1, -1.0);
            m.add(i + 1, i, -1.0);
        }
        for (int i = 0; i < n; i++) m.add(i, i, 1e-3);
        long t0 = System.nanoTime();
        SparseQDLDL ldl = new SparseQDLDL();
        boolean ok = ldl.decompose(m);
        long t1 = System.nanoTime();
        double seconds = (t1 - t0) / 1e9;
        assertTrue(ok, "decompose failed");
        assertTrue(seconds < 5.0, "10k Laplacian factor took " + seconds + "s, want <5s");
    }
}
