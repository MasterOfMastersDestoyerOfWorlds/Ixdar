package unit.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.ejml.data.DMatrixSparseCSC;
import org.ejml.sparse.csc.decomposition.chol.CholeskyUpLooking_DSCC;
import org.ejml.sparse.csc.decomposition.chol.CholeskyUpdate_DSCC;
import org.ejml.sparse.csc.misc.TriangularSolver_DSCC;
import org.junit.jupiter.api.Test;

/**
 * Numerical parity tests for {@link CholeskyUpdate_DSCC}: each rank-1
 * update of L via {@code pinDiagonal} must produce the same solution to
 * {@code A' x = b} (within float precision) as a fresh cold factor of the
 * perturbed matrix {@code A' = A + μ · e_col · e_colᵀ}.
 *
 * <p>Tests directly drive the EJML-package classes rather than the higher
 * level {@code IncrementalCholeskySolver} wrapper, since the rank-1 update
 * is the load-bearing piece — the wrapper is just plumbing.
 */
class IncrementalCholeskySolverTest {

    /** Tolerance for per-entry solution comparison vs cold-factor baseline. */
    private static final double SOLUTION_ABS_TOLERANCE = 1.0e-9;
    /** Pin penalty weight used by IGM rounding ({@code integerPinWeight}). */
    private static final double PIN_PENALTY = 1.0e10;
    /** Diagonal boost so the test Laplacian-style matrix is strictly SPD. */
    private static final double DIAGONAL_BOOST = 1.0;
    /** Test problem dimension — small enough to be exhaustive, big enough to fill. */
    private static final int DIMENSION = 12;

    @Test
    void singlePinMatchesColdFactor() {
        DMatrixSparseCSC original = buildSpdUpperTriangleLaplacian(DIMENSION);
        double[] rhs = buildRhs(DIMENSION, 42L);

        // Baseline: pin column 5 by direct mutation, then cold-factor and solve.
        int pinColumn = 5;
        DMatrixSparseCSC perturbedCold = copy(original);
        addToDiagonal(perturbedCold, pinColumn, PIN_PENALTY);
        double[] coldSolution = solveByColdFactor(perturbedCold, rhs);

        // Incremental: cold-factor original, rank-1 update, solve.
        CholeskyUpdate_DSCC factor = new CholeskyUpdate_DSCC();
        assertTrue(factor.decompose(copy(original)),
                "cold factor of the unpinned matrix must succeed");
        assertTrue(factor.pinDiagonal(pinColumn, PIN_PENALTY),
                "pinDiagonal with positive μ must succeed");
        double[] incrementalSolution = solveWithFactor(factor, rhs);

        assertSolutionsMatch(coldSolution, incrementalSolution);
    }

    @Test
    void multiplePinsMatchColdFactor() {
        DMatrixSparseCSC original = buildSpdUpperTriangleLaplacian(DIMENSION);
        double[] rhs = buildRhs(DIMENSION, 7L);
        int[] pinSequence = { 1, 4, 8, 3, 11, 0 };

        // Baseline: apply all pins, cold-factor, solve.
        DMatrixSparseCSC perturbedCold = copy(original);
        for (int col : pinSequence) {
            addToDiagonal(perturbedCold, col, PIN_PENALTY);
        }
        double[] coldSolution = solveByColdFactor(perturbedCold, rhs);

        // Incremental: cold-factor original, apply pins one-by-one, solve.
        CholeskyUpdate_DSCC factor = new CholeskyUpdate_DSCC();
        assertTrue(factor.decompose(copy(original)));
        for (int col : pinSequence) {
            assertTrue(factor.pinDiagonal(col, PIN_PENALTY),
                    "pin sequence step at column " + col + " failed");
        }
        double[] incrementalSolution = solveWithFactor(factor, rhs);

        assertSolutionsMatch(coldSolution, incrementalSolution);
    }

    /**
     * Build a small 1-D Laplacian-like SPD matrix {@code A = -tridiag(-1, 2+ε, -1)}
     * with a small diagonal boost so it stays well conditioned. Returned in
     * upper-triangular CSC form (the format EJML's
     * {@link CholeskyUpLooking_DSCC} consumes).
     *
     * @param n matrix dimension
     * @return upper-triangle sparse CSC of the test SPD matrix
     */
    private static DMatrixSparseCSC buildSpdUpperTriangleLaplacian(int n) {
        // nnz = n (diagonal) + (n-1) (super-diagonal).
        DMatrixSparseCSC matrix = new DMatrixSparseCSC(n, n, 2 * n - 1);
        matrix.col_idx[0] = 0;
        int slot = 0;
        for (int col = 0; col < n; col++) {
            if (col > 0) {
                matrix.nz_rows[slot] = col - 1;
                matrix.nz_values[slot] = -1.0;
                slot++;
            }
            matrix.nz_rows[slot] = col;
            matrix.nz_values[slot] = 2.0 + DIAGONAL_BOOST;
            slot++;
            matrix.col_idx[col + 1] = slot;
        }
        matrix.nz_length = slot;
        return matrix;
    }

    /**
     * Deterministic random RHS for stable test comparisons.
     *
     * @param n    vector length
     * @param seed PRNG seed
     * @return length-{@code n} RHS vector
     */
    private static double[] buildRhs(int n, long seed) {
        Random rng = new Random(seed);
        double[] rhs = new double[n];
        for (int i = 0; i < n; i++) {
            rhs[i] = rng.nextDouble() - 0.5;
        }
        return rhs;
    }

    /**
     * Add {@code value} to the diagonal entry at column {@code col}. The
     * matrix is in upper-triangle CSC, so the diagonal lives at the LAST
     * row entry within each column.
     *
     * @param matrix upper-triangle CSC matrix to mutate
     * @param col    column whose diagonal to bump
     * @param value  amount to add
     */
    private static void addToDiagonal(DMatrixSparseCSC matrix, int col, double value) {
        int slot = matrix.col_idx[col + 1] - 1;
        if (matrix.nz_rows[slot] != col) {
            throw new IllegalStateException(
                    "expected diagonal entry at column " + col + " end");
        }
        matrix.nz_values[slot] += value;
    }

    /**
     * Deep copy a {@link DMatrixSparseCSC} for non-destructive testing.
     *
     * @param source the matrix to copy
     * @return a structurally identical copy with independent backing arrays
     */
    private static DMatrixSparseCSC copy(DMatrixSparseCSC source) {
        DMatrixSparseCSC copy = new DMatrixSparseCSC(
                source.numRows, source.numCols, source.nz_length);
        System.arraycopy(source.col_idx, 0, copy.col_idx, 0, source.numCols + 1);
        System.arraycopy(source.nz_rows, 0, copy.nz_rows, 0, source.nz_length);
        System.arraycopy(source.nz_values, 0, copy.nz_values, 0, source.nz_length);
        copy.nz_length = source.nz_length;
        return copy;
    }

    /**
     * Cold-factor {@code matrix} with stock EJML Cholesky and solve
     * {@code A x = rhs}.
     *
     * @param matrix upper-triangle CSC of an SPD matrix
     * @param rhs    right-hand side
     * @return solution vector
     */
    private static double[] solveByColdFactor(DMatrixSparseCSC matrix, double[] rhs) {
        CholeskyUpLooking_DSCC cold = new CholeskyUpLooking_DSCC();
        assertTrue(cold.decompose(matrix), "cold factor must succeed");
        return solveWithFactor(cold, rhs);
    }

    /**
     * Forward + backward substitute with the given factor's L.
     *
     * @param factor a {@link CholeskyUpLooking_DSCC} (or subclass) whose
     *               {@code decompose} has succeeded
     * @param rhs    right-hand side
     * @return solution vector
     */
    private static double[] solveWithFactor(CholeskyUpLooking_DSCC factor, double[] rhs) {
        double[] solution = rhs.clone();
        TriangularSolver_DSCC.solveL(factor.getL(), solution);
        TriangularSolver_DSCC.solveTranL(factor.getL(), solution);
        return solution;
    }

    /**
     * Assert two solution vectors agree element-wise to
     * {@link #SOLUTION_ABS_TOLERANCE}.
     *
     * @param expected expected solution
     * @param actual   actual solution
     */
    private static void assertSolutionsMatch(double[] expected, double[] actual) {
        assertEquals(expected.length, actual.length, "dimension mismatch");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], SOLUTION_ABS_TOLERANCE,
                    "solution differs at index " + i
                            + " expected=" + expected[i]
                            + " actual=" + actual[i]);
        }
    }
}
