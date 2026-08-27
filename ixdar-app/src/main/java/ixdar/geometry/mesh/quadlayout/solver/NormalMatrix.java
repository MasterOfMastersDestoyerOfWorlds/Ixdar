package ixdar.geometry.mesh.quadlayout.solver;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Map;

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
     * Two {@link #rowValue} positions per upper slot, enabling
     * {@link #refreshValues}; only the sorted-keys constructor fills it.
     */
    public int[] rowValuePositionBySlot;

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
     * Theta-only Laplacian constructor for the local-search post-process: one
     * variable per face, one row per interior edge. Fixed faces are not eliminated
     * here; {@link AdaptiveSolver} handles them at solve time.
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
     * Accumulator constructor: builds the CSR layout from a diagonal vector, an
     * upper-triangle map keyed by {@code (row << 32) | col} with row ≤ col, and an
     * rhs. Off-diagonal values are mirrored into both triangles to satisfy this
     * class's full-symmetric storage convention.
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
     * an RHS vector. Equivalent to the {@code Map<Long, Double>} constructor, except
     * that the caller must have already deduplicated and sorted the keys.
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

        rowValuePositionBySlot = new int[upperKeys.length * 2];
        for (int slot = 0; slot < upperKeys.length; slot++) {
            long key = upperKeys[slot];
            int row = (int) (key >>> KEY_ROW_SHIFT);
            int col = (int) (key & KEY_COL_MASK);
            double value = upperValues[slot];
            rowValuePositionBySlot[slot * 2] = cursor[row];
            addOffDiagonal(cursor, row, col, value);
            rowValuePositionBySlot[slot * 2 + 1] = cursor[col];
            addOffDiagonal(cursor, col, row, value);
        }
    }

    /** Direct CSR constructor — assigns prebuilt arrays without reassembling. */
    private NormalMatrix(int variableCount, int[] rowStart, int[] rowColumn,
            double[] rowValue, double[] diagonal, double[] rightHandSide) {
        this.variableCount = variableCount;
        this.rowStart = rowStart;
        this.rowColumn = rowColumn;
        this.rowValue = rowValue;
        this.diagonal = diagonal;
        this.rightHandSide = rightHandSide;
    }

    /**
     * Permuted constructor. Builds the CSR layout from a given matrix and a
     * permutation.
     *
     * @param matrix             the matrix to permute
     * @param permutation        the permutation
     * @param inversePermutation the inverse permutation
     */
    public NormalMatrix(NormalMatrix matrix, int[] permutation, int[] inversePermutation) {
        variableCount = matrix.variableCount;

        // New slot k takes old variable permutation[k].
        diagonal = new double[variableCount];
        rightHandSide = new double[variableCount];
        for (int newIndex = 0; newIndex < variableCount; newIndex++) {
            int oldIndex = permutation[newIndex];
            diagonal[newIndex] = matrix.diagonal[oldIndex];
            rightHandSide[newIndex] = matrix.rightHandSide[oldIndex];
        }

        // Each new row inherits the entry count of the old row it came from.
        rowStart = new int[variableCount + 1];
        for (int oldRow = 0; oldRow < variableCount; oldRow++) {
            int newRow = inversePermutation[oldRow];
            rowStart[newRow + 1] = matrix.rowStart[oldRow + 1] - matrix.rowStart[oldRow];
        }
        for (int row = 0; row < variableCount; row++) {
            rowStart[row + 1] += rowStart[row];
        }

        // Copy each entry into its permuted row, remapping the column too.
        rowColumn = new int[rowStart[variableCount]];
        rowValue = new double[rowStart[variableCount]];
        int[] cursor = rowStart.clone();
        for (int oldRow = 0; oldRow < variableCount; oldRow++) {
            int newRow = inversePermutation[oldRow];
            for (int entry = matrix.rowStart[oldRow]; entry < matrix.rowStart[oldRow + 1]; entry++) {
                int slot = cursor[newRow]++;
                rowColumn[slot] = inversePermutation[matrix.rowColumn[entry]];
                rowValue[slot] = matrix.rowValue[entry];
            }
        }
    }

    /**
     * Overwrites the diagonal, off-diagonal, and right-hand-side values in
     * place, keeping the sparsity structure; slot order must match construction.
     *
     * @param diag        new diagonal values, length {@code variableCount}
     * @param upperValues new off-diagonal values in the construction slot order
     * @param rhs         new right-hand side, length {@code variableCount}
     * @throws IllegalStateException when the matrix was not built from sorted
     *                               keys
     */
    public void refreshValues(double[] diag, double[] upperValues, double[] rhs) {
        if (rowValuePositionBySlot == null) {
            throw new IllegalStateException("only a sorted-keys matrix can refresh values");
        }
        System.arraycopy(diag, 0, diagonal, 0, variableCount);
        System.arraycopy(rhs, 0, rightHandSide, 0, variableCount);
        for (int slot = 0; slot < upperValues.length; slot++) {
            double value = upperValues[slot];
            rowValue[rowValuePositionBySlot[slot * 2]] = value;
            rowValue[rowValuePositionBySlot[slot * 2 + 1]] = value;
        }
    }

    /**
     * Return {@code this - other}. General: result pattern is the union of both.
     *
     * @param other matrix to subtract; must have the same dimension
     * @throws IllegalArgumentException if the dimensions differ
     * @return a new matrix holding {@code this - other} on the union pattern
     */
    public NormalMatrix subtract(NormalMatrix other) {
        if (variableCount != other.variableCount) {
            throw new IllegalArgumentException("dimension mismatch");
        }
        int n = variableCount;

        double[] newDiagonal = new double[n];
        for (int i = 0; i < n; i++) {
            newDiagonal[i] = diagonal[i] - other.diagonal[i];
        }

        // --- symbolic pass: union column count per row ---
        int[] degree = new int[n];
        int[] mark = new int[n];
        Arrays.fill(mark, -1);
        for (int i = 0; i < n; i++) {
            int count = 0;
            for (int c = rowStart[i]; c < rowStart[i + 1]; c++) {
                int col = rowColumn[c];
                if (mark[col] != i) {
                    mark[col] = i;
                    count++;
                }
            }
            for (int c = other.rowStart[i]; c < other.rowStart[i + 1]; c++) {
                int col = other.rowColumn[c];
                if (mark[col] != i) {
                    mark[col] = i;
                    count++;
                }
            }
            degree[i] = count;
        }

        int[] newRowStart = new int[n + 1];
        for (int i = 0; i < n; i++) {
            newRowStart[i + 1] = newRowStart[i] + degree[i];
        }
        int[] newRowColumn = new int[newRowStart[n]];
        double[] newRowValue = new double[newRowStart[n]];

        // --- numeric pass: accumulate this - other into the union slots ---
        int[] cursor = newRowStart.clone();
        int[] slotOfCol = new int[n];
        int[] seen = new int[n];
        Arrays.fill(seen, -1);
        for (int i = 0; i < n; i++) {
            for (int c = rowStart[i]; c < rowStart[i + 1]; c++) {
                int col = rowColumn[c];
                if (seen[col] != i) {
                    seen[col] = i;
                    int slot = cursor[i]++;
                    slotOfCol[col] = slot;
                    newRowColumn[slot] = col;
                    newRowValue[slot] = rowValue[c];
                } else {
                    newRowValue[slotOfCol[col]] += rowValue[c];
                }
            }
            for (int c = other.rowStart[i]; c < other.rowStart[i + 1]; c++) {
                int col = other.rowColumn[c];
                if (seen[col] != i) {
                    seen[col] = i;
                    int slot = cursor[i]++;
                    slotOfCol[col] = slot;
                    newRowColumn[slot] = col;
                    newRowValue[slot] = -other.rowValue[c];
                } else {
                    newRowValue[slotOfCol[col]] -= other.rowValue[c];
                }
            }
        }
        return new NormalMatrix(n, newRowStart, newRowColumn, newRowValue, newDiagonal, rightHandSide);
    }

    /**
     * Return {@code this * s}. Shares structure; scales values and diagonal.
     *
     * @param s scalar factor applied to every entry
     * @return a new matrix sharing this one's sparsity arrays with scaled values
     */
    public NormalMatrix scale(double s) {
        double[] newDiagonal = new double[variableCount];
        for (int i = 0; i < variableCount; i++) {
            newDiagonal[i] = diagonal[i] * s;
        }
        double[] newValue = new double[rowValue.length];
        for (int c = 0; c < rowValue.length; c++) {
            newValue[c] = rowValue[c] * s;
        }
        return new NormalMatrix(variableCount, rowStart, rowColumn, newValue, newDiagonal, rightHandSide);
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

    /**
     * Permuted-upper-triangle Compressed Sparse Row: row-pointer / column-index
     * / value arrays for the free-variable submatrix in the supplied
     * permutation, packing only the upper triangle (col ≥ row in the permuted
     * order) with <em>ascending column indices within each row</em>, the SPD input
     * format PARDISO requires.
     *
     * @param freeCount size of the compact problem
     * @param fixed     full-size fixed-variable mask
     * @param compactOf full-index → compact-index, or -1 if fixed
     * @param fullOf    compact-index → full-index, length freeCount
     * @param perm      permuted-index → old compact-index, length freeCount
     * @param invPerm   old compact-index → permuted-index, length freeCount
     * @return CSR arrays where {@code rowPtr.length == freeCount + 1} and
     *         {@code colIdx.length == values.length == nnz}
     */
    public CompressedSparseRowArrays toPermutedUpperCompressedSparseRow(int freeCount, boolean[] fixed,
            int[] compactOf, int[] fullOf, int[] perm, int[] invPerm) {
        int[] rowPtr = new int[freeCount + 1];

        for (int pRow = 0; pRow < freeCount; pRow++) {
            rowPtr[pRow + 1] = 1;
        }
        for (int oldFull = 0; oldFull < variableCount; oldFull++) {
            if (fixed[oldFull])
                continue;
            int pCol = invPerm[compactOf[oldFull]];
            for (int c = rowStart[oldFull]; c < rowStart[oldFull + 1]; c++) {
                int otherFull = rowColumn[c];
                if (fixed[otherFull])
                    continue;
                int pRow = invPerm[compactOf[otherFull]];
                if (pRow < pCol) {
                    rowPtr[pRow + 1]++;
                }
            }
        }

        for (int i = 0; i < freeCount; i++) {
            rowPtr[i + 1] += rowPtr[i];
        }
        int nnz = rowPtr[freeCount];
        int[] colIdx = new int[nnz];
        double[] values = new double[nnz];

        int[] cursor = rowPtr.clone();
        for (int pCol = 0; pCol < freeCount; pCol++) {
            int oldFullCol = fullOf[perm[pCol]];
            int diagonalSlot = cursor[pCol]++;
            colIdx[diagonalSlot] = pCol;
            values[diagonalSlot] = diagonal[oldFullCol];
            for (int c = rowStart[oldFullCol]; c < rowStart[oldFullCol + 1]; c++) {
                int otherFull = rowColumn[c];
                if (fixed[otherFull])
                    continue;
                int pRow = invPerm[compactOf[otherFull]];
                if (pRow < pCol) {
                    int slot = cursor[pRow]++;
                    colIdx[slot] = pCol;
                    values[slot] = rowValue[c];
                }
            }
        }

        return new CompressedSparseRowArrays(rowPtr, colIdx, values);
    }

    /**
     * Dump {@code matrix} to {@code path} in a simple token format.
     *
     * <pre>
     * NORMALMATRIX v1
     * variableCount &lt;n&gt;
     * rowStart &lt;len&gt; v0 v1 ...
     * rowColumn &lt;len&gt; v0 v1 ...
     * rowValue &lt;len&gt; v0 v1 ...
     * diagonal &lt;len&gt; v0 v1 ...
     * rightHandSide &lt;len&gt; v0 v1 ...
     * </pre>
     *
     * @param matrix the matrix to dump
     * @param path   destination file path
     * @throws RuntimeException if writing the file fails
     */
    public static void dump(NormalMatrix matrix, String path) {
        try (PrintWriter w = new PrintWriter(new BufferedWriter(new FileWriter(path)))) {
            w.println("NORMALMATRIX v1");
            w.println("variableCount " + matrix.variableCount);
            writeInts(w, "rowStart", matrix.rowStart);
            writeInts(w, "rowColumn", matrix.rowColumn);
            writeDoubles(w, "rowValue", matrix.rowValue);
            writeDoubles(w, "diagonal", matrix.diagonal);
            writeDoubles(w, "rightHandSide", matrix.rightHandSide);
            w.flush();
            System.err.println("[NormalMatrixIO] dumped " + matrix.variableCount
                    + "x" + matrix.variableCount + " matrix ("
                    + matrix.rowColumn.length + " off-diagonal entries) to " + path);
        } catch (IOException e) {
            throw new RuntimeException("NormalMatrixIO.dump failed for " + path, e);
        }
    }

    private static void writeInts(PrintWriter w, String name, int[] a) {
        w.print(name);
        w.print(' ');
        w.print(a.length);
        for (int v : a) {
            w.print(' ');
            w.print(v);
        }
        w.println();
    }

    private static void writeDoubles(PrintWriter w, String name, double[] a) {
        w.print(name);
        w.print(' ');
        w.print(a.length);
        for (double v : a) {
            w.print(' ');
            w.print(Double.toString(v)); // round-trippable in Java
        }
        w.println();
    }

    /**
     * The quadratic energy 0.5 x'Ax - b'x of this system at x, the objective a
     * linear solve minimizes.
     *
     * @param x candidate solution, length {@link #variableCount}
     * @return the quadratic energy at x
     */
    public double quadraticEnergy(double[] x) {
        double energy = 0.0;
        for (int row = 0; row < variableCount; row++) {
            energy += x[row] * (HALF * rowDot(row, x) - rightHandSide[row]);
        }
        return energy;
    }

    public record CompressedSparseColumnArrays(int[] colPtr, int[] rowIdx, double[] values) {
    }
}