package benchmark;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.solver.CholeskyBackend;
import ixdar.geometry.mesh.quadlayout.solver.EjmlCholeskyFactor;
import ixdar.geometry.mesh.quadlayout.solver.FactorizedSystem;
import ixdar.geometry.mesh.quadlayout.solver.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.OrderingMethod;
import ixdar.geometry.mesh.quadlayout.solver.PardisoCholesky;
import ixdar.geometry.mesh.quadlayout.solver.SolverPermutation;
import ixdar.platform.Platforms;

/**
 * Micro-benchmark separating backend factor time from repeated-solve time at
 * seamless-system scale (~200k variables), to attribute the stiffening-loop
 * preconditioner cost. Not in the default test globs; run explicitly with
 * {@code -Dtest=BackendSolveMicroBenchmark}.
 */
public final class BackendSolveMicroBenchmark {

    private static final int GRID_SIDE = 450;
    private static final int SOLVE_REPETITIONS = 30;
    private static final long RHS_SEED = 99L;

    @Test
    public void compareFactorAndSolveTimes() {
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
                    diag[i] += 1.0;
                    diag[i + 1] += 1.0;
                    upper.put(((long) i << NormalMatrix.KEY_ROW_SHIFT) | (i + 1), -1.0);
                }
                if (y + 1 < GRID_SIDE) {
                    diag[i] += 1.0;
                    diag[i + GRID_SIDE] += 1.0;
                    upper.put(((long) i << NormalMatrix.KEY_ROW_SHIFT) | (i + GRID_SIDE), -1.0);
                }
            }
        }
        NormalMatrix matrix = new NormalMatrix(diag, upper, rhs);
        boolean[] fixed = new boolean[n];
        int[] identity = new int[n];
        for (int i = 0; i < n; i++) {
            identity[i] = i;
        }
        int[] perm = SolverPermutation.computePermutation(matrix, fixed, identity, n, OrderingMethod.AMD);
        int[] invPerm = new int[n];
        for (int i = 0; i < n; i++) {
            invPerm[perm[i]] = i;
        }
        double[] permutedRhs = new double[n];
        for (int newIdx = 0; newIdx < n; newIdx++) {
            permutedRhs[newIdx] = rhs[perm[newIdx]];
        }

        long ejmlFactorStart = System.nanoTime();
        FactorizedSystem ejml = new EjmlCholeskyFactor(
                matrix.toPermutedUpperCompressedSparseColumn(n, fixed, identity, identity, perm, invPerm), n);
        long ejmlFactorEnd = System.nanoTime();
        double[] solution = new double[n];
        for (int rep = 0; rep < SOLVE_REPETITIONS; rep++) {
            ejml.solve(permutedRhs, solution);
        }
        long ejmlSolvesEnd = System.nanoTime();
        Platforms.log("[micro] ejml    n=%d factor %.3fs, %d solves %.3fs (%.1fms each)%n",
                n, (ejmlFactorEnd - ejmlFactorStart) / 1.0e9, SOLVE_REPETITIONS,
                (ejmlSolvesEnd - ejmlFactorEnd) / 1.0e9,
                (ejmlSolvesEnd - ejmlFactorEnd) / 1.0e6 / SOLVE_REPETITIONS);

        Assumptions.assumeTrue(CholeskyBackend.pardisoAvailable(), "PARDISO not loadable");
        long pardisoFactorStart = System.nanoTime();
        FactorizedSystem pardiso = new PardisoCholesky(
                matrix.toPermutedUpperCompressedSparseRow(n, fixed, identity, identity, perm, invPerm), n);
        long pardisoFactorEnd = System.nanoTime();
        for (int rep = 0; rep < SOLVE_REPETITIONS; rep++) {
            pardiso.solve(permutedRhs, solution);
        }
        long pardisoSolvesEnd = System.nanoTime();
        Platforms.log("[micro] pardiso n=%d factor %.3fs, %d solves %.3fs (%.1fms each)%n",
                n, (pardisoFactorEnd - pardisoFactorStart) / 1.0e9, SOLVE_REPETITIONS,
                (pardisoSolvesEnd - pardisoFactorEnd) / 1.0e9,
                (pardisoSolvesEnd - pardisoFactorEnd) / 1.0e6 / SOLVE_REPETITIONS);
        System.out.println("[micro] pardiso refinement steps performed (iparm[6]) = "
                + ((PardisoCholesky) pardiso).iparmNative.get(6)
                + ", nnz(L) (iparm[17]) = " + ((PardisoCholesky) pardiso).iparmNative.get(17));
        pardiso.release();
    }
}
