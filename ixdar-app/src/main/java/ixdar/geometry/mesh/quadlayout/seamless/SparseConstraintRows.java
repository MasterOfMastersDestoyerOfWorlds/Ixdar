package ixdar.geometry.mesh.quadlayout.seamless;

import java.util.Arrays;

/**
 * Append-only sparse rows in one flat pair of arrays, twelve bytes an entry.
 *
 * <p>Write one row at a time: {@link #beginRow()}, any number of {@link #add}, then
 * {@link #endRow()}. Entries keep the order they were added, and adding a DOF the open row already
 * holds sums into it.
 */
public final class SparseConstraintRows {

    /** Initial entry capacity, grown by doubling. */
    private static final int INITIAL_ENTRY_CAPACITY = 16;

    /** Number of finished rows. */
    public int rowCount;

    /** Entry offset of each row, plus the total in {@code rowStart[rowCount]}. */
    public int[] rowStart;

    /** Raw-DOF index of each entry. */
    public int[] entryDof;

    /** Coefficient of each entry, parallel to {@link #entryDof}. */
    public double[] entryCoef;

    /** Number of entries written so far, the write cursor into {@link #entryDof}. */
    public int entryCount;

    /**
     * Empty rows sized for an expected row count; both arrays grow by doubling.
     *
     * @param expectedRowCount rows the caller expects to append
     */
    public SparseConstraintRows(int expectedRowCount) {
        this.rowStart = new int[Math.max(2, expectedRowCount + 1)];
        this.entryDof = new int[INITIAL_ENTRY_CAPACITY];
        this.entryCoef = new double[INITIAL_ENTRY_CAPACITY];
    }

    /** Opens a new row; entries added from now until {@link #endRow()} belong to it. */
    public void beginRow() {
        if (rowCount + 1 >= rowStart.length) {
            rowStart = Arrays.copyOf(rowStart, rowStart.length * 2);
        }
        rowStart[rowCount] = entryCount;
    }

    /**
     * Adds a coefficient to the open row, summing into the DOF's existing entry when the row
     * already holds one. Rows carry a handful of entries, so the duplicate check is a scan.
     *
     * @param dof  raw-DOF index
     * @param coef coefficient to add
     */
    public void add(int dof, double coef) {
        for (int entry = rowStart[rowCount]; entry < entryCount; entry++) {
            if (entryDof[entry] == dof) {
                entryCoef[entry] += coef;
                return;
            }
        }
        if (entryCount == entryDof.length) {
            entryDof = Arrays.copyOf(entryDof, entryCount * 2);
            entryCoef = Arrays.copyOf(entryCoef, entryCount * 2);
        }
        entryDof[entryCount] = dof;
        entryCoef[entryCount] = coef;
        entryCount++;
    }

    /** Closes the open row, making it visible to {@link #rowCount} and {@link #rowStart}. */
    public void endRow() {
        rowCount++;
        rowStart[rowCount] = entryCount;
    }
}
