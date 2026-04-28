package unit.quadlayout.solver;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.solver.SparseMatrix;

public class SparseMatrixTest {

    @Test
    void identityRoundTrip() {
        SparseMatrix m = SparseMatrix.identity(5);
        double[] x = { 1, 2, 3, 4, 5 };
        double[] y = m.multiply(x);
        assertArrayEquals(x, y, 1e-12);
    }

    @Test
    void setAddGet() {
        SparseMatrix m = new SparseMatrix(3, 3);
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
        SparseMatrix m = new SparseMatrix(n, n);
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
        double[] denseY = SparseMatrix.denseMultiply(dense, x);
        assertArrayEquals(denseY, sparseY, 1e-9);
    }

    @Test
    void transposeRoundTrip() {
        SparseMatrix m = new SparseMatrix(3, 4);
        m.set(0, 1, 2.0);
        m.set(2, 3, -5.0);
        SparseMatrix mt = m.transpose();
        assertEquals(2.0, mt.get(1, 0), 1e-12);
        assertEquals(-5.0, mt.get(3, 2), 1e-12);
    }

    @Test
    void csrCscBuilds() {
        SparseMatrix m = SparseMatrix.identity(4);
        assertTrue(m.toCsr().countNonzeros() == 4);
        assertTrue(m.toCsc().countNonzeros() == 4);
    }

    @Test
    void solveLeftIdentity() {
        SparseMatrix m = SparseMatrix.identity(5);
        double[] b = { 1, 2, 3, 4, 5 };
        double[] x = m.solveLeft(b);
        assertArrayEquals(b, x, 1e-9);
    }
}
