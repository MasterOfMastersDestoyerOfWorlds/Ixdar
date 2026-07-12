package ixdar.geometry.mesh.quadlayout.seamless;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Sparse Gauss-Jordan elimination of the leftover seam-constraint rows
 * {@code L · x = 0} (BZK09 §5 variable elimination), producing one
 * substitution rule per pivoted raw DOF.
 *
 * <p>
 * Pivots are chosen per row instead of by a global magnitude search: every
 * initially-built coefficient is an exact 0/±1 (integer cosine/sine of the
 * 90°k cut rotations plus unit s/t entries), so any entry within
 * {@link #PIVOT_MAGNITUDE_GUARD} of the row's maximum is a numerically sound
 * pivot, and among those the entry whose DOF appears in the fewest rows
 * (approximate Markowitz) minimises fill. Rows live in primitive parallel
 * arrays with a DOF → referencing-rows index, so eliminating a pivot touches
 * only the rows that actually contain it. This replaces a global
 * O(rows² · entries) pivot scan over boxed hash maps that cost ~6s of the
 * ~20s seamless build on a 200k-triangle mesh; the same elimination now runs
 * in milliseconds.
 *
 * <p>
 * Substitution rules may reference DOFs pivoted by <em>later</em> rows —
 * consumers resolve chains recursively, exactly as with the previous
 * implementation — but never earlier pivots, since each pivot is eliminated
 * from every live row before the next row is processed (that forward-only
 * property is what makes the recursive expansion terminate).
 *
 * <p>
 * All work happens in the constructor; results land in {@link #pivotDofs} /
 * {@link #pivotCoefs}, indexed by raw DOF (null for non-pivots).
 */
public final class LeftoverConstraintEliminator {

    /**
     * A pivot candidate must have magnitude at least this fraction of its
     * row's maximum. With exact ±1 initial coefficients this accepts every
     * unit entry while refusing entries that elimination has shrunk.
     */
    private static final double PIVOT_MAGNITUDE_GUARD = 0.5;


    /** Tolerance for the leftover-row Gauss-Jordan pivot magnitude. */
    private static final double LEFTOVER_REDUCE_TOLERANCE = 1.0e-10;

    /** Per raw DOF: the non-pivot DOFs its substitution expands into; null for non-pivots. */
    public final int[][] pivotDofs;
    /** Coefficients matching {@link #pivotDofs}. */
    public final double[][] pivotCoefs;
    private final int[][] rowDofs;
    private final double[][] rowCoefs;
    private final int[] rowSize;
    /** Per DOF: row indices referencing it; may contain stale entries after removals. */
    private final int[][] dofRows;
    private final int[] dofRowCount;

    /**
     * Convert the built constraint rows to primitive sparse vectors and run
     * the elimination.
     *
     * @param builtRows   constraint rows as DOF → coefficient maps, as
     *                    assembled by
     *                    {@code SeamlessDofSystem.reduceLeftoverConstraints}
     * @param rawDofCount raw-DOF space size (row entries index into it)
     */
    public LeftoverConstraintEliminator(ArrayList<HashMap<Integer, Double>> builtRows,
            int rawDofCount) {
        this.pivotDofs = new int[rawDofCount][];
        this.pivotCoefs = new double[rawDofCount][];
        int totalRows = builtRows.size();
        this.rowDofs = new int[totalRows][];
        this.rowCoefs = new double[totalRows][];
        this.rowSize = new int[totalRows];
        this.dofRows = new int[rawDofCount][];
        this.dofRowCount = new int[rawDofCount];

        for (int rowIdx = 0; rowIdx < totalRows; rowIdx++) {
            HashMap<Integer, Double> built = builtRows.get(rowIdx);
            int[] dofs = new int[Math.max(1, built.size())];
            double[] coefs = new double[dofs.length];
            int size = 0;
            for (Map.Entry<Integer, Double> entry : built.entrySet()) {
                if (Math.abs(entry.getValue()) < LEFTOVER_REDUCE_TOLERANCE) {
                    continue;
                }
                dofs[size] = entry.getKey();
                coefs[size] = entry.getValue();
                size++;
            }
            rowDofs[rowIdx] = dofs;
            rowCoefs[rowIdx] = coefs;
            rowSize[rowIdx] = size;
            for (int i = 0; i < size; i++) {
                referenceRow(dofs[i], rowIdx);
            }
        }

        for (int rowIdx = 0; rowIdx < totalRows; rowIdx++) {
            pivotRow(rowIdx);
        }
    }

    /**
     * Pivot one row: pick its pivot entry, record the substitution rule, and
     * eliminate the pivot DOF from every live row referencing it. Rows whose
     * entries have all shrunk below tolerance are dependent and are skipped.
     *
     * @param rowIdx index of the row to pivot
     */
    private void pivotRow(int rowIdx) {
        int size = rowSize[rowIdx];
        int[] dofs = rowDofs[rowIdx];
        double[] coefs = rowCoefs[rowIdx];
        double rowMax = 0.0;
        for (int i = 0; i < size; i++) {
            rowMax = Math.max(rowMax, Math.abs(coefs[i]));
        }
        if (rowMax < LEFTOVER_REDUCE_TOLERANCE) {
            rowSize[rowIdx] = 0;
            return;
        }
        double magnitudeFloor = PIVOT_MAGNITUDE_GUARD * rowMax;
        int pivotSlot = -1;
        int pivotOccupancy = Integer.MAX_VALUE;
        double pivotMagnitude = 0.0;
        for (int i = 0; i < size; i++) {
            double magnitude = Math.abs(coefs[i]);
            if (magnitude < magnitudeFloor) {
                continue;
            }
            int occupancy = dofRowCount[dofs[i]];
            if (occupancy < pivotOccupancy
                    || (occupancy == pivotOccupancy && magnitude > pivotMagnitude)) {
                pivotOccupancy = occupancy;
                pivotMagnitude = magnitude;
                pivotSlot = i;
            }
        }

        int pivotDof = dofs[pivotSlot];
        double pivotCoef = coefs[pivotSlot];
        int[] substitutionDofs = new int[size - 1];
        double[] substitutionCoefs = new double[size - 1];
        int out = 0;
        for (int i = 0; i < size; i++) {
            if (i == pivotSlot) {
                continue;
            }
            substitutionDofs[out] = dofs[i];
            substitutionCoefs[out] = -coefs[i] / pivotCoef;
            out++;
        }
        pivotDofs[pivotDof] = substitutionDofs;
        pivotCoefs[pivotDof] = substitutionCoefs;
        rowSize[rowIdx] = 0;

        int referencingCount = dofRowCount[pivotDof];
        int[] referencing = dofRows[pivotDof];
        for (int r = 0; r < referencingCount; r++) {
            int otherIdx = referencing[r];
            if (otherIdx != rowIdx && rowSize[otherIdx] > 0) {
                substituteInto(otherIdx, pivotDof, substitutionDofs, substitutionCoefs);
            }
        }
    }

    /**
     * Replace {@code pivotDof} inside one live row with its substitution:
     * remove the pivot entry and fold {@code factor · substitution} into the
     * row, appending fill entries (and their column-index references) as
     * needed and dropping entries that shrink below tolerance. A stale
     * column-index reference — the row no longer contains the pivot — is a
     * no-op.
     *
     * @param rowIdx            row to update
     * @param pivotDof          DOF being eliminated
     * @param substitutionDofs  DOFs of the pivot's substitution rule
     * @param substitutionCoefs coefficients matching {@code substitutionDofs}
     */
    private void substituteInto(int rowIdx, int pivotDof,
            int[] substitutionDofs, double[] substitutionCoefs) {
        int[] dofs = rowDofs[rowIdx];
        double[] coefs = rowCoefs[rowIdx];
        int size = rowSize[rowIdx];
        int pivotSlot = -1;
        for (int i = 0; i < size; i++) {
            if (dofs[i] == pivotDof) {
                pivotSlot = i;
                break;
            }
        }
        if (pivotSlot < 0) {
            return;
        }
        double factor = coefs[pivotSlot];
        size--;
        dofs[pivotSlot] = dofs[size];
        coefs[pivotSlot] = coefs[size];

        for (int s = 0; s < substitutionDofs.length; s++) {
            int dof = substitutionDofs[s];
            double delta = factor * substitutionCoefs[s];
            int slot = -1;
            for (int i = 0; i < size; i++) {
                if (dofs[i] == dof) {
                    slot = i;
                    break;
                }
            }
            if (slot >= 0) {
                double updated = coefs[slot] + delta;
                if (Math.abs(updated) < LEFTOVER_REDUCE_TOLERANCE) {
                    size--;
                    dofs[slot] = dofs[size];
                    coefs[slot] = coefs[size];
                } else {
                    coefs[slot] = updated;
                }
            } else if (Math.abs(delta) >= LEFTOVER_REDUCE_TOLERANCE) {
                if (size == dofs.length) {
                    dofs = Arrays.copyOf(dofs, size * 2);
                    coefs = Arrays.copyOf(coefs, size * 2);
                    rowDofs[rowIdx] = dofs;
                    rowCoefs[rowIdx] = coefs;
                }
                dofs[size] = dof;
                coefs[size] = delta;
                size++;
                referenceRow(dof, rowIdx);
            }
        }
        rowSize[rowIdx] = size;
    }

    /**
     * Record that {@code rowIdx} references {@code dof} in the column index,
     * growing the per-DOF list as needed. Duplicate references are tolerated
     * (they stale-skip during elimination).
     *
     * @param dof    DOF gaining a referencing row
     * @param rowIdx the referencing row
     */
    private void referenceRow(int dof, int rowIdx) {
        int[] list = dofRows[dof];
        int count = dofRowCount[dof];
        if (list == null) {
            list = new int[2];
            dofRows[dof] = list;
        } else if (count == list.length) {
            list = Arrays.copyOf(list, count * 2);
            dofRows[dof] = list;
        }
        list[count] = rowIdx;
        dofRowCount[dof] = count + 1;
    }
}
