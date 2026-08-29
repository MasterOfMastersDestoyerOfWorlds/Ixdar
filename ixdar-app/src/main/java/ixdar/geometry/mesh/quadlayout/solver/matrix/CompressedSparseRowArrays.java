package ixdar.geometry.mesh.quadlayout.solver.matrix;

/**
 * Raw Compressed Sparse Row arrays for one triangle of a symmetric matrix:
 * the row-major mirror of
 * {@link NormalMatrix.CompressedSparseColumnArrays}, produced by
 * {@link NormalMatrix#toPermutedUpperCompressedSparseRow} for backends (like
 * PARDISO) whose SPD input format is the upper triangle in CSR with
 * ascending column indices per row.
 */
public final class CompressedSparseRowArrays {

    /** Row pointers, length {@code dimension + 1}. */
    public final int[] rowPtr;

    /** Column indices, ascending within each row, length {@code nnz}. */
    public final int[] colIdx;

    /** Non-zero values matching {@link #colIdx}, length {@code nnz}. */
    public final double[] values;

    /**
     * Wrap the three CSR arrays; no copies are made.
     *
     * @param rowPtr row pointers, length {@code dimension + 1}
     * @param colIdx column indices, ascending within each row
     * @param values non-zero values matching {@code colIdx}
     */
    public CompressedSparseRowArrays(int[] rowPtr, int[] colIdx, double[] values) {
        this.rowPtr = rowPtr;
        this.colIdx = colIdx;
        this.values = values;
    }
}
