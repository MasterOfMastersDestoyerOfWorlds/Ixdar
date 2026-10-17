package ixdar.geometry.mesh.data.representation;

import java.util.Arrays;
import java.util.Objects;

/**
 * One list of ids per row: a half-edge mesh's per-vertex and per-face adjacency. A bulk build
 * packs every row into one shared array; a row moves into its own {@link IntIdList} the first time
 * it is edited, so editing stays cheap and a bulk build allocates no per-row objects.
 */
public final class IntIdTable {

    /**
     * Offsets of the packed rows into {@link #packedValues}, one more than there are packed rows:
     * row {@code r} ends where {@code r + 1} starts.
     */
    public int[] packedStart;

    /** Every packed row's ids, end to end. */
    public int[] packedValues;

    /** Rows edited since the pack, or created after it; null where the row is still packed. */
    public IntIdList[] editedRows;

    /** Capacity a row gets when it first moves into its own list. */
    public final int rowCapacity;

    /**
     * Creates an empty table.
     *
     * @param rowCapacity capacity a row's own list starts with
     */
    public IntIdTable(int rowCapacity) {
        this.rowCapacity = rowCapacity;
        this.packedStart = new int[1];
        this.packedValues = new int[0];
        this.editedRows = new IntIdList[0];
    }

    /**
     * Replaces every row with packed rows; the arrays are kept, not copied.
     *
     * @param start  offsets into {@code values}, one more than there are rows
     * @param values the rows' ids, end to end
     */
    public void pack(int[] start, int[] values) {
        packedStart = start;
        packedValues = values;
        editedRows = new IntIdList[start.length - 1];
    }

    /**
     * The number of ids in a row; a row never written holds none.
     *
     * @param row row to measure
     * @return the row's length
     */
    public int size(int row) {
        IntIdList edited = row < editedRows.length ? editedRows[row] : null;
        if (edited != null) {
            return edited.size();
        }
        return row < packedStart.length - 1 ? packedStart[row + 1] - packedStart[row] : 0;
    }

    /**
     * Whether a row holds no ids.
     *
     * @param row row to test
     * @return true when the row is empty
     */
    public boolean isEmpty(int row) {
        return size(row) == 0;
    }

    /**
     * The id at a position of a row.
     *
     * @param row   row to read
     * @param index zero-based position within the row
     * @throws IndexOutOfBoundsException when {@code index} is outside the row
     * @return the id at that position
     */
    public int get(int row, int index) {
        IntIdList edited = row < editedRows.length ? editedRows[row] : null;
        if (edited != null) {
            return edited.get(index);
        }
        int start = packedStart[row];
        Objects.checkIndex(index, packedStart[row + 1] - start);
        return packedValues[start + index];
    }

    /**
     * Appends an id to a row.
     *
     * @param row   row to extend
     * @param value id to append
     */
    public void add(int row, int value) {
        editableRow(row).add(value);
    }

    /**
     * Appends an id to a row unless the row already holds it.
     *
     * @param row   row to extend
     * @param value id to append when absent
     */
    public void addUnique(int row, int value) {
        editableRow(row).addUnique(value);
    }

    /**
     * Removes the first occurrence of an id from a row, keeping the order of the rest.
     *
     * @param row   row to edit
     * @param value id to remove
     * @return true when the id was found and removed
     */
    public boolean removeValue(int row, int value) {
        return editableRow(row).removeValue(value);
    }

    /**
     * Empties a row, creating it when it does not exist yet.
     *
     * @param row row to empty
     */
    public void clear(int row) {
        IntIdList edited = row < editedRows.length ? editedRows[row] : null;
        if (edited != null) {
            edited.clear();
            return;
        }
        ensureRowCapacity(row + 1);
        editedRows[row] = new IntIdList(rowCapacity);
    }

    /**
     * The row's own list, moving a packed row into one first.
     *
     * @param row row about to be edited
     * @return the list holding the row from now on
     */
    private IntIdList editableRow(int row) {
        IntIdList edited = row < editedRows.length ? editedRows[row] : null;
        if (edited != null) {
            return edited;
        }
        ensureRowCapacity(row + 1);
        int length = row < packedStart.length - 1 ? packedStart[row + 1] - packedStart[row] : 0;
        edited = new IntIdList(Math.max(rowCapacity, length + 1));
        if (length > 0) {
            System.arraycopy(packedValues, packedStart[row], edited.values, 0, length);
            edited.size = length;
        }
        editedRows[row] = edited;
        return edited;
    }

    /**
     * Grows {@link #editedRows} so it can address a row count.
     *
     * @param requiredRowCount smallest row count the array must address
     */
    private void ensureRowCapacity(int requiredRowCount) {
        if (requiredRowCount <= editedRows.length) {
            return;
        }
        editedRows = Arrays.copyOf(editedRows,
                HalfEdgeMesh.nextCapacity(editedRows.length, requiredRowCount));
    }
}
