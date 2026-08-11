package ixdar.geometry.mesh.quadlayout.solver;

import java.util.Arrays;

public final class DirectSolver {

    /**
     * Factorize the compact system for the free variables (those with
     * {@code !fixed[i]}) using a sparse Cholesky factorization with the
     * requested fill-reducing ordering.
     *
     * @param matrix   the system matrix
     * @param fixed    the per-variable fixed flag
     * @param ordering fill-reducing column ordering applied before the cold
     *                 factor; default to {@link OrderingMethod#AMD}, and reach
     *                 for {@link OrderingMethod#RCM} only when a bandwidth-minimising
     *                 order is specifically wanted
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
        return factorizeWithPermInternal(matrix, fixed, compactOf, fullOf, freeCount, perm);
    }

    /**
     * Factorize using a caller-supplied permutation, valid only when the matrix
     * non-zero pattern is identical to the {@link #factorize} call the permutation
     * came from. The caller must discard the cached {@code perm} whenever that
     * pattern, the {@code fixed} mask, or the desired ordering changes.
     *
     * @param matrix the system matrix (same non-zero pattern as the
     *               originating call)
     * @param fixed  the per-variable fixed flag (must match the
     *               originating call)
     * @param perm   cached {@code perm[newCompactIndex] = oldCompactIndex}
     *               from a prior {@link SolverPermutation#computePermutation}
     *               call
     * @throws IllegalArgumentException if {@code perm.length} does not
     *                                  match the free-variable count
     * @return the Cholesky handle
     */
    public static CholeskyHandle factorizeWithPerm(NormalMatrix matrix,
            boolean[] fixed, int[] perm) {
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
        if (perm.length != freeCount) {
            throw new IllegalArgumentException(
                    "cached perm length " + perm.length
                            + " must equal current freeCount " + freeCount);
        }
        return factorizeWithPermInternal(matrix, fixed, compactOf, fullOf, freeCount, perm);
    }

    private static CholeskyHandle factorizeWithPermInternal(NormalMatrix matrix,
            boolean[] fixed, int[] compactOf, int[] fullOf, int freeCount, int[] perm) {
        int n = matrix.size();
        int[] invPerm = new int[freeCount];
        for (int i = 0; i < freeCount; i++) {
            invPerm[perm[i]] = i;
        }
        FactorizedSystem factor = CholeskyBackend.factor(matrix, freeCount, fixed,
                compactOf, fullOf, perm, invPerm);
        return new CholeskyHandle(n, freeCount, compactOf, fullOf, perm, invPerm,
                factor, new double[freeCount], new double[freeCount]);
    }

    /**
     * Recompute the handle's numeric factor from {@code matrix}'s values,
     * reusing the symbolic analysis. Valid only when the matrix non-zero
     * pattern and {@code fixed} mask are identical to the factorizing call.
     *
     * @param handle handle whose factor is refactorized in place
     * @param matrix system matrix with new values on the identical pattern
     * @param fixed  per-variable fixed flag (must match the factorizing call)
     */
    public static void refactorizeHandle(CholeskyHandle handle, NormalMatrix matrix,
            boolean[] fixed) {
        if (handle.freeCount() == 0) {
            return;
        }
        if (CholeskyBackend.pardisoAvailable()) {
            handle.factor().refactorize(matrix.toPermutedUpperCompressedSparseRow(
                    handle.freeCount(), fixed, handle.compactOf(), handle.fullOf(),
                    handle.perm(), handle.invPerm()).values);
        } else {
            handle.factor().refactorize(matrix.toPermutedUpperCompressedSparseColumn(
                    handle.freeCount(), fixed, handle.compactOf(), handle.fullOf(),
                    handle.perm(), handle.invPerm()).values());
        }
    }

    /**
     * Per-position value sources of the handle's backend buffer: an upper slot
     * index, or a diagonal index encoded as {@code -(index + 1)}.
     *
     * @param handle    handle whose permuted layout the map describes
     * @param matrix    the matrix the handle factorized
     * @param fixed     per-variable fixed flag, must match the factorizing call
     * @param upperKeys sorted packed keys the matrix was built from
     * @return one encoded source per backend values position
     * @throws IllegalStateException when a backend entry has no matching key
     */
    public static int[] valueSources(CholeskyHandle handle, NormalMatrix matrix,
            boolean[] fixed, long[] upperKeys) {
        int freeCount = handle.freeCount();
        if (freeCount == 0) {
            return new int[0];
        }
        int[] perm = handle.perm();
        int[] fullOf = handle.fullOf();
        if (CholeskyBackend.pardisoAvailable()) {
            CompressedSparseRowArrays upperCsr = matrix.toPermutedUpperCompressedSparseRow(
                    freeCount, fixed, handle.compactOf(), fullOf, perm, handle.invPerm());
            int[] sources = new int[upperCsr.values.length];
            for (int permutedRow = 0; permutedRow < freeCount; permutedRow++) {
                for (int position = upperCsr.rowPtr[permutedRow];
                        position < upperCsr.rowPtr[permutedRow + 1]; position++) {
                    sources[position] = valueSourceOf(permutedRow, upperCsr.colIdx[position],
                            perm, fullOf, upperKeys);
                }
            }
            return sources;
        }
        NormalMatrix.CompressedSparseColumnArrays upperCsc = matrix
                .toPermutedUpperCompressedSparseColumn(freeCount, fixed, handle.compactOf(),
                        fullOf, perm, handle.invPerm());
        int[] sources = new int[upperCsc.values().length];
        for (int permutedColumn = 0; permutedColumn < freeCount; permutedColumn++) {
            for (int position = upperCsc.colPtr()[permutedColumn];
                    position < upperCsc.colPtr()[permutedColumn + 1]; position++) {
                sources[position] = valueSourceOf(upperCsc.rowIdx()[position], permutedColumn,
                        perm, fullOf, upperKeys);
            }
        }
        return sources;
    }

    /**
     * Encoded source of one backend position: {@code -(variable + 1)} for a
     * diagonal entry, else the upper slot of the entry's unpermuted key.
     *
     * @param permutedRow    entry row in the permuted free index space
     * @param permutedColumn entry column in the permuted free index space
     * @param perm           free-index permutation of the handle
     * @param fullOf         compact-to-full variable map of the handle
     * @param upperKeys      sorted packed keys the matrix was built from
     * @return the encoded source index
     * @throws IllegalStateException when the entry has no matching key
     */
    private static int valueSourceOf(int permutedRow, int permutedColumn, int[] perm,
            int[] fullOf, long[] upperKeys) {
        int rowVariable = fullOf[perm[permutedRow]];
        int columnVariable = fullOf[perm[permutedColumn]];
        if (rowVariable == columnVariable) {
            return -rowVariable - 1;
        }
        long key = ((long) Math.min(rowVariable, columnVariable) << NormalMatrix.KEY_ROW_SHIFT)
                | Math.max(rowVariable, columnVariable);
        int slot = Arrays.binarySearch(upperKeys, key);
        if (slot < 0) {
            throw new IllegalStateException(
                    "backend entry (" + rowVariable + ", " + columnVariable + ") has no key");
        }
        return slot;
    }

    /**
     * Numeric-only refactorize from raw diagonal and upper values through a
     * precomputed source map, skipping the permuted extraction entirely.
     *
     * @param handle       handle with a live factor
     * @param diagonal     current diagonal values, full variable indexing
     * @param upperValues  current upper values in the map's slot order
     * @param sources      map from {@link #valueSources}
     * @param valuesBuffer reusable buffer, length {@code sources.length}
     */
    public static void refactorizeHandleValues(CholeskyHandle handle, double[] diagonal,
            double[] upperValues, int[] sources, double[] valuesBuffer) {
        if (handle.freeCount() == 0) {
            return;
        }
        for (int position = 0; position < sources.length; position++) {
            int source = sources[position];
            valuesBuffer[position] = source < 0 ? diagonal[-source - 1] : upperValues[source];
        }
        handle.factor().refactorize(valuesBuffer);
    }

    /**
     * Solve the compact system for the free variables (those with
     * {@code !fixed[i]}) through the handle's factorization, holding the fixed
     * entries at {@code start[i]}.
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
        double[] permutedRhs = handle.rhsScratch();
        double[] permutedSolution = handle.solutionScratch();
        int[] perm = handle.perm();
        int[] fullOf = handle.fullOf();
        int[] rowStart = matrix.rowStart;
        int[] rowCol = matrix.rowColumn;
        double[] rowVal = matrix.rowValue;
        for (int newRow = 0; newRow < freeCount; newRow++) {
            int row = fullOf[perm[newRow]];
            double value = rhs[row];
            for (int c = rowStart[row]; c < rowStart[row + 1]; c++) {
                int col = rowCol[c];
                if (fixed[col]) {
                    value -= rowVal[c] * start[col];
                }
            }
            permutedRhs[newRow] = value;
        }

        handle.factor().solve(permutedRhs, permutedSolution);
        for (int newRow = 0; newRow < freeCount; newRow++) {
            out[fullOf[perm[newRow]]] = permutedSolution[newRow];
        }
    }

    /**
     * Solve {@code A x = b} for the free variables (those with {@code !fixed[i]})
     * using a sparse Cholesky factorization with the requested fill-reducing
     * ordering, holding the fixed entries at {@code start[i]}. The factorization is
     * released before returning.
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
        solveCompact(handle, matrix, matrix.rightHandSide, out, start, fixed);
        releaseHandle(handle);
        return out;
    }

    /**
     * Solve {@code A x = b} using a caller-supplied cached permutation.
     * Avoids the cost of recomputing the fill-reducing ordering on every
     * call when the matrix non-zero pattern is invariant (the seamless
     * stage's 50 stiffening iterations). The factorization is released
     * before returning.
     *
     * @param matrix symmetric system matrix A
     * @param start  initial values; only the fixed entries are read
     * @param fixed  per-variable fixed flag
     * @param perm   cached column permutation from a prior
     *               {@link SolverPermutation#computePermutation} call
     * @throws IllegalStateException    if the Cholesky factorization fails
     * @throws IllegalArgumentException if {@code perm.length} does not
     *                                  match the free-variable count
     * @return solution with fixed entries copied from {@code start}
     */
    public static double[] solveWithPerm(NormalMatrix matrix,
            double[] start,
            boolean[] fixed,
            int[] perm) {
        CholeskyHandle handle = factorizeWithPerm(matrix, fixed, perm);
        double[] out = start.clone();
        solveCompact(handle, matrix, matrix.rightHandSide, out, start, fixed);
        releaseHandle(handle);
        return out;
    }

    /**
     * Release the handle's backend factorization if it has one. Call when a
     * handle built with {@link #factorize} / {@link #factorizeWithPerm} is no
     * longer needed — native backends hold off-heap memory that the garbage
     * collector only reclaims lazily.
     *
     * @param handle handle whose factor should be freed; tolerates the
     *               {@code freeCount == 0} sentinel with a null factor
     */
    public static void releaseHandle(CholeskyHandle handle) {
        if (handle.factor() != null) {
            handle.factor().release();
        }
    }

    /**
     * Pre-computed Cholesky factorization plus the permutation, index mapping, and
     * scratch vectors needed to apply it to a right-hand side.
     *
     * <p>
     * <strong>Not thread-safe</strong>: the backing factor holds scratch state during
     * {@code solve()}. {@link FactorizedSystem#release()} must be called when the
     * handle is discarded, since native backends hold off-heap memory.
     *
     * @param n               full-matrix dimension (size of the original
     *                        {@link NormalMatrix})
     * @param freeCount       number of free (non-fixed) variables
     * @param compactOf       {@code compactOf[fullIndex]} = compact (free-only)
     *                        index, or -1 if fixed
     * @param fullOf          {@code fullOf[compactIndex]} = original full-matrix
     *                        row/col
     * @param perm            {@code perm[newCompactIndex]} = old compact index
     *                        (fill-reducing ordering)
     * @param invPerm         {@code invPerm[oldCompactIndex]} = new compact index
     * @param factor          backend factorization; {@code null} when
     *                        {@code freeCount == 0}
     * @param rhsScratch      reusable RHS vector in permuted (new-compact) order;
     *                        {@code null} when {@code freeCount == 0}
     * @param solutionScratch reusable solution vector in permuted (new-compact)
     *                        order; {@code null} when {@code freeCount == 0}
     */
    public record CholeskyHandle(
            int n,
            int freeCount,
            int[] compactOf,
            int[] fullOf,
            int[] perm,
            int[] invPerm,
            FactorizedSystem factor,
            double[] rhsScratch,
            double[] solutionScratch) {
    }
}
