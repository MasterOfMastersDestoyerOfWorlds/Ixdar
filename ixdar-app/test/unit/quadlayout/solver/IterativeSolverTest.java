package unit.quadlayout.solver;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.solver.IterativeSolver;
import ixdar.geometry.mesh.quadlayout.solver.MtjSparseMatrix;

public class IterativeSolverTest {

    @Test
    void cgOnSyntheticSpd() {
        Random rng = new Random(101);
        int n = 100;
        MtjSparseMatrix a = buildSpd(n, rng);
        double[] x0 = new double[n];
        for (int i = 0; i < n; i++) x0[i] = rng.nextDouble() * 4 - 2;
        double[] b = a.multiply(x0);

        double[] x = IterativeSolver.solve(a, b, 1e-9, 500);
        double[] check = a.multiply(x);
        double resSq = 0.0;
        double bSq = 0.0;
        for (int i = 0; i < n; i++) {
            double r = check[i] - b[i];
            resSq += r * r;
            bSq += b[i] * b[i];
        }
        double relRes = Math.sqrt(resSq / bSq);
        assertTrue(relRes < 1e-6, "CG relative residual too high: " + relRes);
    }

    @Test
    void biCGstabFallbackOnNonsymmetric() {
        // Build a nonsymmetric matrix; CG will fail but BiCGstab should still
        // succeed via the IterativeSolver's automatic fallback path.
        Random rng = new Random(202);
        int n = 50;
        MtjSparseMatrix a = new MtjSparseMatrix(n, n);
        for (int i = 0; i < n; i++) {
            a.set(i, i, 4.0 + rng.nextDouble());
            if (i > 0) a.set(i, i - 1, -1.0 - rng.nextDouble() * 0.1);
            if (i < n - 1) a.set(i, i + 1, -0.5);
        }
        double[] x0 = new double[n];
        for (int i = 0; i < n; i++) x0[i] = rng.nextDouble() * 4 - 2;
        double[] b = a.multiply(x0);

        double[] x = IterativeSolver.solve(a, b, 1e-8, 500);
        double[] check = a.multiply(x);
        double resSq = 0.0, bSq = 0.0;
        for (int i = 0; i < n; i++) {
            double r = check[i] - b[i];
            resSq += r * r;
            bSq += b[i] * b[i];
        }
        double relRes = Math.sqrt(resSq / bSq);
        assertTrue(relRes < 1e-5, "BiCGstab relative residual too high: " + relRes);
    }

    @Test
    void resultStructHasResidual() {
        int n = 20;
        MtjSparseMatrix a = MtjSparseMatrix.identity(n);
        double[] b = new double[n];
        for (int i = 0; i < n; i++) b[i] = i + 1;

        IterativeSolver.Result r = IterativeSolver.solveWithResidual(a, b, 1e-9, 100);
        for (int i = 0; i < n; i++) {
            assertTrue(Math.abs(r.x[i] - b[i]) < 1e-6, "x[" + i + "] mismatch");
        }
        assertTrue(r.relativeResidual < 1e-6);
    }

    private static MtjSparseMatrix buildSpd(int n, Random rng) {
        MtjSparseMatrix a = new MtjSparseMatrix(n, n);
        double[] diag = new double[n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (rng.nextDouble() < 0.05) {
                    double v = rng.nextDouble() * 0.1;
                    a.set(i, j, v);
                    a.set(j, i, v);
                    diag[i] += Math.abs(v);
                    diag[j] += Math.abs(v);
                }
            }
        }
        for (int i = 0; i < n; i++) {
            a.set(i, i, diag[i] + 1.0 + rng.nextDouble());
        }
        return a;
    }
}
