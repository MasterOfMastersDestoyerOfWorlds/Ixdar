package ixdar.geometry.mesh.quadlayout.solver;

import java.util.Arrays;

import org.ejml.data.DMatrixRMaj;
import org.ejml.data.DMatrixSparseCSC;
import org.ejml.interfaces.linsol.LinearSolverSparse;
import org.ejml.sparse.FillReducing;
import org.ejml.sparse.csc.factory.LinearSolverFactory_DSCC;

import ixdar.geometry.mesh.quadlayout.NormalMatrix;

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
     * @param matrix   the system matrix
     * @param fixed    the per-variable fixed flag
     * @param ordering fill-reducing column ordering applied before the cold
     *                 factor; pick {@link OrderingMethod#RCM} for the
     *                 cross-field bandwidth-minimising path or
     *                 {@link OrderingMethod#AMD} for the seamless stage where
     *                 nnz(L) dominates
     * @return the Cholesky handle
     */
    public static CholeskyHandle factorize(NormalMatrix matrix, boolean[] fixed,
            OrderingMethod ordering) {
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

        int[] perm = SolverPermutation.computePermutation(matrix, fixed, compactOf, freeCount, ordering);
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
     * using a sparse Cholesky factorization with the requested fill-reducing
     * ordering, holding the fixed entries at {@code start[i]}. Throws when the
     * matrix is not positive definite (e.g. for closed surfaces with no
     * anchored variable).
     *
     * @param matrix   symmetric system matrix A
     * @param start    initial values; only the fixed entries are read
     * @param fixed    per-variable fixed flag
     * @param ordering fill-reducing column ordering applied before the
     *                 factorization
     * @throws IllegalStateException if the Cholesky factorization fails
     * @return solution with fixed entries copied from {@code start}
     */
    public static double[] solve(NormalMatrix matrix,
            double[] start,
            boolean[] fixed,
            OrderingMethod ordering) {
        CholeskyHandle handle = factorize(matrix, fixed, ordering);
        double[] out = start.clone();
        solveCompact(handle, matrix, matrix.rhs, out, start, fixed);
        return out;
    }

}
