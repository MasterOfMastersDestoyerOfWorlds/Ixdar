package unit.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.ejml.data.DMatrixSparseCSC;
import org.ejml.sparse.csc.decomposition.chol.CholeskyUpLooking_DSCC;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.solver.SolverPermutation;

/**
 * Validates the AMD ordering in
 * {@link SolverPermutation#approximateMinimumDegree(int[][])}:
 *
 * <ul>
 *   <li>output is a valid permutation (each index 0..n-1 once);</li>
 *   <li>on a 2-D grid Laplacian, AMD produces fewer non-zeros in L than
 *       NATURAL and at-most-as-many as RCM — the empirical reason BZK09
 *       picks a fill-reducing ordering at all.</li>
 * </ul>
 *
 * <p>This test bypasses the higher-level solver wrappers: it drives
 * {@link SolverPermutation} and EJML's Cholesky directly so the assertion is
 * about the ordering itself, not the plumbing around it.
 */
class AmdTest {

    /** Side length of the grid Laplacian used to compare orderings. */
    private static final int GRID_SIDE = 20;
    /** SPD diagonal boost — keeps the test matrix strictly positive definite. */
    private static final double DIAGONAL_BOOST = 1.0;

    @Test
    void amdProducesValidPermutation() {
        int[][] adjacency = buildGridAdjacency(GRID_SIDE);
        int[] perm = SolverPermutation.approximateMinimumDegree(adjacency);

        assertEquals(adjacency.length, perm.length, "perm length must match adj");
        int[] sorted = perm.clone();
        Arrays.sort(sorted);
        for (int i = 0; i < sorted.length; i++) {
            assertEquals(i, sorted[i],
                    "AMD output must be a permutation of 0..n-1");
        }
    }

    @Test
    void amdReducesFillOverNaturalAndIsCompetitiveWithRcm() {
        int[][] adjacency = buildGridAdjacency(GRID_SIDE);
        int dimension = adjacency.length;

        int[] identityPerm = new int[dimension];
        for (int i = 0; i < dimension; i++) {
            identityPerm[i] = i;
        }
        int[] rcmPerm = SolverPermutation.reverseCuthillMcKee(adjacency);
        int[] amdPerm = SolverPermutation.approximateMinimumDegree(adjacency);

        int nnzNatural = cholFactorNnz(adjacency, identityPerm);
        int nnzRcm = cholFactorNnz(adjacency, rcmPerm);
        int nnzAmd = cholFactorNnz(adjacency, amdPerm);

        assertTrue(nnzAmd <= nnzNatural,
                "AMD must not produce more L fill than NATURAL: "
                        + "nnz(L_AMD)=" + nnzAmd + " nnz(L_NATURAL)=" + nnzNatural);
        assertTrue(nnzAmd <= nnzRcm,
                "AMD should be at-most-as-many L non-zeros as RCM on a grid Laplacian: "
                        + "nnz(L_AMD)=" + nnzAmd + " nnz(L_RCM)=" + nnzRcm);
    }

    /**
     * Cold-factor the grid Laplacian under the given permutation and return
     * the number of non-zeros in L. The actual factorization is delegated to
     * EJML's {@link CholeskyUpLooking_DSCC}; this test isolates the effect of
     * the ordering by passing already-permuted matrices with no further
     * reordering.
     *
     * @param adjacency original adjacency in natural order
     * @param perm      column ordering, {@code perm[newIdx] = oldIdx}
     * @return {@code nz_length} of the Cholesky factor L
     */
    private static int cholFactorNnz(int[][] adjacency, int[] perm) {
        int n = adjacency.length;
        int[] invPerm = new int[n];
        for (int i = 0; i < n; i++) {
            invPerm[perm[i]] = i;
        }
        DMatrixSparseCSC permuted = buildPermutedGridLaplacian(adjacency, perm, invPerm);
        CholeskyUpLooking_DSCC cholesky = new CholeskyUpLooking_DSCC();
        assertTrue(cholesky.decompose(permuted),
                "cold factor must succeed for the test grid Laplacian");
        DMatrixSparseCSC lFactor = cholesky.getL();
        assertNotNull(lFactor, "Cholesky.getL() should not be null after decompose");
        return lFactor.nz_length;
    }

    /**
     * Build the 4-neighbour adjacency for a {@code side × side} grid.
     *
     * @param side side length of the grid
     * @return per-vertex neighbour lists, length {@code side * side}
     */
    private static int[][] buildGridAdjacency(int side) {
        int n = side * side;
        int[][] adj = new int[n][];
        int[] scratch = new int[4];
        for (int row = 0; row < side; row++) {
            for (int col = 0; col < side; col++) {
                int self = row * side + col;
                int count = 0;
                if (row > 0) {
                    scratch[count++] = (row - 1) * side + col;
                }
                if (row < side - 1) {
                    scratch[count++] = (row + 1) * side + col;
                }
                if (col > 0) {
                    scratch[count++] = row * side + (col - 1);
                }
                if (col < side - 1) {
                    scratch[count++] = row * side + (col + 1);
                }
                adj[self] = Arrays.copyOf(scratch, count);
            }
        }
        return adj;
    }

    /**
     * Assemble the grid Laplacian's upper-triangle CSC under a given column
     * permutation. Diagonal value is {@code degree + DIAGONAL_BOOST}, off-diagonal
     * entries are {@code -1}.
     *
     * @param adjacency original adjacency in natural (un-permuted) order
     * @param perm      permuted-index → old-index map
     * @param invPerm   old-index → permuted-index map
     * @return upper-triangle CSC of the permuted Laplacian
     */
    private static DMatrixSparseCSC buildPermutedGridLaplacian(int[][] adjacency,
            int[] perm, int[] invPerm) {
        int n = adjacency.length;
        int upperOffDiagonalEntries = 0;
        for (int oldRow = 0; oldRow < n; oldRow++) {
            int permRow = invPerm[oldRow];
            for (int neighbour : adjacency[oldRow]) {
                int permCol = invPerm[neighbour];
                if (permRow < permCol) {
                    upperOffDiagonalEntries++;
                }
            }
        }
        int nnz = n + upperOffDiagonalEntries;
        DMatrixSparseCSC matrix = new DMatrixSparseCSC(n, n, nnz);
        int[] columnEntryCount = new int[n + 1];
        for (int oldRow = 0; oldRow < n; oldRow++) {
            int permRow = invPerm[oldRow];
            columnEntryCount[permRow + 1]++;
            for (int neighbour : adjacency[oldRow]) {
                int permCol = invPerm[neighbour];
                if (permRow < permCol) {
                    columnEntryCount[permCol + 1]++;
                }
            }
        }
        for (int c = 0; c < n; c++) {
            columnEntryCount[c + 1] += columnEntryCount[c];
        }
        System.arraycopy(columnEntryCount, 0, matrix.col_idx, 0, n + 1);

        int[] cursor = columnEntryCount.clone();
        for (int permCol = 0; permCol < n; permCol++) {
            int oldCol = perm[permCol];
            int slot = cursor[permCol]++;
            matrix.nz_rows[slot] = permCol;
            matrix.nz_values[slot] = adjacency[oldCol].length + DIAGONAL_BOOST;
        }
        for (int oldRow = 0; oldRow < n; oldRow++) {
            int permRow = invPerm[oldRow];
            for (int neighbour : adjacency[oldRow]) {
                int permCol = invPerm[neighbour];
                if (permRow < permCol) {
                    int slot = cursor[permCol]++;
                    matrix.nz_rows[slot] = permRow;
                    matrix.nz_values[slot] = -1.0;
                }
            }
        }
        matrix.nz_length = nnz;
        return matrix;
    }
}
