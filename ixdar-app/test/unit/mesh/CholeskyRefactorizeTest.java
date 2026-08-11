package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.solver.CholeskyBackend;
import ixdar.geometry.mesh.quadlayout.solver.DirectSolver;
import ixdar.geometry.mesh.quadlayout.solver.EjmlCholeskyFactor;
import ixdar.geometry.mesh.quadlayout.solver.FactorizedSystem;
import ixdar.geometry.mesh.quadlayout.solver.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.OrderingMethod;
import ixdar.geometry.mesh.quadlayout.solver.PardisoCholesky;

/**
 * Numeric-only refactorization test: after
 * {@link FactorizedSystem#refactorize(double[])} with new values on the
 * identical non-zero pattern, solves must match a fresh factorization of the
 * new values to tight tolerance, on both backends.
 */
public final class CholeskyRefactorizeTest {

    private static final int GRID_SIDE = 12;
    private static final double TOLERANCE = 1.0e-9;
    private static final long RHS_SEED = 20260809L;
    private static final double FIRST_EDGE_WEIGHT = 1.0;
    private static final double SECOND_EDGE_WEIGHT = 2.5;

    /**
     * Build a weighted 2D grid-graph Laplacian plus identity — SPD, with a
     * non-zero pattern independent of {@code edgeWeight} and a seeded random RHS.
     *
     * @param edgeWeight coupling weight on every grid edge; varying it changes
     *                   only values, never the pattern
     * @return the assembled SPD system
     */
    private static NormalMatrix buildGridLaplacian(double edgeWeight) {
        int n = GRID_SIDE * GRID_SIDE;
        double[] diagonal = new double[n];
        double[] rhs = new double[n];
        Map<Long, Double> upper = new HashMap<>();
        Random random = new Random(RHS_SEED);
        Arrays.fill(diagonal, 1.0);
        for (int y = 0; y < GRID_SIDE; y++) {
            for (int x = 0; x < GRID_SIDE; x++) {
                int i = y * GRID_SIDE + x;
                rhs[i] = random.nextDouble() * 2.0 - 1.0;
                if (x + 1 < GRID_SIDE) {
                    couple(upper, diagonal, i, i + 1, edgeWeight);
                }
                if (y + 1 < GRID_SIDE) {
                    couple(upper, diagonal, i, i + GRID_SIDE, edgeWeight);
                }
            }
        }
        return new NormalMatrix(diagonal, upper, rhs);
    }

    /**
     * Add the weighted Laplacian edge (i, j): {@code +weight} to both diagonals,
     * {@code -weight} off-diagonal.
     *
     * @param upper    upper-triangle accumulator keyed by packed (row, col)
     * @param diagonal diagonal accumulator
     * @param i        first variable, {@code i < j}
     * @param j        second variable
     * @param weight   coupling weight of the edge
     */
    private static void couple(Map<Long, Double> upper, double[] diagonal, int i, int j,
            double weight) {
        diagonal[i] += weight;
        diagonal[j] += weight;
        upper.put(((long) i << NormalMatrix.KEY_ROW_SHIFT) | j, -weight);
    }

    /**
     * Identity index arrays sized for the all-free full system.
     *
     * @param n system dimension
     * @return {@code [0, 1, ..., n - 1]}
     */
    private static int[] identity(int n) {
        int[] indices = new int[n];
        for (int i = 0; i < n; i++) {
            indices[i] = i;
        }
        return indices;
    }

    @Test
    public void ejmlRefactorizeMatchesFreshFactor() {
        NormalMatrix first = buildGridLaplacian(FIRST_EDGE_WEIGHT);
        NormalMatrix second = buildGridLaplacian(SECOND_EDGE_WEIGHT);
        int n = first.size();
        boolean[] fixed = new boolean[n];
        int[] identity = identity(n);
        FactorizedSystem factor = new EjmlCholeskyFactor(
                first.toPermutedUpperCompressedSparseColumn(n, fixed, identity, identity,
                        identity, identity),
                n);
        double[] firstSolution = new double[n];
        factor.solve(first.rightHandSide, firstSolution);

        factor.refactorize(second.toPermutedUpperCompressedSparseColumn(n, fixed, identity,
                identity, identity, identity).values());
        double[] refactorizedSolution = new double[n];
        factor.solve(second.rightHandSide, refactorizedSolution);

        FactorizedSystem fresh = new EjmlCholeskyFactor(
                second.toPermutedUpperCompressedSparseColumn(n, fixed, identity, identity,
                        identity, identity),
                n);
        double[] freshSolution = new double[n];
        fresh.solve(second.rightHandSide, freshSolution);
        for (int i = 0; i < n; i++) {
            assertEquals(freshSolution[i], refactorizedSolution[i], TOLERANCE,
                    "ejml refactorized solution mismatch at index " + i);
        }
    }

    @Test
    public void pardisoRefactorizeMatchesFreshFactor() {
        Assumptions.assumeTrue(CholeskyBackend.pardisoAvailable(),
                "PARDISO natives not loadable on this platform");
        NormalMatrix first = buildGridLaplacian(FIRST_EDGE_WEIGHT);
        NormalMatrix second = buildGridLaplacian(SECOND_EDGE_WEIGHT);
        int n = first.size();
        boolean[] fixed = new boolean[n];
        int[] identity = identity(n);
        FactorizedSystem factor = new PardisoCholesky(
                first.toPermutedUpperCompressedSparseRow(n, fixed, identity, identity,
                        identity, identity),
                n);
        double[] firstSolution = new double[n];
        factor.solve(first.rightHandSide, firstSolution);

        factor.refactorize(second.toPermutedUpperCompressedSparseRow(n, fixed, identity,
                identity, identity, identity).values);
        double[] refactorizedSolution = new double[n];
        factor.solve(second.rightHandSide, refactorizedSolution);
        factor.release();

        FactorizedSystem fresh = new PardisoCholesky(
                second.toPermutedUpperCompressedSparseRow(n, fixed, identity, identity,
                        identity, identity),
                n);
        double[] freshSolution = new double[n];
        fresh.solve(second.rightHandSide, freshSolution);
        fresh.release();
        for (int i = 0; i < n; i++) {
            assertEquals(freshSolution[i], refactorizedSolution[i], TOLERANCE,
                    "pardiso refactorized solution mismatch at index " + i);
        }
    }

    @Test
    public void valuesOnlyRefactorizeMatchesFreshSolve() {
        int n = GRID_SIDE * GRID_SIDE;
        int edgeCount = 2 * GRID_SIDE * (GRID_SIDE - 1);
        long[] upperKeys = new long[edgeCount];
        double[] firstDiagonal = new double[n];
        double[] secondDiagonal = new double[n];
        double[] rhs = new double[n];
        Random random = new Random(RHS_SEED);
        int edge = 0;
        for (int y = 0; y < GRID_SIDE; y++) {
            for (int x = 0; x < GRID_SIDE; x++) {
                int i = y * GRID_SIDE + x;
                rhs[i] = random.nextDouble() * 2.0 - 1.0;
                if (x + 1 < GRID_SIDE) {
                    upperKeys[edge++] = ((long) i << NormalMatrix.KEY_ROW_SHIFT) | (i + 1);
                }
                if (y + 1 < GRID_SIDE) {
                    upperKeys[edge++] = ((long) i << NormalMatrix.KEY_ROW_SHIFT)
                            | (i + GRID_SIDE);
                }
                int neighborCount = (x > 0 ? 1 : 0) + (x + 1 < GRID_SIDE ? 1 : 0)
                        + (y > 0 ? 1 : 0) + (y + 1 < GRID_SIDE ? 1 : 0);
                firstDiagonal[i] = 1.0 + neighborCount * FIRST_EDGE_WEIGHT;
                secondDiagonal[i] = 1.0 + neighborCount * SECOND_EDGE_WEIGHT;
            }
        }
        double[] firstUpperValues = new double[edgeCount];
        double[] secondUpperValues = new double[edgeCount];
        Arrays.fill(firstUpperValues, -FIRST_EDGE_WEIGHT);
        Arrays.fill(secondUpperValues, -SECOND_EDGE_WEIGHT);
        boolean[] fixed = new boolean[n];
        NormalMatrix matrix = new NormalMatrix(firstDiagonal, upperKeys, firstUpperValues, rhs);
        DirectSolver.CholeskyHandle handle = DirectSolver.factorize(matrix, fixed,
                OrderingMethod.AMD);
        int[] sources = DirectSolver.valueSources(handle, matrix, fixed, upperKeys);
        double[] valuesBuffer = new double[sources.length];

        matrix.refreshValues(secondDiagonal, secondUpperValues, rhs);
        DirectSolver.refactorizeHandleValues(handle, matrix.diagonal, secondUpperValues,
                sources, valuesBuffer);
        double[] refactorizedSolution = new double[n];
        DirectSolver.solveCompact(handle, matrix, matrix.rightHandSide, refactorizedSolution,
                refactorizedSolution, fixed);
        DirectSolver.releaseHandle(handle);

        double[] freshSolution = DirectSolver.solve(buildGridLaplacian(SECOND_EDGE_WEIGHT),
                new double[n], fixed, OrderingMethod.AMD);
        for (int i = 0; i < n; i++) {
            assertEquals(freshSolution[i], refactorizedSolution[i], TOLERANCE,
                    "values-only refactorized solution mismatch at index " + i);
        }
    }

    @Test
    public void refactorizeHandleMatchesFreshSolve() {
        NormalMatrix first = buildGridLaplacian(FIRST_EDGE_WEIGHT);
        NormalMatrix second = buildGridLaplacian(SECOND_EDGE_WEIGHT);
        int n = first.size();
        boolean[] fixed = new boolean[n];
        DirectSolver.CholeskyHandle handle = DirectSolver.factorize(first, fixed,
                OrderingMethod.AMD);
        double[] firstSolution = new double[n];
        DirectSolver.solveCompact(handle, first, first.rightHandSide, firstSolution,
                firstSolution, fixed);

        DirectSolver.refactorizeHandle(handle, second, fixed);
        double[] refactorizedSolution = new double[n];
        DirectSolver.solveCompact(handle, second, second.rightHandSide, refactorizedSolution,
                refactorizedSolution, fixed);
        DirectSolver.releaseHandle(handle);

        double[] freshSolution = DirectSolver.solve(second, new double[n], fixed,
                OrderingMethod.AMD);
        for (int i = 0; i < n; i++) {
            assertEquals(freshSolution[i], refactorizedSolution[i], TOLERANCE,
                    "refactorized handle solution mismatch at index " + i);
        }
    }
}
