package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.solver.FactorizedSystem;
import ixdar.geometry.mesh.quadlayout.solver.chol.CholeskyBackend;
import ixdar.geometry.mesh.quadlayout.solver.chol.EjmlCholeskyFactor;
import ixdar.geometry.mesh.quadlayout.solver.matrix.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.ordering.OrderingMethod;
import ixdar.geometry.mesh.quadlayout.solver.ordering.SolverPermutation;

/**
 * Backend-equivalence test for the Cholesky seam: the native backend (PARDISO
 * or Accelerate, whichever the ladder selects) must reproduce the EJML
 * reference backend's solutions on the same SPD system to tight tolerance, and
 * both must actually solve the system (small residual). Constructs the EJML
 * backend directly, so the pure-Java path is exercised even on machines where
 * the natives load.
 */
public final class CholeskyBackendEquivalenceTest {

    private static final int GRID_SIDE = 24;
    private static final double SOLUTION_AGREEMENT_TOLERANCE = 1.0e-8;
    private static final double RESIDUAL_TOLERANCE = 1.0e-8;
    private static final long RHS_SEED = 424242L;

    /**
     * Build a 2D grid-graph Laplacian plus identity — SPD with the same
     * structure class as the pipeline's mesh systems.
     *
     * @return the assembled SPD system with a random RHS
     */
    private static NormalMatrix buildGridLaplacian() {
        int n = GRID_SIDE * GRID_SIDE;
        double[] diag = new double[n];
        double[] rhs = new double[n];
        Map<Long, Double> upper = new HashMap<>();
        Random random = new Random(RHS_SEED);
        java.util.Arrays.fill(diag, 1.0);
        for (int y = 0; y < GRID_SIDE; y++) {
            for (int x = 0; x < GRID_SIDE; x++) {
                int i = y * GRID_SIDE + x;
                rhs[i] = random.nextDouble() * 2.0 - 1.0;
                if (x + 1 < GRID_SIDE) {
                    couple(upper, diag, i, i + 1);
                }
                if (y + 1 < GRID_SIDE) {
                    couple(upper, diag, i, i + GRID_SIDE);
                }
            }
        }
        return new NormalMatrix(diag, upper, rhs);
    }

    /**
     * Add the Laplacian edge (i, j): +1 to both diagonals, -1 off-diagonal.
     *
     * @param upper upper-triangle accumulator keyed by packed (row, col)
     * @param diag  diagonal accumulator
     * @param i     first variable, {@code i < j}
     * @param j     second variable
     */
    private static void couple(Map<Long, Double> upper, double[] diag, int i, int j) {
        diag[i] += 1.0;
        diag[j] += 1.0;
        upper.put(((long) i << NormalMatrix.KEY_ROW_SHIFT) | j, -1.0);
    }

    @Test
    public void ejmlSolvesTinySystemExactly() {
        int n = 3;
        double[] diag = new double[] { 3.0, 4.0, 3.0 };
        double[] rhs = new double[] { 1.0, 2.0, 3.0 };
        Map<Long, Double> upper = new HashMap<>();
        upper.put((0L << NormalMatrix.KEY_ROW_SHIFT) | 1, -1.0);
        upper.put((1L << NormalMatrix.KEY_ROW_SHIFT) | 2, -1.0);
        NormalMatrix matrix = new NormalMatrix(diag, upper, rhs);
        boolean[] fixed = new boolean[n];
        int[] identity = new int[] { 0, 1, 2 };
        FactorizedSystem ejml = new EjmlCholeskyFactor(
                matrix.toPermutedUpperCompressedSparseColumn(n, fixed, identity, identity, identity, identity), n);
        double[] solution = new double[n];
        ejml.solve(rhs, solution);
        double third = 1.0 / 3.0;
        assertEquals(2.0 * third, solution[0], SOLUTION_AGREEMENT_TOLERANCE);
        assertEquals(1.0, solution[1], SOLUTION_AGREEMENT_TOLERANCE);
        assertEquals(4.0 * third, solution[2], SOLUTION_AGREEMENT_TOLERANCE);
    }

    @Test
    public void nativeBackendMatchesEjmlOnSpdSystem() {
        NormalMatrix matrix = buildGridLaplacian();
        int n = matrix.size();
        boolean[] fixed = new boolean[n];
        int[] compactOf = new int[n];
        int[] fullOf = new int[n];
        for (int i = 0; i < n; i++) {
            compactOf[i] = i;
            fullOf[i] = i;
        }
        int[] perm = SolverPermutation.computePermutation(matrix, fixed, compactOf, n, OrderingMethod.AMD);
        int[] invPerm = new int[n];
        for (int i = 0; i < n; i++) {
            invPerm[perm[i]] = i;
        }

        double[] permutedRhs = new double[n];
        for (int newIdx = 0; newIdx < n; newIdx++) {
            permutedRhs[newIdx] = matrix.rightHandSide[fullOf[perm[newIdx]]];
        }

        FactorizedSystem ejml = new EjmlCholeskyFactor(
                matrix.toPermutedUpperCompressedSparseColumn(n, fixed, compactOf, fullOf, perm, invPerm), n);
        double[] ejmlSolution = new double[n];
        ejml.solve(permutedRhs, ejmlSolution);
        assertResidualSmall(matrix, ejmlSolution, perm, fullOf, "ejml");

        Assumptions.assumeTrue(CholeskyBackend.pardisoAvailable(),
                "no native backend loadable on this platform; EJML path verified");

        FactorizedSystem nativeFactor = CholeskyBackend.nativeBackend().factorUpper(
                matrix.toPermutedUpperCompressedSparseRow(n, fixed, compactOf, fullOf, perm, invPerm), n);
        double[] nativeSolution = new double[n];
        nativeFactor.solve(permutedRhs, nativeSolution);
        assertResidualSmall(matrix, nativeSolution, perm, fullOf, "native");
        nativeFactor.release();

        for (int i = 0; i < n; i++) {
            assertEquals(ejmlSolution[i], nativeSolution[i], SOLUTION_AGREEMENT_TOLERANCE,
                    "solution mismatch at permuted index " + i);
        }
    }

    /**
     * Check {@code ‖A x − b‖∞} in original index space.
     *
     * @param matrix           the SPD system
     * @param permutedSolution solution in permuted index space
     * @param perm             permuted-index → old compact-index
     * @param fullOf           compact-index → full-index
     * @param backendName      label for the assertion message
     */
    private static void assertResidualSmall(NormalMatrix matrix, double[] permutedSolution,
            int[] perm, int[] fullOf, String backendName) {
        int n = matrix.size();
        double[] x = new double[n];
        for (int newIdx = 0; newIdx < n; newIdx++) {
            x[fullOf[perm[newIdx]]] = permutedSolution[newIdx];
        }
        double maxResidual = 0.0;
        for (int row = 0; row < n; row++) {
            maxResidual = Math.max(maxResidual,
                    Math.abs(matrix.rightHandSide[row] - matrix.rowDot(row, x)));
        }
        assertTrue(maxResidual < RESIDUAL_TOLERANCE,
                backendName + " residual too large: " + maxResidual);
    }
}
