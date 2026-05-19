package ixdar.geometry.mesh.quadlayout;

import java.util.Map;

import ixdar.geometry.mesh.quadlayout.solver.AdaptiveSolver;

public final class NormalMatrix {
    public static final double HALF = 0.5;
    /** Bit shift used to pack (row, col) keys into a {@code long}. */
    public static final int KEY_ROW_SHIFT = 32;
    /** Low-32-bit mask used to extract the column from a packed (row, col) key. */
    public static final long KEY_COL_MASK = 0xFFFFFFFFL;
    public final int variableCount;
    public final double[] rightHandSide;
    public final int[] rowStart;
    public final int[] rowColumn;
    public final double[] rowValue;
    public final double[] diagonal;

    /**
     * Constructor with chord-based rows.
     * 
     * @param faceCount   number of faces
     * @param chordCount  number of chords
     * @param rowCount    number of rows
     * @param rowFaceI    row face I
     * @param rowFaceJ    row face J
     * @param rowEdgeAi   row edge A I
     * @param chordOfEdge chord of edge
     * @param periodValue period value
     * @param rowKappa    row kappa
     */
    public NormalMatrix(int faceCount, int chordCount, int rowCount, int[] rowFaceI, int[] rowFaceJ, int[] rowEdgeAi,
            int[] chordOfEdge, int[] periodValue, float[] rowKappa) {
        variableCount = faceCount + chordCount;
        diagonal = new double[variableCount];
        rightHandSide = new double[variableCount];

        int[] degree = new int[variableCount];
        for (int r = 0; r < rowCount; r++) {
            int fi = rowFaceI[r];
            int fj = rowFaceJ[r];
            int chord = chordOfEdge[rowEdgeAi[r]];
            if (chord >= 0) {
                int pe = faceCount + chord;
                degree[fi] += 2;
                degree[fj] += 2;
                degree[pe] += 2;
            } else {
                degree[fi] += 1;
                degree[fj] += 1;
            }
        }

        rowStart = new int[variableCount + 1];
        for (int i = 0; i < variableCount; i++) {
            rowStart[i + 1] = rowStart[i] + degree[i];
        }
        rowColumn = new int[rowStart[variableCount]];
        rowValue = new double[rowStart[variableCount]];
        int[] cursor = rowStart.clone();
        double halfPi = Math.PI * HALF;

        for (int r = 0; r < rowCount; r++) {
            int faceI = rowFaceI[r];
            int faceJ = rowFaceJ[r];
            int edge = rowEdgeAi[r];
            int chord = chordOfEdge[edge];
            int pe = chord >= 0 ? faceCount + chord : -1;
            double k = rowKappa[r] + (chord < 0 ? halfPi * periodValue[edge] : 0.0);

            diagonal[faceI] += 1.0;
            diagonal[faceJ] += 1.0;

            addOffDiagonal(cursor, faceI, faceJ, -1.0);
            addOffDiagonal(cursor, faceJ, faceI, -1.0);

            rightHandSide[faceI] -= k;
            rightHandSide[faceJ] += k;
            if (pe >= 0) {
                diagonal[pe] += halfPi * halfPi;

                addOffDiagonal(cursor, faceI, pe, halfPi);
                addOffDiagonal(cursor, pe, faceI, halfPi);
                addOffDiagonal(cursor, faceJ, pe, -halfPi);
                addOffDiagonal(cursor, pe, faceJ, -halfPi);

                rightHandSide[pe] -= halfPi * k;
            }
        }
    }

    /**
     * Theta-only Laplacian constructor for the local-search post-process. One
     * variable per face; one row per interior edge. Each interior edge between
     * faces (i, j) contributes +1 to diag[i] and diag[j], -1 at off-diagonals (i,
     * j) and (j, i), and -k / +k to rhs[i] / rhs[j] where k = kappa_ij + (pi/2) *
     * p_ij is precomputed by the caller.
     *
     * <p>
     * No chord variables, no constrained-face elimination — fixed faces are handled
     * at solve time by {@link AdaptiveSolver}, same convention as the MIP
     * constructor.
     *
     * @param faceCount           number of face variables (variableCount)
     * @param rowCount            number of interior-edge rows
     * @param rowFaceI            per-row first face index
     * @param rowFaceJ            per-row second face index
     * @param rowKappaPlusHalfPiP per-row precomputed kappa + (pi/2) * p
     */
    public NormalMatrix(int faceCount, int rowCount,
            int[] rowFaceI, int[] rowFaceJ, double[] rowKappaPlusHalfPiP) {
        variableCount = faceCount;
        diagonal = new double[variableCount];
        rightHandSide = new double[variableCount];

        int[] degree = new int[variableCount];
        for (int r = 0; r < rowCount; r++) {
            degree[rowFaceI[r]] += 1;
            degree[rowFaceJ[r]] += 1;
        }

        rowStart = new int[variableCount + 1];
        for (int i = 0; i < variableCount; i++) {
            rowStart[i + 1] = rowStart[i] + degree[i];
        }
        rowColumn = new int[rowStart[variableCount]];
        rowValue = new double[rowStart[variableCount]];
        int[] cursor = rowStart.clone();

        for (int r = 0; r < rowCount; r++) {
            int fi = rowFaceI[r];
            int fj = rowFaceJ[r];
            double k = rowKappaPlusHalfPiP[r];

            diagonal[fi] += 1.0;
            diagonal[fj] += 1.0;

            addOffDiagonal(cursor, fi, fj, -1.0);
            addOffDiagonal(cursor, fj, fi, -1.0);

            rightHandSide[fi] -= k;
            rightHandSide[fj] += k;
        }
    }

    /**
     * Accumulator constructor. Builds the CSR layout from an already-assembled SPD
     * system expressed as a diagonal vector plus an upper-triangle map (key =
     * ((long) row << 32) | (col & 0xFFFFFFFFL) with row ≤ col) plus an rhs. The
     * off-diagonal map's values are mirrored to both (row, col) and (col, row) to
     * satisfy {@code NormalMatrix}'s full-symmetric storage convention.
     *
     * <p>
     * Used by callers that build their matrix by accumulating outer-product
     * contributions (e.g. seamless parameterization's per-face and per-cut-edge
     * penalty terms) rather than by walking a fixed row-row structure.
     *
     * @param diag  diagonal values, length {@code variableCount}
     * @param upper off-diagonals; key packs (row, col) with row &lt; col, value is
     *              the matrix entry; mirrored to both triangles on construction
     * @param rhs   right-hand-side, length {@code variableCount}
     */
    public NormalMatrix(double[] diag, Map<Long, Double> upper, double[] rhs) {
        this.variableCount = diag.length;
        this.diagonal = diag.clone();
        this.rightHandSide = rhs.clone();

        int[] degree = new int[variableCount];
        for (long key : upper.keySet()) {
            int row = (int) (key >>> KEY_ROW_SHIFT);
            int col = (int) (key & KEY_COL_MASK);
            degree[row]++;
            degree[col]++;
        }

        rowStart = new int[variableCount + 1];
        for (int i = 0; i < variableCount; i++) {
            rowStart[i + 1] = rowStart[i] + degree[i];
        }
        rowColumn = new int[rowStart[variableCount]];
        rowValue = new double[rowStart[variableCount]];
        int[] cursor = rowStart.clone();

        for (Map.Entry<Long, Double> e : upper.entrySet()) {
            long key = e.getKey();
            int row = (int) (key >>> KEY_ROW_SHIFT);
            int col = (int) (key & KEY_COL_MASK);
            double value = e.getValue();
            addOffDiagonal(cursor, row, col, value);
            addOffDiagonal(cursor, col, row, value);
        }
    }

    /**
     * Parallel-array accumulator constructor. Builds the CSR layout from an
     * already-assembled SPD system in flat-array form: diagonal, sorted
     * upper-triangle packed keys plus a value array indexed by the same slot, and
     * an RHS vector. Equivalent to the {@code Map<Long,
     * Double>} constructor but the caller has already paid the deduplication cost —
     * used by the cached seamless assembly playback path where the same
     * upper-triangle structure is reused across many solves.
     *
     * @param diag        diagonal values, length {@code variableCount}
     * @param upperKeys   sorted (row, col) packed-long keys; slot index = array
     *                    index
     * @param upperValues values matching {@code upperKeys}, same length
     * @param rhs         right-hand-side, length {@code variableCount}
     */
    public NormalMatrix(double[] diag, long[] upperKeys, double[] upperValues, double[] rhs) {
        this.variableCount = diag.length;
        this.diagonal = diag.clone();
        this.rightHandSide = rhs.clone();

        int[] degree = new int[variableCount];
        for (long key : upperKeys) {
            int row = (int) (key >>> KEY_ROW_SHIFT);
            int col = (int) (key & KEY_COL_MASK);
            degree[row]++;
            degree[col]++;
        }

        rowStart = new int[variableCount + 1];
        for (int i = 0; i < variableCount; i++) {
            rowStart[i + 1] = rowStart[i] + degree[i];
        }
        rowColumn = new int[rowStart[variableCount]];
        rowValue = new double[rowStart[variableCount]];
        int[] cursor = rowStart.clone();

        for (int slot = 0; slot < upperKeys.length; slot++) {
            long key = upperKeys[slot];
            int row = (int) (key >>> KEY_ROW_SHIFT);
            int col = (int) (key & KEY_COL_MASK);
            double value = upperValues[slot];
            addOffDiagonal(cursor, row, col, value);
            addOffDiagonal(cursor, col, row, value);
        }
    }

    /**
     * Append one off-diagonal entry into the row's Compressed Sparse Row slot,
     * advancing the row's write cursor in {@code cursor}.
     *
     * @param cursor per-row write cursor (mutated)
     * @param row    row index of the new entry
     * @param col    column index of the new entry
     * @param value  coefficient
     */
    public void addOffDiagonal(int[] cursor, int row, int col, double value) {
        int i = cursor[row]++;
        rowColumn[i] = col;
        rowValue[i] = value;
    }

    /**
     * Get the number of variables.
     * 
     * @return number of variables
     */
    public int size() {
        return variableCount;
    }

    /**
     * Get the diagonal entry of the row.
     * 
     * @param row row index
     * @return diagonal entry
     */
    public double diag(int row) {
        return diagonal[row];
    }

    /**
     * Get the start index of the row.
     * 
     * @param row row index
     * @return start index
     */
    public int rowStart(int row) {
        return rowStart[row];
    }

    /**
     * Get the end index of the row.
     * 
     * @param row row index
     * @return end index
     */
    public int rowEnd(int row) {
        return rowStart[row + 1];
    }

    /**
     * Get the column index of the entry at the given cursor.
     * 
     * @param cursor cursor index
     * @return column index
     */
    public int column(int cursor) {
        return rowColumn[cursor];
    }

    /**
     * Get the column index of the entry at the given cursor.
     * 
     * @param cursor cursor index
     * @return column index
     */
    public double value(int cursor) {
        return rowValue[cursor];
    }

    /**
     * Compute the dot product of the row with the vector x.
     * 
     * @param row row index
     * @param x   vector
     * @return dot product
     */
    public double rowDot(int row, double[] x) {
        double sum = diagonal[row] * x[row];
        for (int cursor = rowStart[row]; cursor < rowStart[row + 1]; cursor++) {
            sum += rowValue[cursor] * x[rowColumn[cursor]];
        }
        return sum;
    }

    /**
     * Permuted-upper-triangle Compressed Sparse Column: column-pointer / row-index
     * / value arrays for the free-variable submatrix in the supplied permutation,
     * packing only the upper triangle (col ≥ row in the permuted order).
     * 
     * @param freeCount size of the compact problem
     * @param fixed     full-size fixed-variable mask
     * @param compactOf full-index → compact-index, or -1 if fixed
     * @param fullOf    compact-index → full-index, length freeCount
     * @param perm      permuted-index → old compact-index, length freeCount
     * @param invPerm   old compact-index → permuted-index, length freeCount
     * @return three arrays {@code [colPtr, rowIdx, values]} where
     *         {@code colPtr.length == freeCount + 1},
     *         {@code rowIdx.length == values.length == nnz}
     */
    public CompressedSparseColumnArrays toPermutedUpperCompressedSparseColumn(int freeCount, boolean[] fixed,
            int[] compactOf, int[] fullOf, int[] perm, int[] invPerm) {
        int[] colPtr = new int[freeCount + 1];

        for (int pCol = 0; pCol < freeCount; pCol++) {
            colPtr[pCol + 1] = 1;
        }
        for (int oldFull = 0; oldFull < variableCount; oldFull++) {
            if (fixed[oldFull])
                continue;
            int pRow = invPerm[compactOf[oldFull]];
            for (int c = rowStart[oldFull]; c < rowStart[oldFull + 1]; c++) {
                int colFull = rowColumn[c];
                if (fixed[colFull])
                    continue;
                int pCol = invPerm[compactOf[colFull]];
                if (pRow < pCol) {
                    colPtr[pCol + 1]++;
                }
            }
        }

        for (int i = 0; i < freeCount; i++) {
            colPtr[i + 1] += colPtr[i];
        }
        int nnz = colPtr[freeCount];
        int[] rowIdx = new int[nnz];
        double[] values = new double[nnz];

        int[] cursor = colPtr.clone();
        for (int pCol = 0; pCol < freeCount; pCol++) {
            int oldFull = fullOf[perm[pCol]];
            int slot = cursor[pCol]++;
            rowIdx[slot] = pCol;
            values[slot] = diagonal[oldFull];
        }
        for (int oldFull = 0; oldFull < variableCount; oldFull++) {
            if (fixed[oldFull])
                continue;
            int pRow = invPerm[compactOf[oldFull]];
            for (int c = rowStart[oldFull]; c < rowStart[oldFull + 1]; c++) {
                int colFull = rowColumn[c];
                if (fixed[colFull])
                    continue;
                int pCol = invPerm[compactOf[colFull]];
                if (pRow < pCol) {
                    int slot = cursor[pCol]++;
                    rowIdx[slot] = pRow;
                    values[slot] = rowValue[c];
                }
            }
        }

        return new CompressedSparseColumnArrays(colPtr, rowIdx, values);
    }

    public record CompressedSparseColumnArrays(int[] colPtr, int[] rowIdx, double[] values) {
    }
}