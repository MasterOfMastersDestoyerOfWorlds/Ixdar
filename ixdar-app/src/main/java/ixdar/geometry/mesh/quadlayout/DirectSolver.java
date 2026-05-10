package ixdar.geometry.mesh.quadlayout;

import java.util.ArrayDeque;
import java.util.Arrays;

import org.ejml.data.DMatrixRMaj;
import org.ejml.data.DMatrixSparseCSC;
import org.ejml.data.DMatrixSparseTriplet;
import org.ejml.interfaces.linsol.LinearSolverSparse;
import org.ejml.ops.DConvertMatrixStruct;
import org.ejml.sparse.FillReducing;
import org.ejml.sparse.csc.factory.LinearSolverFactory_DSCC;

public final class DirectSolver {

    /**
     * Pre-computed Cholesky factorization plus the metadata needed to apply it to a
     * right-hand side: the RCM permutation, the free/full index mapping, and
     * reusable {@link DMatrixRMaj} scratch buffers for the triangular solve.
     *
     * <p>
     * Build with {@link AdaptiveSolver#factorize}. Reuse across many right-hand
     * sides via {@link AdaptiveSolver#solveCompact}. <strong>Not
     * thread-safe</strong>: EJML's sparse Cholesky solver keeps internal scratch
     * state during {@code solve()}, so a handle should be used by one thread at a
     * time (typically held in a {@link ThreadLocal}).
     *
     * @param n         full-matrix dimension (size of the original
     *                  {@link NormalMatrix})
     * @param freeCount number of free (non-fixed) variables
     * @param compactOf {@code compactOf[fullIndex]} = compact (free-only) index, or
     *                  -1 if fixed
     * @param fullOf    {@code fullOf[compactIndex]} = original full-matrix row/col
     * @param perm      {@code perm[newCompactIndex]} = old compact index (RCM
     *                  ordering)
     * @param invPerm   {@code invPerm[oldCompactIndex]} = new compact index
     * @param solver    factorized EJML solver; {@code null} when
     *                  {@code freeCount == 0}
     * @param b         reusable RHS buffer in permuted (new-compact) order;
     *                  {@code null} when {@code freeCount == 0}
     * @param x         reusable solution buffer in permuted (new-compact) order;
     *                  {@code null} when {@code freeCount == 0}
     */
    public record CholeskyHandle(
            int n,
            int freeCount,
            int[] compactOf,
            int[] fullOf,
            int[] perm,
            int[] invPerm,
            LinearSolverSparse<DMatrixSparseCSC, DMatrixRMaj> solver,
            DMatrixRMaj b,
            DMatrixRMaj x) {
    }

    /**
     * Factorize the compact system for the free variables (those with
     * {@code !fixed[i]}) using a sparse Cholesky factorization with
     * reverse-Cuthill-McKee ordering.
     *
     * @param matrix the system matrix
     * @param fixed  the per-variable fixed flag
     * @return the Cholesky handle
     */
    public static CholeskyHandle factorize(NormalMatrix matrix, boolean[] fixed) {
        int n = matrix.size();
        int[] compactOf = new int[n];
        int[] fullOf = new int[n];
        Arrays.fill(compactOf, -1);
        int freeCount = 0;
        for (int i = 0; i < n; i++) {
            if (!fixed[i]) {
                compactOf[i] = freeCount;
                fullOf[freeCount] = i;
                freeCount++;
            }
        }
        if (freeCount == 0) {
            return new CholeskyHandle(n, 0, compactOf, fullOf,
                    new int[0], new int[0], null, null, null);
        }

        // Step 2: build adjacency list of the compact (free-only) matrix
        // for the reordering algorithm
        int[][] adj = buildAdjacency(matrix, fixed, compactOf, freeCount);

        // Step 3: compute reordering using Reverse Cuthill-McKee
        int[] perm = reverseCuthillMcKee(adj);
        int[] invPerm = new int[freeCount];
        for (int i = 0; i < freeCount; i++) {
            invPerm[perm[i]] = i;
        }

        // Step 4: build the permuted matrix (in new variable order) for EJML.
        // EJML's Cholesky needs the upper triangle in CSC format.
        NormalMatrix.CompressedSparseColumnArrays csc = matrix.toPermutedUpperCompressedSparseColumn(freeCount, fixed,
                compactOf, fullOf,
                perm, invPerm);
        DMatrixSparseCSC ejmlCsc = new DMatrixSparseCSC(freeCount, freeCount, csc.values().length);
        ejmlCsc.col_idx = csc.colPtr();
        ejmlCsc.nz_rows = csc.rowIdx();
        ejmlCsc.nz_values = csc.values();
        ejmlCsc.nz_length = csc.values().length;
        // Step 5: factorize and solve
        var solver = LinearSolverFactory_DSCC.cholesky(FillReducing.NONE);
        solver.setA(ejmlCsc);
        DMatrixRMaj b = new DMatrixRMaj(freeCount, 1);
        DMatrixRMaj solX = new DMatrixRMaj(freeCount, 1);
        CholeskyHandle handle = new CholeskyHandle(n, freeCount, compactOf, fullOf, perm, invPerm, solver, b, solX);
        return handle;
    }

    /**
     * Solve the compact system for the free variables (those with
     * {@code !fixed[i]}) using a sparse Cholesky factorization with
     * reverse-Cuthill-McKee ordering, holding the fixed entries at
     * {@code start[i]}.
     *
     * @param handle the Cholesky handle
     * @param matrix the system matrix
     * @param rhs    the right-hand side
     * @param out    the output solution
     * @param start  the initial values; only the fixed entries are read
     * @param fixed  the per-variable fixed flag
     */
    public static void solveCompact(CholeskyHandle handle, NormalMatrix matrix, double[] rhs,
            double[] out, double[] start, boolean[] fixed) {
        int n = handle.n();
        if (out != start) {
            System.arraycopy(start, 0, out, 0, n);
        }
        int freeCount = handle.freeCount();
        if (freeCount == 0) {
            return;
        }

        // Build the permuted RHS, folding fixed contributions into it
        // (a_ij * x_j moves from LHS to RHS when x_j is fixed).
        double[] bData = handle.b().data;
        double[] xData = handle.x().data;
        int[] perm = handle.perm();
        int[] fullOf = handle.fullOf();
        int[] rowStart = matrix.rowStart;
        int[] rowCol = matrix.rowCol;
        double[] rowVal = matrix.rowVal;
        for (int newRow = 0; newRow < freeCount; newRow++) {
            int row = fullOf[perm[newRow]];
            double value = rhs[row];
            for (int c = rowStart[row]; c < rowStart[row + 1]; c++) {
                int col = rowCol[c];
                if (fixed[col]) {
                    value -= rowVal[c] * start[col];
                }
            }
            bData[newRow] = value;
        }

        handle.solver().solve(handle.b(), handle.x());
        for (int newRow = 0; newRow < freeCount; newRow++) {
            out[fullOf[perm[newRow]]] = xData[newRow];
        }
    }

    /**
     * Solve {@code A x = b} for the free variables (those with {@code !fixed[i]})
     * using a sparse Cholesky factorization with reverse-Cuthill-McKee ordering,
     * holding the fixed entries at {@code start[i]}. Throws when the matrix is not
     * positive definite (e.g. for closed surfaces with no anchored variable).
     *
     * @param matrix symmetric system matrix A
     * @param start  initial values; only the fixed entries are read
     * @param fixed  per-variable fixed flag
     * @throws IllegalStateException if the Cholesky factorization fails
     * @return solution with fixed entries copied from {@code start}
     */
    public static double[] solve(NormalMatrix matrix,
            double[] start,
            boolean[] fixed) {
        CholeskyHandle handle = factorize(matrix, fixed);
        double[] out = start.clone();
        solveCompact(handle, matrix, matrix.rhs, out, start, fixed);
        return out;
    }

    /**
     * Build adjacency list of the compact symmetric matrix (free vars only).
     *
     * @param matrix    full symmetric system matrix A
     * @param fixed     mask of held-fixed variables
     * @param compactOf full-index → compact-index lookup (or {@code -1} for fixed
     *                  rows)
     * @param freeCount number of free variables (size of the compact problem)
     * @return per-free-variable list of free-variable neighbours (off-diagonal
     *         only)
     */
    private static int[][] buildAdjacency(NormalMatrix matrix,
            boolean[] fixed,
            int[] compactOf,
            int freeCount) {
        int n = matrix.size();
        int[] degree = new int[freeCount];
        for (int i = 0; i < n; i++) {
            if (fixed[i])
                continue;
            int u = compactOf[i];
            for (int c = matrix.rowStart(i); c < matrix.rowEnd(i); c++) {
                int col = matrix.column(c);
                if (!fixed[col] && col != i) {
                    degree[u]++;
                }
            }
        }
        int[][] adj = new int[freeCount][];
        for (int u = 0; u < freeCount; u++) {
            adj[u] = new int[degree[u]];
        }
        int[] cursor = new int[freeCount];
        for (int i = 0; i < n; i++) {
            if (fixed[i])
                continue;
            int u = compactOf[i];
            for (int c = matrix.rowStart(i); c < matrix.rowEnd(i); c++) {
                int col = matrix.column(c);
                if (!fixed[col] && col != i) {
                    adj[u][cursor[u]++] = compactOf[col];
                }
            }
        }
        return adj;
    }

    /**
     * Reverse Cuthill-McKee ordering. Returns perm[newIndex] = oldIndex.
     *
     * @param adj per-vertex neighbour lists for the compact problem
     * @return permutation mapping new compact index to old compact index
     */
    private static int[] reverseCuthillMcKee(int[][] adj) {
        int n = adj.length;
        int[] perm = new int[n];
        boolean[] visited = new boolean[n];
        int filled = 0;

        while (filled < n) {
            // Find unvisited node of minimum degree as the BFS start
            int start = -1;
            int minDeg = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (!visited[i] && adj[i].length < minDeg) {
                    minDeg = adj[i].length;
                    start = i;
                }
            }

            // BFS, sorting each level's neighbors by degree
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            visited[start] = true;
            while (!queue.isEmpty()) {
                int u = queue.poll();
                perm[filled++] = u;
                int[] nbrs = adj[u].clone();
                // Sort unvisited neighbors by ascending degree
                Integer[] boxed = new Integer[nbrs.length];
                for (int i = 0; i < nbrs.length; i++)
                    boxed[i] = nbrs[i];
                Arrays.sort(boxed, (a, b) -> adj[a].length - adj[b].length);
                for (int v : boxed) {
                    if (!visited[v]) {
                        visited[v] = true;
                        queue.add(v);
                    }
                }
            }
        }

        // Reverse the order
        int[] reversed = new int[n];
        for (int i = 0; i < n; i++) {
            reversed[i] = perm[n - 1 - i];
        }
        return reversed;
    }
}
