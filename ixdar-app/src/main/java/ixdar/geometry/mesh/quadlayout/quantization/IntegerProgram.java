package ixdar.geometry.mesh.quadlayout.quantization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Exact integer program for quantization: minimise a non-negative weighting of non-negative
 * integers under {@code Σ left = Σ right} equalities and {@code Σ ≥ 1} cover rows.
 */
public final class IntegerProgram {

    private static final double FEASIBILITY_TOLERANCE = 1e-9;

    private static final double PIVOT_TOLERANCE = 1e-9;

    private static final double INTEGRALITY_TOLERANCE = 1e-6;

    private static final double PRUNE_TOLERANCE = 1e-7;

    private static final int ITERATIONS_PER_DIMENSION = 50;

    /** Objective weight per variable, all non-negative. */
    public final double[] weights;

    /** Left side of each equality as an ascending variable multiset. */
    public final List<int[]> equalityLeftSides = new ArrayList<>();

    /** Right side of each equality as an ascending variable multiset, parallel to the left. */
    public final List<int[]> equalityRightSides = new ArrayList<>();

    /** Cover rows as ascending variable multisets: a variable's multiplicity is its coefficient. */
    public final List<int[]> coverRows = new ArrayList<>();

    /**
     * Optimal integer value per variable, filled by {@link #solve()} when feasible: the branch and
     * bound's first optimum, with no preference among tied optima, as in Lyon 2021 §5.
     */
    public int[] values;

    /** Weighted sum of {@link #values} under {@link #weights}. */
    public double objectiveValue;

    /** Whether any assignment satisfies every row. */
    public boolean feasible;

    /**
     * Start a program over one variable per weight.
     *
     * @param weights objective weight per variable, all non-negative
     * @throws IllegalArgumentException when a weight is negative or NaN
     */
    public IntegerProgram(double[] weights) {
        for (double weight : weights) {
            if (!(weight >= 0.0)) {
                throw new IllegalArgumentException("integer program weights must be non-negative");
            }
        }
        this.weights = weights;
    }

    /**
     * Find a minimum-weight integer assignment by best-first branch and bound. Among tied optima
     * the first found is kept: Lyon et al. 2021 §5 states no preference, and on the test suite
     * it is the same answer the earlier most-zeros, lexicographic tie-break chose (CRAW-118).
     *
     * @throws IllegalStateException when the simplex fails to converge or the rounded optimum
     *                               violates a row, both numerical failures
     * @return this, with {@link #feasible}, {@link #values} and {@link #objectiveValue} set
     */
    public IntegerProgram solve() {
        int variableTotal = weights.length;

        List<int[]> coversByLength = new ArrayList<>(coverRows);
        coversByLength.sort((first, second) -> Integer.compare(first.length, second.length));
        List<int[]> keptCovers = new ArrayList<>();
        int[][] keptByFirstVariable = new int[variableTotal][];
        int[] keptByFirstVariableSize = new int[variableTotal];
        for (int[] cover : coversByLength) {
            boolean dominated = false;
            for (int start = 0; start < cover.length && !dominated; start++) {
                if (start > 0 && cover[start] == cover[start - 1]) {
                    continue;
                }
                int firstVariable = cover[start];
                for (int bucket = 0; bucket < keptByFirstVariableSize[firstVariable] && !dominated;
                        bucket++) {
                    int[] kept = keptCovers.get(keptByFirstVariable[firstVariable][bucket]);
                    int coverPosition = start;
                    boolean contained = true;
                    for (int keptVariable : kept) {
                        while (coverPosition < cover.length && cover[coverPosition] < keptVariable) {
                            coverPosition++;
                        }
                        if (coverPosition == cover.length || cover[coverPosition] != keptVariable) {
                            contained = false;
                            break;
                        }
                        coverPosition++;
                    }
                    dominated = contained;
                }
            }
            if (dominated) {
                continue;
            }
            int firstVariable = cover[0];
            if (keptByFirstVariable[firstVariable] == null) {
                keptByFirstVariable[firstVariable] = new int[2];
            } else if (keptByFirstVariableSize[firstVariable] == keptByFirstVariable[firstVariable].length) {
                keptByFirstVariable[firstVariable] = Arrays.copyOf(keptByFirstVariable[firstVariable],
                        2 * keptByFirstVariableSize[firstVariable]);
            }
            keptByFirstVariable[firstVariable][keptByFirstVariableSize[firstVariable]++] = keptCovers.size();
            keptCovers.add(cover);
        }

        int equalityTotal = equalityLeftSides.size();
        int rowCapacity = equalityTotal + keptCovers.size();
        int[][] rowVariables = new int[rowCapacity][];
        int[][] rowCoefficients = new int[rowCapacity][];
        int[] mergeVariables = new int[variableTotal];
        int[] mergeCoefficients = new int[variableTotal];
        for (int row = 0; row < rowCapacity; row++) {
            int[] left = row < equalityTotal ? equalityLeftSides.get(row) : keptCovers.get(row - equalityTotal);
            int[] right = row < equalityTotal ? equalityRightSides.get(row) : new int[0];
            int length = 0;
            int leftPosition = 0;
            int rightPosition = 0;
            while (leftPosition < left.length || rightPosition < right.length) {
                int variable = rightPosition == right.length
                        || leftPosition < left.length && left[leftPosition] <= right[rightPosition]
                        ? left[leftPosition] : right[rightPosition];
                int coefficient = 0;
                while (leftPosition < left.length && left[leftPosition] == variable) {
                    coefficient++;
                    leftPosition++;
                }
                while (rightPosition < right.length && right[rightPosition] == variable) {
                    coefficient--;
                    rightPosition++;
                }
                if (coefficient != 0) {
                    mergeVariables[length] = variable;
                    mergeCoefficients[length++] = coefficient;
                }
            }
            rowVariables[row] = Arrays.copyOf(mergeVariables, length);
            rowCoefficients[row] = Arrays.copyOf(mergeCoefficients, length);
        }
        int[][] rowsOfVariable = new int[variableTotal][];
        int[] rowsOfVariableSize = new int[variableTotal];
        for (int row = 0; row < rowCapacity; row++) {
            for (int variable : rowVariables[row]) {
                if (rowsOfVariable[variable] == null) {
                    rowsOfVariable[variable] = new int[4];
                } else if (rowsOfVariableSize[variable] == rowsOfVariable[variable].length) {
                    rowsOfVariable[variable] = Arrays.copyOf(rowsOfVariable[variable],
                            2 * rowsOfVariableSize[variable]);
                }
                rowsOfVariable[variable][rowsOfVariableSize[variable]++] = row;
            }
        }

        double[] presolvedWeights = weights.clone();
        boolean[] eliminated = new boolean[variableTotal];
        boolean[] rowRemoved = new boolean[rowCapacity];
        List<Integer> substitutedVariables = new ArrayList<>();
        List<int[]> substitutionVariables = new ArrayList<>();
        List<int[]> substitutionCoefficients = new ArrayList<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int row = 0; row < equalityTotal; row++) {
                if (rowRemoved[row]) {
                    continue;
                }
                int[] variables = rowVariables[row];
                int[] coefficients = rowCoefficients[row];
                if (variables.length == 0) {
                    rowRemoved[row] = true;
                    continue;
                }
                int positiveTotal = 0;
                for (int coefficient : coefficients) {
                    if (coefficient > 0) {
                        positiveTotal++;
                    }
                }
                int pivotPosition = -1;
                for (int position = 0; position < variables.length && pivotPosition < 0; position++) {
                    int sameSignTotal = coefficients[position] > 0 ? positiveTotal
                            : variables.length - positiveTotal;
                    if (Math.abs(coefficients[position]) == 1 && sameSignTotal == 1) {
                        pivotPosition = position;
                    }
                }
                if (pivotPosition < 0) {
                    continue;
                }
                int substituted = variables[pivotPosition];
                int sign = coefficients[pivotPosition];
                int[] expressionVariables = new int[variables.length - 1];
                int[] expressionCoefficients = new int[variables.length - 1];
                for (int position = 0, target = 0; position < variables.length; position++) {
                    if (position != pivotPosition) {
                        expressionVariables[target] = variables[position];
                        expressionCoefficients[target++] = -coefficients[position] * sign;
                    }
                }
                rowRemoved[row] = true;
                eliminated[substituted] = true;
                substitutedVariables.add(substituted);
                substitutionVariables.add(expressionVariables);
                substitutionCoefficients.add(expressionCoefficients);
                for (int incidence = 0; incidence < rowsOfVariableSize[substituted]; incidence++) {
                    int other = rowsOfVariable[substituted][incidence];
                    if (rowRemoved[other]) {
                        continue;
                    }
                    int[] otherVariables = rowVariables[other];
                    int[] otherCoefficients = rowCoefficients[other];
                    int found = Arrays.binarySearch(otherVariables, substituted);
                    if (found < 0) {
                        continue;
                    }
                    int scale = otherCoefficients[found];
                    int length = 0;
                    int otherPosition = 0;
                    int expressionPosition = 0;
                    while (otherPosition < otherVariables.length
                            || expressionPosition < expressionVariables.length) {
                        int variable;
                        int coefficient;
                        boolean added = false;
                        if (expressionPosition == expressionVariables.length
                                || otherPosition < otherVariables.length
                                && otherVariables[otherPosition] < expressionVariables[expressionPosition]) {
                            variable = otherVariables[otherPosition];
                            coefficient = variable == substituted ? 0 : otherCoefficients[otherPosition];
                            otherPosition++;
                        } else if (otherPosition == otherVariables.length
                                || expressionVariables[expressionPosition] < otherVariables[otherPosition]) {
                            variable = expressionVariables[expressionPosition];
                            coefficient = scale * expressionCoefficients[expressionPosition++];
                            added = true;
                        } else {
                            variable = otherVariables[otherPosition];
                            coefficient = otherCoefficients[otherPosition++]
                                    + scale * expressionCoefficients[expressionPosition++];
                        }
                        if (coefficient != 0) {
                            mergeVariables[length] = variable;
                            mergeCoefficients[length++] = coefficient;
                        }
                        if (added) {
                            if (rowsOfVariable[variable] == null) {
                                rowsOfVariable[variable] = new int[4];
                            } else if (rowsOfVariableSize[variable] == rowsOfVariable[variable].length) {
                                rowsOfVariable[variable] = Arrays.copyOf(rowsOfVariable[variable],
                                        2 * rowsOfVariableSize[variable]);
                            }
                            rowsOfVariable[variable][rowsOfVariableSize[variable]++] = other;
                        }
                    }
                    rowVariables[other] = Arrays.copyOf(mergeVariables, length);
                    rowCoefficients[other] = Arrays.copyOf(mergeCoefficients, length);
                }
                double substitutedWeight = presolvedWeights[substituted];
                presolvedWeights[substituted] = 0.0;
                for (int position = 0; position < expressionVariables.length; position++) {
                    presolvedWeights[expressionVariables[position]] +=
                            substitutedWeight * expressionCoefficients[position];
                }
                changed = true;
            }
        }

        int[] columnOfVariable = new int[variableTotal];
        int[] variableOfColumn = new int[variableTotal];
        int columns = 0;
        for (int variable = 0; variable < variableTotal; variable++) {
            columnOfVariable[variable] = eliminated[variable] ? -1 : columns;
            if (!eliminated[variable]) {
                variableOfColumn[columns++] = variable;
            }
        }
        double[] columnLower = new double[columns];
        int[] tableauSourceRow = new int[rowCapacity];
        int rowTotal = 0;
        for (int row = 0; row < rowCapacity; row++) {
            if (rowRemoved[row]) {
                continue;
            }
            int length = rowVariables[row].length;
            boolean equality = row < equalityTotal;
            if (length == 0) {
                if (!equality) {
                    feasible = false;
                    return this;
                }
                continue;
            }
            if (!equality && length == 1) {
                columnLower[columnOfVariable[rowVariables[row][0]]] = 1.0;
                continue;
            }
            tableauSourceRow[rowTotal++] = row;
        }

        int variableSlots = columns + rowTotal;
        double[] tableau = new double[(rowTotal + 1) * columns];
        for (int row = 0; row < rowTotal; row++) {
            int source = tableauSourceRow[row];
            for (int position = 0; position < rowVariables[source].length; position++) {
                tableau[row * columns + columnOfVariable[rowVariables[source][position]]] =
                        rowCoefficients[source][position];
            }
        }
        for (int column = 0; column < columns; column++) {
            tableau[rowTotal * columns + column] = presolvedWeights[variableOfColumn[column]];
        }
        int[] basis = new int[rowTotal + columns];
        for (int row = 0; row < rowTotal; row++) {
            basis[row] = columns + row;
        }
        for (int column = 0; column < columns; column++) {
            basis[rowTotal + column] = column;
        }
        double[] bounds = new double[2 * variableSlots];
        for (int column = 0; column < columns; column++) {
            bounds[column] = columnLower[column];
            bounds[variableSlots + column] = Double.POSITIVE_INFINITY;
        }
        for (int row = 0; row < rowTotal; row++) {
            boolean equality = tableauSourceRow[row] < equalityTotal;
            bounds[columns + row] = equality ? 0.0 : 1.0;
            bounds[variableSlots + columns + row] = equality ? 0.0 : Double.POSITIVE_INFINITY;
        }
        boolean[] atUpper = new boolean[variableSlots];

        double[] incumbent = null;
        double incumbentBound = Double.POSITIVE_INFINITY;
        List<double[]> openTableaus = new ArrayList<>();
        List<int[]> openBases = new ArrayList<>();
        List<double[]> openBounds = new ArrayList<>();
        List<boolean[]> openAtUpper = new ArrayList<>();
        List<double[]> openValues = new ArrayList<>();
        List<Double> openRelaxations = new ArrayList<>();
        PriorityQueue<Integer> frontier = new PriorityQueue<>((first, second) -> {
            int byBound = Double.compare(openRelaxations.get(first), openRelaxations.get(second));
            return byBound != 0 ? byBound : Integer.compare(second, first);
        });
        double[] basicValues = new double[rowTotal];
        double[] rowWeights = new double[rowTotal];
        if (dualSimplex(tableau, basis, bounds, atUpper, rowTotal, columns, basicValues, rowWeights)) {
            double rootRelaxation = objectiveOf(tableau, basis, bounds, atUpper, rowTotal, columns);
            openTableaus.add(tableau);
            openBases.add(basis);
            openBounds.add(bounds.clone());
            openAtUpper.add(atUpper);
            openValues.add(basicValues.clone());
            openRelaxations.add(rootRelaxation);
            frontier.add(0);
        }
        while (!frontier.isEmpty()) {
            int node = frontier.poll();
            double relaxation = openRelaxations.get(node);
            double[] nodeTableau = openTableaus.get(node);
            int[] nodeBasis = openBases.get(node);
            double[] nodeBounds = openBounds.get(node);
            boolean[] nodeAtUpper = openAtUpper.get(node);
            double[] nodeValues = openValues.get(node);
            openTableaus.set(node, null);
            openBases.set(node, null);
            openBounds.set(node, null);
            openAtUpper.set(node, null);
            openValues.set(node, null);
            if (relaxation >= incumbentBound - PRUNE_TOLERANCE) {
                continue;
            }
            int branchRow = -1;
            double heaviest = -1.0;
            for (int row = 0; row < rowTotal; row++) {
                int slot = nodeBasis[row];
                double value = nodeValues[row];
                double fractionality = Math.abs(value - Math.rint(value));
                if (slot < columns && fractionality > INTEGRALITY_TOLERANCE
                        && presolvedWeights[variableOfColumn[slot]] * fractionality > heaviest) {
                    heaviest = presolvedWeights[variableOfColumn[slot]] * fractionality;
                    branchRow = row;
                }
            }
            if (branchRow < 0) {
                incumbentBound = relaxation;
                incumbent = new double[columns];
                for (int column = 0; column < columns; column++) {
                    incumbent[column] = nodeBounds[(nodeAtUpper[column] ? variableSlots : 0) + column];
                }
                for (int row = 0; row < rowTotal; row++) {
                    if (nodeBasis[row] < columns) {
                        incumbent[nodeBasis[row]] = nodeValues[row];
                    }
                }
                continue;
            }
            int branchSlot = nodeBasis[branchRow];
            double branchValue = nodeValues[branchRow];
            for (int side = 0; side < 2; side++) {
                double[] childTableau = side == 0 ? nodeTableau.clone() : nodeTableau;
                int[] childBasis = side == 0 ? nodeBasis.clone() : nodeBasis;
                double[] childBounds = side == 0 ? nodeBounds.clone() : nodeBounds;
                boolean[] childAtUpper = side == 0 ? nodeAtUpper.clone() : nodeAtUpper;
                if (side == 0) {
                    childBounds[variableSlots + branchSlot] = Math.floor(branchValue);
                } else {
                    childBounds[branchSlot] = Math.ceil(branchValue);
                }
                if (!dualSimplex(childTableau, childBasis, childBounds, childAtUpper, rowTotal,
                        columns, basicValues, rowWeights)) {
                    continue;
                }
                double childRelaxation =
                        objectiveOf(childTableau, childBasis, childBounds, childAtUpper, rowTotal, columns);
                if (childRelaxation >= incumbentBound - PRUNE_TOLERANCE) {
                    continue;
                }
                openTableaus.add(childTableau);
                openBases.add(childBasis);
                openBounds.add(childBounds);
                openAtUpper.add(childAtUpper);
                openValues.add(basicValues.clone());
                openRelaxations.add(childRelaxation);
                frontier.add(openRelaxations.size() - 1);
            }
        }

        feasible = incumbent != null;
        if (!feasible) {
            return this;
        }
        values = new int[variableTotal];
        for (int column = 0; column < columns; column++) {
            values[variableOfColumn[column]] = (int) Math.rint(incumbent[column]);
        }
        for (int substitution = substitutedVariables.size() - 1; substitution >= 0; substitution--) {
            int[] expressionVariables = substitutionVariables.get(substitution);
            int[] expressionCoefficients = substitutionCoefficients.get(substitution);
            int value = 0;
            for (int position = 0; position < expressionVariables.length; position++) {
                value += expressionCoefficients[position] * values[expressionVariables[position]];
            }
            values[substitutedVariables.get(substitution)] = value;
        }
        objectiveValue = 0.0;
        for (int variable = 0; variable < variableTotal; variable++) {
            if (values[variable] < 0) {
                throw new IllegalStateException("integer program: negative value after presolve");
            }
            objectiveValue += weights[variable] * values[variable];
        }
        for (int equality = 0; equality < equalityTotal; equality++) {
            long balance = 0;
            for (int variable : equalityLeftSides.get(equality)) {
                balance += values[variable];
            }
            for (int variable : equalityRightSides.get(equality)) {
                balance -= values[variable];
            }
            if (balance != 0) {
                throw new IllegalStateException("integer program: rounded optimum breaks an equality");
            }
        }
        for (int[] cover : coverRows) {
            long sum = 0;
            for (int variable : cover) {
                sum += values[variable];
            }
            if (sum < 1) {
                throw new IllegalStateException("integer program: rounded optimum breaks a cover row");
            }
        }
        return this;
    }

    /**
     * Bounded dual simplex from a dual-feasible basis, pivoting out the basic slot with the
     * largest bound violation per unit tableau-row norm until every basic value is in bounds.
     *
     * @param tableau     row-major {@code (rowTotal + 1) × columns} tableau over the nonbasic
     *                    columns, the reduced costs last, pivoted in place
     * @param basis       basic slot per row, then nonbasic slot per column, updated in place
     * @param bounds      lower bound per slot, then upper bound per slot
     * @param atUpper     whether each nonbasic slot sits at its upper bound, updated in place
     * @param rowTotal    number of rows
     * @param columns     number of nonbasic columns
     * @param basicValues receives the basic value per row
     * @param rowWeights  scratch for one plus each row's squared norm
     * @throws IllegalStateException when the iteration limit is reached
     * @return false when the bounds admit no solution
     */
    private static boolean dualSimplex(double[] tableau, int[] basis, double[] bounds,
            boolean[] atUpper, int rowTotal, int columns, double[] basicValues, double[] rowWeights) {
        int variableSlots = rowTotal + columns;
        Arrays.fill(basicValues, 0.0);
        for (int column = 0; column < columns; column++) {
            int slot = basis[rowTotal + column];
            double value = bounds[(atUpper[slot] ? variableSlots : 0) + slot];
            if (value == 0.0) {
                continue;
            }
            for (int row = 0; row < rowTotal; row++) {
                basicValues[row] += tableau[row * columns + column] * value;
            }
        }
        for (int row = 0; row < rowTotal; row++) {
            double norm = 1.0;
            for (int offset = row * columns, end = offset + columns; offset < end; offset++) {
                norm += tableau[offset] * tableau[offset];
            }
            rowWeights[row] = norm;
        }
        int[] pivotNonzeros = new int[columns];
        int costOffset = rowTotal * columns;
        int iterationLimit = ITERATIONS_PER_DIMENSION * variableSlots;
        for (int iteration = 0; iteration < iterationLimit; iteration++) {
            int leavingRow = -1;
            double bestScore = 0.0;
            double leavingTarget = 0.0;
            for (int row = 0; row < rowTotal; row++) {
                int slot = basis[row];
                double value = basicValues[row];
                double tolerance = FEASIBILITY_TOLERANCE * (1.0 + Math.abs(value));
                double violation = bounds[slot] - value;
                double target = bounds[slot];
                if (violation <= tolerance) {
                    violation = value - bounds[variableSlots + slot];
                    target = bounds[variableSlots + slot];
                    if (violation <= tolerance) {
                        continue;
                    }
                }
                double score = violation * violation / rowWeights[row];
                if (score > bestScore) {
                    bestScore = score;
                    leavingRow = row;
                    leavingTarget = target;
                }
            }
            if (leavingRow < 0) {
                return true;
            }
            boolean leavesBelow = leavingTarget > basicValues[leavingRow];
            int pivotOffset = leavingRow * columns;
            int enteringColumn = -1;
            double bestRatio = Double.POSITIVE_INFINITY;
            double bestMagnitude = 0.0;
            for (int column = 0; column < columns; column++) {
                double alpha = tableau[pivotOffset + column];
                if (Math.abs(alpha) <= PIVOT_TOLERANCE) {
                    continue;
                }
                int slot = basis[rowTotal + column];
                if (bounds[slot] == bounds[variableSlots + slot]) {
                    continue;
                }
                boolean increases = !atUpper[slot];
                if (leavesBelow != (increases == alpha > 0)) {
                    continue;
                }
                double ratio = Math.abs(tableau[costOffset + column]) / Math.abs(alpha);
                if (ratio < bestRatio - PIVOT_TOLERANCE
                        || ratio <= bestRatio + PIVOT_TOLERANCE && Math.abs(alpha) > bestMagnitude) {
                    bestRatio = Math.min(ratio, bestRatio);
                    bestMagnitude = Math.abs(alpha);
                    enteringColumn = column;
                }
            }
            if (enteringColumn < 0) {
                return false;
            }
            int enteringSlot = basis[rowTotal + enteringColumn];
            double pivot = tableau[pivotOffset + enteringColumn];
            double step = (leavingTarget - basicValues[leavingRow]) / pivot;
            double enteringValue = bounds[(atUpper[enteringSlot] ? variableSlots : 0) + enteringSlot] + step;
            double inversePivot = 1.0 / pivot;
            int nonzeroTotal = 0;
            double pivotRowNorm = 1.0;
            for (int column = 0; column < columns; column++) {
                double entry = tableau[pivotOffset + column];
                if (entry != 0.0) {
                    double scaled = column == enteringColumn ? inversePivot : -entry * inversePivot;
                    tableau[pivotOffset + column] = scaled;
                    pivotRowNorm += scaled * scaled;
                    pivotNonzeros[nonzeroTotal++] = column;
                }
            }
            for (int row = 0; row <= rowTotal; row++) {
                if (row == leavingRow) {
                    continue;
                }
                int rowOffset = row * columns;
                double factor = tableau[rowOffset + enteringColumn];
                if (factor == 0.0) {
                    continue;
                }
                tableau[rowOffset + enteringColumn] = 0.0;
                if (row == rowTotal) {
                    for (int position = 0; position < nonzeroTotal; position++) {
                        int column = pivotNonzeros[position];
                        tableau[rowOffset + column] += factor * tableau[pivotOffset + column];
                    }
                    continue;
                }
                basicValues[row] += factor * step;
                double normChange = -factor * factor;
                for (int position = 0; position < nonzeroTotal; position++) {
                    int column = pivotNonzeros[position];
                    double old = tableau[rowOffset + column];
                    double updated = old + factor * tableau[pivotOffset + column];
                    tableau[rowOffset + column] = updated;
                    normChange += updated * updated - old * old;
                }
                rowWeights[row] += normChange;
            }
            rowWeights[leavingRow] = pivotRowNorm;
            basicValues[leavingRow] = enteringValue;
            int leavingSlot = basis[leavingRow];
            basis[leavingRow] = enteringSlot;
            basis[rowTotal + enteringColumn] = leavingSlot;
            atUpper[leavingSlot] = !leavesBelow;
        }
        throw new IllegalStateException("integer program: dual simplex did not converge");
    }

    /**
     * Objective of the basic solution: the reduced costs against the nonbasic values, exact
     * because every row is homogeneous.
     *
     * @param tableau  row-major tableau whose last row holds the reduced costs
     * @param basis    basic slot per row, then nonbasic slot per column
     * @param bounds   lower bound per slot, then upper bound per slot
     * @param atUpper  whether each nonbasic slot sits at its upper bound
     * @param rowTotal number of rows
     * @param columns  number of nonbasic columns
     * @return the relaxation's objective value
     */
    private static double objectiveOf(double[] tableau, int[] basis, double[] bounds,
            boolean[] atUpper, int rowTotal, int columns) {
        int variableSlots = rowTotal + columns;
        double objective = 0.0;
        for (int column = 0; column < columns; column++) {
            int slot = basis[rowTotal + column];
            double value = bounds[(atUpper[slot] ? variableSlots : 0) + slot];
            if (value != 0.0) {
                objective += tableau[rowTotal * columns + column] * value;
            }
        }
        return objective;
    }
}
