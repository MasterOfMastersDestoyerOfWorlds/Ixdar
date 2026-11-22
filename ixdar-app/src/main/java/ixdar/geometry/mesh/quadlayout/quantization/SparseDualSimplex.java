package ixdar.geometry.mesh.quadlayout.quantization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Slots are the structural columns of A, then one slack per row. The eta file sits on the
// all-slack basis and is rebuilt every REFACTOR_INTERVAL pivots, so a state is a few arrays of the
// slot count rather than a dense tableau, and copies share the matrix and the eta prefix.
/**
 * Bounded revised dual simplex over {@code A x - s = 0}, its basis inverse a product-form eta
 * file, priced by dual steepest edge (Forrest and Goldfarb 1992).
 */
final class SparseDualSimplex {

    private static final double FEASIBILITY_TOLERANCE = 1e-9;

    private static final double PIVOT_TOLERANCE = 1e-9;

    private static final double DROP_TOLERANCE = 1e-12;

    private static final double MIN_WEIGHT = 1e-6;

    private static final int ITERATIONS_PER_DIMENSION = 50;

    private static final int REFACTOR_INTERVAL = 64;

    /** The constraint matrix and objective, shared by every copy of a solver. */
    static final class Matrix {

        final int rowTotal;

        final int structuralTotal;

        final int slotTotal;

        final int[] columnStart;

        final int[] columnRow;

        final double[] columnValue;

        final int[] rowStart;

        final int[] rowColumn;

        final double[] rowValue;

        final double[] cost;

        /**
         * Build both sparse orientations of {@code A} from its rows.
         *
         * @param rowColumns       ascending structural columns of each row
         * @param rowCoefficients  coefficient of each of those columns
         * @param structuralTotal  number of structural columns
         * @param structuralCost   objective weight per structural column, all non-negative
         */
        Matrix(int[][] rowColumns, int[][] rowCoefficients, int structuralTotal, double[] structuralCost) {
            this.rowTotal = rowColumns.length;
            this.structuralTotal = structuralTotal;
            this.slotTotal = structuralTotal + rowTotal;
            int nonzeros = 0;
            for (int[] columns : rowColumns) {
                nonzeros += columns.length;
            }
            rowStart = new int[rowTotal + 1];
            rowColumn = new int[nonzeros];
            rowValue = new double[nonzeros];
            int[] columnCount = new int[structuralTotal];
            for (int row = 0, next = 0; row < rowTotal; row++) {
                rowStart[row] = next;
                for (int position = 0; position < rowColumns[row].length; position++) {
                    rowColumn[next] = rowColumns[row][position];
                    rowValue[next++] = rowCoefficients[row][position];
                    columnCount[rowColumns[row][position]]++;
                }
                rowStart[row + 1] = next;
            }
            columnStart = new int[structuralTotal + 1];
            for (int column = 0; column < structuralTotal; column++) {
                columnStart[column + 1] = columnStart[column] + columnCount[column];
            }
            columnRow = new int[nonzeros];
            columnValue = new double[nonzeros];
            int[] fill = Arrays.copyOf(columnStart, structuralTotal);
            for (int row = 0; row < rowTotal; row++) {
                for (int entry = rowStart[row]; entry < rowStart[row + 1]; entry++) {
                    int column = rowColumn[entry];
                    columnRow[fill[column]] = row;
                    columnValue[fill[column]++] = rowValue[entry];
                }
            }
            cost = new double[slotTotal];
            System.arraycopy(structuralCost, 0, cost, 0, structuralTotal);
        }

        /**
         * Scatter column {@code slot} of {@code [A, -I]}, scaled, into a dense row-indexed vector.
         *
         * @param slot   structural column or slack slot
         * @param scale  factor applied to the column
         * @param target dense vector the column is added into
         */
        void addColumn(int slot, double scale, double[] target) {
            if (slot >= structuralTotal) {
                target[slot - structuralTotal] -= scale;
                return;
            }
            for (int entry = columnStart[slot]; entry < columnStart[slot + 1]; entry++) {
                target[columnRow[entry]] += scale * columnValue[entry];
            }
        }

        /**
         * Dot product of a dense row-indexed vector with column {@code slot} of {@code [A, -I]}.
         *
         * @param slot   structural column or slack slot
         * @param vector dense vector over the rows
         * @return the product
         */
        double dotColumn(int slot, double[] vector) {
            if (slot >= structuralTotal) {
                return -vector[slot - structuralTotal];
            }
            double sum = 0.0;
            for (int entry = columnStart[slot]; entry < columnStart[slot + 1]; entry++) {
                sum += columnValue[entry] * vector[columnRow[entry]];
            }
            return sum;
        }
    }

    /** One pivot of the product-form inverse: the identity with column {@code position} replaced. */
    private static final class Eta {

        final int position;

        final double pivot;

        final int[] index;

        final double[] value;

        Eta(int position, double pivot, int[] index, double[] value) {
            this.position = position;
            this.pivot = pivot;
            this.index = index;
            this.value = value;
        }
    }

    /** The shared matrix and objective. */
    final Matrix matrix;

    /** Lower bound per slot. */
    final double[] lower;

    /** Upper bound per slot, {@code +∞} when unbounded. */
    final double[] upper;

    /** Basic slot per basis position. */
    final int[] basisSlot;

    /** Whether each nonbasic slot sits at its upper bound. */
    final boolean[] atUpper;

    /** Value of the basic slot per position. */
    final double[] basic;

    private final int[] positionOf;

    private final double[] reduced;

    private final double[] weight;

    private List<Eta> etas;

    private int updatesSinceRefactor;

    private final double[] rowWork;

    private final double[] columnWork;

    private final double[] tauWork;

    private final double[] alphaWork;

    private final int[] touched;

    private final boolean[] isTouched;

    /**
     * Start from the all-slack basis with every structural slot nonbasic at its lower bound, a
     * dual-feasible start because every cost is non-negative.
     *
     * @param matrix the constraint matrix and objective
     * @param lower  lower bound per slot, kept by reference
     * @param upper  upper bound per slot, kept by reference
     */
    SparseDualSimplex(Matrix matrix, double[] lower, double[] upper) {
        this.matrix = matrix;
        this.lower = lower;
        this.upper = upper;
        int rows = matrix.rowTotal;
        int slots = matrix.slotTotal;
        basisSlot = new int[rows];
        positionOf = new int[slots];
        Arrays.fill(positionOf, -1);
        for (int row = 0; row < rows; row++) {
            basisSlot[row] = matrix.structuralTotal + row;
            positionOf[matrix.structuralTotal + row] = row;
        }
        atUpper = new boolean[slots];
        basic = new double[rows];
        reduced = new double[slots];
        weight = new double[rows];
        Arrays.fill(weight, 1.0);
        etas = new ArrayList<>();
        rowWork = new double[rows];
        columnWork = new double[rows];
        tauWork = new double[rows];
        alphaWork = new double[slots];
        touched = new int[slots];
        isTouched = new boolean[slots];
        recomputePrimalAndDual();
    }

    private SparseDualSimplex(SparseDualSimplex source, double[] lower, double[] upper) {
        this.matrix = source.matrix;
        this.lower = lower;
        this.upper = upper;
        basisSlot = source.basisSlot.clone();
        positionOf = source.positionOf.clone();
        atUpper = source.atUpper.clone();
        basic = source.basic.clone();
        reduced = source.reduced.clone();
        weight = source.weight.clone();
        etas = new ArrayList<>(source.etas);
        updatesSinceRefactor = source.updatesSinceRefactor;
        int rows = matrix.rowTotal;
        int slots = matrix.slotTotal;
        rowWork = new double[rows];
        columnWork = new double[rows];
        tauWork = new double[rows];
        alphaWork = new double[slots];
        touched = new int[slots];
        isTouched = new boolean[slots];
    }

    /**
     * A copy with its own bounds that shares the matrix and the eta prefix with this one.
     *
     * @return the copy, ready to be re-solved under changed bounds
     */
    SparseDualSimplex copy() {
        return new SparseDualSimplex(this, lower.clone(), upper.clone());
    }

    /**
     * Rebuild a solver at a stored basis under new bounds, for a branch and bound node taken back
     * up from the frontier.
     *
     * @param lower     lower bound per slot, kept by reference
     * @param upper     upper bound per slot, kept by reference
     * @param basisSlot basic slot per position of the stored basis
     * @param atUpper   which nonbasic slots of the stored basis sit at their upper bound
     * @return the solver at that basis, dual feasible, its primal values computed
     */
    SparseDualSimplex restart(double[] lower, double[] upper, int[] basisSlot, boolean[] atUpper) {
        SparseDualSimplex restarted = new SparseDualSimplex(this, lower, upper);
        System.arraycopy(basisSlot, 0, restarted.basisSlot, 0, basisSlot.length);
        System.arraycopy(atUpper, 0, restarted.atUpper, 0, atUpper.length);
        Arrays.fill(restarted.weight, 1.0);
        Arrays.fill(restarted.positionOf, -1);
        for (int position = 0; position < basisSlot.length; position++) {
            restarted.positionOf[basisSlot[position]] = position;
        }
        restarted.refactor();
        return restarted;
    }

    /**
     * Value of one slot: its basic value, or the bound it sits at.
     *
     * @param slot structural column or slack slot
     * @return the slot's current value
     */
    double valueOf(int slot) {
        int position = positionOf[slot];
        if (position >= 0) {
            return basic[position];
        }
        return atUpper[slot] ? upper[slot] : lower[slot];
    }

    /**
     * Reduced cost of one slot, {@code 0} while it is basic.
     *
     * @param slot structural column or slack slot
     * @return how much the objective rises per unit the slot moves off its bound
     */
    double reducedCost(int slot) {
        return positionOf[slot] >= 0 ? 0.0 : reduced[slot];
    }

    /**
     * Whether one slot is in the basis.
     *
     * @param slot structural column or slack slot
     * @return {@code true} when basic
     */
    boolean isBasic(int slot) {
        return positionOf[slot] >= 0;
    }

    /**
     * Objective of the current basic solution.
     *
     * @return {@code Σ cost · value} over the structural slots
     */
    double objective() {
        double sum = 0.0;
        for (int slot = 0; slot < matrix.structuralTotal; slot++) {
            if (matrix.cost[slot] != 0.0) {
                sum += matrix.cost[slot] * valueOf(slot);
            }
        }
        return sum;
    }

    /**
     * Pivot until every basic value is within its bounds, keeping dual feasibility.
     *
     * @throws IllegalStateException when the iteration limit is reached or the basis turns singular
     * @return false when the bounds admit no solution
     */
    boolean solve() {
        int rows = matrix.rowTotal;
        int iterationLimit = ITERATIONS_PER_DIMENSION * matrix.slotTotal;
        for (int iteration = 0; iteration < iterationLimit; iteration++) {
            int leaving = -1;
            double bestScore = 0.0;
            double leavingTarget = 0.0;
            for (int position = 0; position < rows; position++) {
                int slot = basisSlot[position];
                double value = basic[position];
                double tolerance = FEASIBILITY_TOLERANCE * (1.0 + Math.abs(value));
                double violation = lower[slot] - value;
                double target = lower[slot];
                if (violation <= tolerance) {
                    violation = value - upper[slot];
                    target = upper[slot];
                    if (violation <= tolerance) {
                        continue;
                    }
                }
                double score = violation * violation / weight[position];
                if (score > bestScore) {
                    bestScore = score;
                    leaving = position;
                    leavingTarget = target;
                }
            }
            if (leaving < 0) {
                return true;
            }
            if (!pivot(leaving, leavingTarget)) {
                return false;
            }
        }
        throw new IllegalStateException("integer program: dual simplex did not converge");
    }

    /**
     * One dual simplex pivot: the slot at {@code leaving} goes to {@code leavingTarget}, the
     * entering slot is chosen by the bounded dual ratio test on the pivot row.
     *
     * @param leaving       basis position whose slot leaves
     * @param leavingTarget the bound it leaves at
     * @return false when no slot can enter, so the bounds admit no solution
     */
    private boolean pivot(int leaving, double leavingTarget) {
        int rows = matrix.rowTotal;
        int structurals = matrix.structuralTotal;
        boolean leavesBelow = leavingTarget > basic[leaving];

        // Pivot row: rho = e_leaving · B⁻¹, and tableau entry -rho · M_j per nonbasic slot j.
        Arrays.fill(rowWork, 0.0);
        rowWork[leaving] = 1.0;
        btran(rowWork);
        double rhoNorm = 0.0;
        int touchedTotal = 0;
        for (int row = 0; row < rows; row++) {
            double rho = rowWork[row];
            if (rho == 0.0) {
                continue;
            }
            rhoNorm += rho * rho;
            for (int entry = matrix.rowStart[row]; entry < matrix.rowStart[row + 1]; entry++) {
                int column = matrix.rowColumn[entry];
                if (!isTouched[column]) {
                    isTouched[column] = true;
                    touched[touchedTotal++] = column;
                }
                alphaWork[column] -= rho * matrix.rowValue[entry];
            }
            int slack = structurals + row;
            isTouched[slack] = true;
            touched[touchedTotal++] = slack;
            alphaWork[slack] = rho;
        }

        int entering = -1;
        double bestRatio = Double.POSITIVE_INFINITY;
        double bestMagnitude = 0.0;
        for (int index = 0; index < touchedTotal; index++) {
            int slot = touched[index];
            double alpha = alphaWork[slot];
            if (positionOf[slot] >= 0 || Math.abs(alpha) <= PIVOT_TOLERANCE || lower[slot] == upper[slot]) {
                continue;
            }
            boolean increases = !atUpper[slot];
            if (leavesBelow != (increases == alpha > 0)) {
                continue;
            }
            double ratio = Math.abs(reduced[slot]) / Math.abs(alpha);
            if (ratio < bestRatio - PIVOT_TOLERANCE
                    || ratio <= bestRatio + PIVOT_TOLERANCE && Math.abs(alpha) > bestMagnitude) {
                bestRatio = Math.min(ratio, bestRatio);
                bestMagnitude = Math.abs(alpha);
                entering = slot;
            }
        }
        if (entering < 0) {
            clearTouched(touchedTotal);
            return false;
        }

        // Entering column: B⁻¹ M_entering, whose negation is the tableau column.
        Arrays.fill(columnWork, 0.0);
        matrix.addColumn(entering, 1.0, columnWork);
        ftran(columnWork);
        double pivotEntry = -columnWork[leaving];
        double step = (leavingTarget - basic[leaving]) / pivotEntry;
        double enteringValue = (atUpper[entering] ? upper[entering] : lower[entering]) + step;
        for (int position = 0; position < rows; position++) {
            double entry = columnWork[position];
            if (entry != 0.0 && position != leaving) {
                basic[position] -= entry * step;
            }
        }

        double enteringCost = reduced[entering];
        for (int index = 0; index < touchedTotal; index++) {
            int slot = touched[index];
            if (positionOf[slot] < 0 && slot != entering) {
                reduced[slot] -= enteringCost * alphaWork[slot] / pivotEntry;
            }
        }
        clearTouched(touchedTotal);
        int leavingSlot = basisSlot[leaving];
        reduced[leavingSlot] = enteringCost / pivotEntry;
        reduced[entering] = 0.0;

        // Dual steepest edge weights, with tau = B⁻¹ rhoᵀ.
        System.arraycopy(rowWork, 0, tauWork, 0, rows);
        ftran(tauWork);
        double alphaPivot = columnWork[leaving];
        for (int position = 0; position < rows; position++) {
            double entry = columnWork[position];
            if (entry == 0.0 || position == leaving) {
                continue;
            }
            double ratio = entry / alphaPivot;
            weight[position] = Math.max(weight[position] - 2.0 * ratio * tauWork[position] + ratio * ratio * rhoNorm,
                    MIN_WEIGHT);
        }
        weight[leaving] = Math.max(rhoNorm / (alphaPivot * alphaPivot), MIN_WEIGHT);

        addEta(leaving, columnWork);
        basic[leaving] = enteringValue;
        basisSlot[leaving] = entering;
        positionOf[entering] = leaving;
        positionOf[leavingSlot] = -1;
        atUpper[leavingSlot] = !leavesBelow;
        atUpper[entering] = false;
        if (++updatesSinceRefactor >= REFACTOR_INTERVAL) {
            refactor();
        }
        return true;
    }

    private void clearTouched(int touchedTotal) {
        for (int index = 0; index < touchedTotal; index++) {
            alphaWork[touched[index]] = 0.0;
            isTouched[touched[index]] = false;
        }
    }

    /**
     * Append the eta of a pivot at {@code position} whose column, already through {@code B⁻¹}, is
     * {@code column}.
     */
    private void addEta(int position, double[] column) {
        int nonzeros = 0;
        for (int row = 0; row < column.length; row++) {
            if (row != position && Math.abs(column[row]) > DROP_TOLERANCE) {
                nonzeros++;
            }
        }
        int[] index = new int[nonzeros];
        double[] value = new double[nonzeros];
        for (int row = 0, next = 0; row < column.length; row++) {
            if (row != position && Math.abs(column[row]) > DROP_TOLERANCE) {
                index[next] = row;
                value[next++] = column[row];
            }
        }
        etas.add(new Eta(position, column[position], index, value));
    }

    /** {@code vector ← B⁻¹ vector}, with {@code B} the all-slack basis {@code -I} times the etas. */
    private void ftran(double[] vector) {
        for (int row = 0; row < vector.length; row++) {
            vector[row] = -vector[row];
        }
        for (Eta eta : etas) {
            double pivotValue = vector[eta.position];
            if (pivotValue == 0.0) {
                continue;
            }
            pivotValue /= eta.pivot;
            vector[eta.position] = pivotValue;
            for (int entry = 0; entry < eta.index.length; entry++) {
                vector[eta.index[entry]] -= eta.value[entry] * pivotValue;
            }
        }
    }

    /** {@code vector ← vector · B⁻¹} for a row vector. */
    private void btran(double[] vector) {
        for (int at = etas.size() - 1; at >= 0; at--) {
            Eta eta = etas.get(at);
            double sum = vector[eta.position];
            for (int entry = 0; entry < eta.index.length; entry++) {
                sum -= vector[eta.index[entry]] * eta.value[entry];
            }
            vector[eta.position] = sum / eta.pivot;
        }
        for (int row = 0; row < vector.length; row++) {
            vector[row] = -vector[row];
        }
    }

    /**
     * Rebuild the eta file for the current basic slots from the all-slack basis: each basic
     * structural column replaces, at the largest pivot, one of the slacks that is not basic,
     * sparsest columns first. Positions are relabelled; each slot keeps its edge weight.
     *
     * @throws IllegalStateException when the basis is numerically singular
     */
    private void refactor() {
        int rows = matrix.rowTotal;
        int structurals = matrix.structuralTotal;
        double[] weightOfSlot = new double[matrix.slotTotal];
        List<Integer> structuralBasics = new ArrayList<>();
        for (int position = 0; position < rows; position++) {
            int slot = basisSlot[position];
            weightOfSlot[slot] = weight[position];
            if (slot < structurals) {
                structuralBasics.add(slot);
            }
        }
        structuralBasics.sort((first, second) -> Integer.compare(
                matrix.columnStart[first + 1] - matrix.columnStart[first],
                matrix.columnStart[second + 1] - matrix.columnStart[second]));
        boolean[] open = new boolean[rows];
        for (int row = 0; row < rows; row++) {
            open[row] = positionOf[structurals + row] < 0;
            basisSlot[row] = structurals + row;
        }
        etas = new ArrayList<>();
        for (int slot : structuralBasics) {
            Arrays.fill(columnWork, 0.0);
            matrix.addColumn(slot, 1.0, columnWork);
            ftran(columnWork);
            int best = -1;
            double bestMagnitude = PIVOT_TOLERANCE;
            for (int row = 0; row < rows; row++) {
                if (open[row] && Math.abs(columnWork[row]) > bestMagnitude) {
                    bestMagnitude = Math.abs(columnWork[row]);
                    best = row;
                }
            }
            if (best < 0) {
                throw new IllegalStateException("integer program: singular basis at refactorization");
            }
            open[best] = false;
            addEta(best, columnWork);
            basisSlot[best] = slot;
        }
        for (int position = 0; position < rows; position++) {
            positionOf[basisSlot[position]] = position;
            weight[position] = Math.max(weightOfSlot[basisSlot[position]], MIN_WEIGHT);
        }
        updatesSinceRefactor = 0;
        recomputePrimalAndDual();
    }

    /** Recompute the basic values and the reduced costs from scratch through the eta file. */
    private void recomputePrimalAndDual() {
        int rows = matrix.rowTotal;
        Arrays.fill(columnWork, 0.0);
        for (int slot = 0; slot < matrix.slotTotal; slot++) {
            if (positionOf[slot] >= 0) {
                continue;
            }
            double value = atUpper[slot] ? upper[slot] : lower[slot];
            if (value != 0.0) {
                matrix.addColumn(slot, value, columnWork);
            }
        }
        ftran(columnWork);
        for (int position = 0; position < rows; position++) {
            basic[position] = -columnWork[position];
        }
        for (int position = 0; position < rows; position++) {
            rowWork[position] = matrix.cost[basisSlot[position]];
        }
        btran(rowWork);
        for (int slot = 0; slot < matrix.slotTotal; slot++) {
            reduced[slot] = positionOf[slot] >= 0 ? 0.0 : matrix.cost[slot] - matrix.dotColumn(slot, rowWork);
        }
    }
}
