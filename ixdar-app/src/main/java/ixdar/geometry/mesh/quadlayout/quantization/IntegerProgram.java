package ixdar.geometry.mesh.quadlayout.quantization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

import ixdar.numeric.graph.UnionFind;

/**
 * Exact integer program for quantization: minimise a non-negative weighting of non-negative
 * integers under {@code Σ left = Σ right} equalities and {@code Σ ≥ 1} cover rows.
 */
public final class IntegerProgram {

    private static final double FEASIBILITY_TOLERANCE = 1e-9;

    private static final double PIVOT_TOLERANCE = 1e-9;

    private static final double INTEGRALITY_TOLERANCE = 1e-6;

    private static final double PRUNE_TOLERANCE = 1e-7;

    private static final double TIE_TOLERANCE = 1e-9;

    private static final int ITERATIONS_PER_DIMENSION = 50;

    private static final int PROPAGATION_PASSES = 20;

    /** Objective weight per variable, all non-negative. */
    public final double[] weights;

    /** Left side of each equality as an ascending variable multiset. */
    public final List<int[]> equalityLeftSides = new ArrayList<>();

    /** Right side of each equality as an ascending variable multiset, parallel to the left. */
    public final List<int[]> equalityRightSides = new ArrayList<>();

    /** Cover rows as ascending variable multisets: a variable's multiplicity is its coefficient. */
    public final List<int[]> coverRows = new ArrayList<>();

    /**
     * Optimal integer value per variable, filled by {@link #solve()} when feasible; among tied
     * optima, the one with most zeros, then the lexicographically smallest.
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
     * Find the minimum-weight integer assignment; among optima within a relative 1e-9, the one
     * with most zero variables, then the lexicographically smallest. Lyon et al. 2021 §5 states
     * no preference; most zeros is its coarsest layout.
     *
     * @throws IllegalStateException when the simplex fails to converge or the rounded optimum
     *                               violates a row, both numerical failures
     * @return this, with {@link #feasible}, {@link #values} and {@link #objectiveValue} set
     */
    public IntegerProgram solve() {
        int variableTotal = weights.length;

        // Cover dominance: a cover containing a kept cover is implied by it. Kept covers are
        // bucketed by their first variable so a candidate only meets kept covers it could contain.
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

        // Sparse rows (ascending variables, non-zero integer coefficients) with a per-variable
        // list of the rows that may contain it; entries go stale when a row drops the variable.
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

        // Substitute out x = Σ c·y (c > 0, integer) wherever an equality has a ±1 variable whose
        // sign no other entry shares: x stays integer and its bound x ≥ 0 is implied.
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
            // Newest first among equal bounds: a plateau of tied relaxations is dived, not swept.
            return byBound != 0 ? byBound : Integer.compare(second, first);
        });
        double[] basicValues = new double[rowTotal];
        double[] rowWeights = new double[rowTotal];
        double[] initialTableau = tableau.clone();
        double[] rootReducedCosts = null;
        int[] rootBasis = null;
        boolean[] rootAtUpper = null;
        double rootRelaxation = 0.0;
        if (dualSimplex(tableau, basis, bounds, atUpper, rowTotal, columns, basicValues, rowWeights)) {
            rootRelaxation = objectiveOf(tableau, basis, bounds, atUpper, rowTotal, columns);
            rootReducedCosts = Arrays.copyOfRange(tableau, rowTotal * columns, (rowTotal + 1) * columns);
            rootBasis = basis.clone();
            rootAtUpper = atUpper.clone();
            openTableaus.add(tableau);
            openBases.add(basis);
            openBounds.add(bounds.clone());
            openAtUpper.add(atUpper);
            openValues.add(basicValues.clone());
            openRelaxations.add(rootRelaxation);
            frontier.add(0);
        }
        // Ties are possible only in a node pruned within the tie tolerance of the optimum, or at
        // the optimal node itself when a free nonbasic slot has a near-zero reduced cost.
        double smallestPrunedRelaxation = Double.POSITIVE_INFINITY;
        double incumbentSmallestReducedCost = Double.POSITIVE_INFINITY;
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
                smallestPrunedRelaxation = Math.min(smallestPrunedRelaxation, relaxation);
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
                incumbentSmallestReducedCost = Double.POSITIVE_INFINITY;
                for (int column = 0; column < columns; column++) {
                    int slot = nodeBasis[rowTotal + column];
                    if (nodeBounds[slot] != nodeBounds[variableSlots + slot]) {
                        incumbentSmallestReducedCost = Math.min(incumbentSmallestReducedCost,
                                Math.abs(nodeTableau[rowTotal * columns + column]));
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
                    smallestPrunedRelaxation = Math.min(smallestPrunedRelaxation, childRelaxation);
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
        double tieTolerance = TIE_TOLERANCE * (1.0 + Math.abs(incumbentBound));
        if (smallestPrunedRelaxation <= incumbentBound + tieTolerance
                || incumbentSmallestReducedCost <= tieTolerance) {
            // Tie-break over the tied set T = {feasible integer x : w·x <= optimum + tolerance}:
            // fewest non-zero variables, then the lexicographically smallest vector. The program
            // lives in the presolved columns plus one row per substituted variable, the objective
            // ceiling, and an indicator column y >= x / bound for each variable that can exceed 1.
            double objectiveCeiling = incumbentBound + tieTolerance;
            boolean[] inEquality = new boolean[variableTotal];
            for (int equality = 0; equality < equalityTotal; equality++) {
                for (int variable : equalityLeftSides.get(equality)) {
                    inEquality[variable] = true;
                }
                for (int variable : equalityRightSides.get(equality)) {
                    inEquality[variable] = true;
                }
            }
            int[][] expansionColumns = new int[variableTotal][];
            int[][] expansionCoefficients = new int[variableTotal][];
            for (int column = 0; column < columns; column++) {
                expansionColumns[variableOfColumn[column]] = new int[] {column};
                expansionCoefficients[variableOfColumn[column]] = new int[] {1};
            }
            int substitutionTotal = substitutedVariables.size();
            int[] substitutionOfVariable = new int[variableTotal];
            int[] accumulated = new int[columns];
            int[] touchedColumns = new int[columns];
            for (int substitution = substitutionTotal - 1; substitution >= 0; substitution--) {
                int substituted = substitutedVariables.get(substitution);
                substitutionOfVariable[substituted] = substitution;
                int[] expressionVariables = substitutionVariables.get(substitution);
                int[] expressionCoefficients = substitutionCoefficients.get(substitution);
                int touchedTotal = 0;
                for (int position = 0; position < expressionVariables.length; position++) {
                    int[] termColumns = expansionColumns[expressionVariables[position]];
                    int[] termCoefficients = expansionCoefficients[expressionVariables[position]];
                    for (int term = 0; term < termColumns.length; term++) {
                        if (accumulated[termColumns[term]] == 0) {
                            touchedColumns[touchedTotal++] = termColumns[term];
                        }
                        accumulated[termColumns[term]] += expressionCoefficients[position] * termCoefficients[term];
                    }
                }
                Arrays.sort(touchedColumns, 0, touchedTotal);
                expansionColumns[substituted] = Arrays.copyOf(touchedColumns, touchedTotal);
                expansionCoefficients[substituted] = new int[touchedTotal];
                for (int term = 0; term < touchedTotal; term++) {
                    expansionCoefficients[substituted][term] = accumulated[touchedColumns[term]];
                    accumulated[touchedColumns[term]] = 0;
                }
            }

            // A variable off every equality can drop from any value above 1 to 1 without breaking
            // a row or losing ground on the tie-break, so 1 bounds it on the winner. One on an
            // equality is bounded by the ceiling over its weight; with zero weight it keeps the
            // first optimum's value (NaN bound), which quantization never produces.
            double[] fixedValue = new double[variableTotal];
            double[] valueBound = new double[variableTotal];
            int[] indicatorOfVariable = new int[variableTotal];
            int[] slotOfVariable = new int[variableTotal];
            double[] firstValues = new double[variableTotal];
            int indicatorTotal = 0;
            for (int variable = 0; variable < variableTotal; variable++) {
                for (int term = 0; term < expansionColumns[variable].length; term++) {
                    firstValues[variable] += expansionCoefficients[variable][term]
                            * Math.rint(incumbent[expansionColumns[variable][term]]);
                }
                double bound = !inEquality[variable] ? 1.0
                        : weights[variable] > 0.0
                                ? Math.floor(objectiveCeiling / weights[variable] + INTEGRALITY_TOLERANCE)
                                : Double.NaN;
                valueBound[variable] = bound;
                fixedValue[variable] = Double.isNaN(bound) ? firstValues[variable]
                        : bound == 0.0 ? 0.0 : Double.NaN;
                indicatorOfVariable[variable] = bound > 1.0 ? indicatorTotal++ : -1;
            }
            int tieColumns = columns + indicatorTotal;
            int substitutionRowStart = rowTotal;
            int ceilingRow = rowTotal + substitutionTotal;
            int indicatorRowStart = ceilingRow + 1;
            int tieRows = indicatorRowStart + indicatorTotal;
            int tieSlots = tieColumns + tieRows;
            double[] tieTableau = new double[(tieRows + 1) * tieColumns];
            double[] tieBounds = new double[2 * tieSlots];
            for (int row = 0; row < rowTotal; row++) {
                System.arraycopy(initialTableau, row * columns, tieTableau, row * tieColumns, columns);
                boolean equality = tableauSourceRow[row] < equalityTotal;
                tieBounds[tieColumns + row] = equality ? 0.0 : 1.0;
                tieBounds[tieSlots + tieColumns + row] = equality ? 0.0 : Double.POSITIVE_INFINITY;
            }
            for (int column = 0; column < columns; column++) {
                tieTableau[ceilingRow * tieColumns + column] = presolvedWeights[variableOfColumn[column]];
                tieBounds[column] = columnLower[column];
                tieBounds[tieSlots + column] = Double.POSITIVE_INFINITY;
            }
            tieBounds[tieSlots + tieColumns + ceilingRow] = objectiveCeiling;
            for (int variable = 0; variable < variableTotal; variable++) {
                int substitution = eliminated[variable] ? substitutionOfVariable[variable] : -1;
                slotOfVariable[variable] = substitution < 0 ? columnOfVariable[variable]
                        : tieColumns + substitutionRowStart + substitution;
                int indicator = indicatorOfVariable[variable];
                int[] termColumns = expansionColumns[variable];
                int[] termCoefficients = expansionCoefficients[variable];
                for (int term = 0; term < termColumns.length; term++) {
                    if (substitution >= 0) {
                        tieTableau[(substitutionRowStart + substitution) * tieColumns + termColumns[term]] =
                                termCoefficients[term];
                    }
                    if (indicator >= 0) {
                        tieTableau[(indicatorRowStart + indicator) * tieColumns + termColumns[term]] =
                                termCoefficients[term];
                    } else if (Double.isNaN(fixedValue[variable])) {
                        tieTableau[tieRows * tieColumns + termColumns[term]] += termCoefficients[term];
                    }
                }
                int slot = slotOfVariable[variable];
                if (substitution >= 0) {
                    tieBounds[tieSlots + slot] = Double.POSITIVE_INFINITY;
                }
                if (!Double.isNaN(fixedValue[variable])) {
                    tieBounds[slot] = fixedValue[variable];
                    tieBounds[tieSlots + slot] = fixedValue[variable];
                } else {
                    tieBounds[tieSlots + slot] = valueBound[variable];
                }
                if (indicator >= 0) {
                    int indicatorRow = indicatorRowStart + indicator;
                    tieTableau[indicatorRow * tieColumns + columns + indicator] = -valueBound[variable];
                    tieTableau[tieRows * tieColumns + columns + indicator] = 1.0;
                    tieBounds[tieSlots + columns + indicator] = 1.0;
                    tieBounds[tieColumns + indicatorRow] = -valueBound[variable];
                }
            }
            // Reduced-cost fixing at the root: w·x = root + Σ d·(slot − its bound), so on T a
            // nonbasic slot whose |d| exceeds the root gap cannot leave its integer bound.
            double rootGap = objectiveCeiling - rootRelaxation;
            for (int column = 0; column < columns; column++) {
                int slot = rootBasis[rowTotal + column];
                if (Math.abs(rootReducedCosts[column]) > rootGap) {
                    double value = bounds[(rootAtUpper[slot] ? variableSlots : 0) + slot];
                    int tieSlot = slot < columns ? slot : tieColumns + slot - columns;
                    tieBounds[tieSlot] = value;
                    tieBounds[tieSlots + tieSlot] = value;
                }
            }
            double[] rootTieBounds = tieBounds;

            // Independent components: columns joined by a row through free columns. Root slots left
            // free with d > 0 are the only ones that trade weight under the ceiling, so they join
            // one component; any other component's weight is constant over T. The lexicographic
            // minimum of a product is the product of the components' minima.
            int[] componentOfColumn = UnionFind.singletons(tieColumns);
            // Row tieRows stands for the ceiling's coupled set: the free columns of every root
            // nonbasic slot left free with d > 0 (a row slot contributes its row's columns).
            boolean[] ceilingCoupled = new boolean[tieColumns];
            for (int column = 0; column < columns; column++) {
                int slot = rootBasis[rowTotal + column];
                double reducedCost = Math.abs(rootReducedCosts[column]);
                int tieSlot = slot < columns ? slot : tieColumns + slot - columns;
                if (reducedCost == 0.0 || reducedCost > rootGap
                        || rootTieBounds[tieSlot] == rootTieBounds[tieSlots + tieSlot]) {
                    continue;
                }
                for (int member = 0; member < columns; member++) {
                    ceilingCoupled[member] |= slot < columns ? member == slot
                            : tieTableau[(slot - columns) * tieColumns + member] != 0.0;
                }
            }
            for (int row = 0; row <= tieRows; row++) {
                if (row == ceilingRow) {
                    continue;
                }
                int anchor = -1;
                for (int column = 0; column < tieColumns; column++) {
                    boolean joins = row < tieRows ? tieTableau[row * tieColumns + column] != 0.0
                            : ceilingCoupled[column];
                    if (!joins || rootTieBounds[column] == rootTieBounds[tieSlots + column]) {
                        continue;
                    }
                    if (anchor < 0) {
                        anchor = column;
                    }
                    UnionFind.union(componentOfColumn, anchor, column);
                }
            }
            // Group the free variables by component in ascending index: a variable whose columns
            // are all fixed, or whose value is fixed outright, is decided already.
            for (int column = 0; column < tieColumns; column++) {
                componentOfColumn[column] = UnionFind.find(componentOfColumn, column);
            }
            for (int column = 0; column < tieColumns; column++) {
                if (rootTieBounds[column] == rootTieBounds[tieSlots + column]) {
                    componentOfColumn[column] = -1;
                }
            }
            int[] componentOfVariable = new int[variableTotal];
            int[] componentSize = new int[tieColumns + 1];
            for (int variable = 0; variable < variableTotal; variable++) {
                int component = -1;
                for (int column : expansionColumns[variable]) {
                    if (Double.isNaN(fixedValue[variable]) && component < 0) {
                        component = componentOfColumn[column];
                    }
                }
                componentOfVariable[variable] = component;
                componentSize[component + 1]++;
            }
            int[] componentStart = new int[tieColumns + 1];
            for (int component = 1; component <= tieColumns; component++) {
                componentStart[component] = componentStart[component - 1] + componentSize[component];
            }
            int[] variablesByComponent = new int[variableTotal];
            int[] componentFill = componentStart.clone();
            for (int variable = 0; variable < variableTotal; variable++) {
                if (componentOfVariable[variable] >= 0) {
                    variablesByComponent[componentFill[componentOfVariable[variable]]++] = variable;
                }
            }

            // Per component: a best-first search over the count Σ y + Σ binary x (never above the
            // true count at an integer point, since y = [x > 0] stays feasible) for the fewest
            // non-zeros; then, variable by variable in index order, lower the witness's value
            // while a solution with that count still exists, and fix it.
            double[] witness = firstValues;
            List<double[]> searchBounds = new ArrayList<>();
            List<Double> searchRelaxations = new ArrayList<>();
            PriorityQueue<Integer> searchFrontier = new PriorityQueue<>((first, second) -> {
                int byBound = Double.compare(searchRelaxations.get(first), searchRelaxations.get(second));
                return byBound != 0 ? byBound : Integer.compare(second, first);
            });
            int coupledComponent = -1;
            for (int column = 0; column < tieColumns; column++) {
                if (ceilingCoupled[column] && componentOfColumn[column] >= 0) {
                    coupledComponent = componentOfColumn[column];
                }
            }
            int[] componentOfRow = new int[tieRows];
            for (int row = 0; row < tieRows; row++) {
                componentOfRow[row] = row == ceilingRow ? coupledComponent : -1;
                for (int column = 0; column < tieColumns && row != ceilingRow; column++) {
                    if (tieTableau[row * tieColumns + column] != 0.0 && componentOfColumn[column] >= 0) {
                        componentOfRow[row] = componentOfColumn[column];
                        break;
                    }
                }
            }
            int[] localColumnOf = new int[tieColumns];
            int[] localRowOf = new int[tieRows];
            for (int component = 0; component < tieColumns; component++) {
                int start = componentStart[component];
                int end = componentStart[component + 1];
                if (start == end) {
                    continue;
                }
                // The component's own program: its free columns and its rows. Every other column
                // of those rows is fixed, or in the ceiling row constant over T, so it moves into
                // the row's bounds at its first-optimum value.
                int localColumns = 0;
                for (int column = 0; column < tieColumns; column++) {
                    localColumnOf[column] = componentOfColumn[column] == component ? localColumns++ : -1;
                }
                int localRows = 0;
                for (int row = 0; row < tieRows; row++) {
                    localRowOf[row] = componentOfRow[row] == component ? localRows++ : -1;
                }
                int localSlots = localColumns + localRows;
                double[] localTableau = new double[(localRows + 1) * localColumns];
                double[] lexBounds = new double[2 * localSlots];
                for (int column = 0; column < tieColumns; column++) {
                    int local = localColumnOf[column];
                    if (local >= 0) {
                        lexBounds[local] = rootTieBounds[column];
                        lexBounds[localSlots + local] = rootTieBounds[tieSlots + column];
                        localTableau[localRows * localColumns + local] = tieTableau[tieRows * tieColumns + column];
                    }
                }
                for (int row = 0; row < tieRows; row++) {
                    int local = localRowOf[row];
                    if (local < 0) {
                        continue;
                    }
                    double constant = 0.0;
                    for (int column = 0; column < tieColumns; column++) {
                        double coefficient = tieTableau[row * tieColumns + column];
                        if (coefficient != 0.0 && localColumnOf[column] >= 0) {
                            localTableau[local * localColumns + localColumnOf[column]] = coefficient;
                        } else if (coefficient != 0.0) {
                            constant += coefficient * (componentOfColumn[column] < 0 ? rootTieBounds[column]
                                    : Math.rint(incumbent[column]));
                        }
                    }
                    lexBounds[localColumns + local] = rootTieBounds[tieColumns + row] - constant;
                    lexBounds[localSlots + localColumns + local] = rootTieBounds[tieSlots + tieColumns + row] - constant;
                }
                // Bound propagation over the component's rows, then each indicator row x − U·y
                // takes U from x's propagated maximum instead of the ceiling over its weight: the
                // loose U left the count relaxation near zero and the search exponential.
                boolean tightened = true;
                for (int pass = 0; pass < PROPAGATION_PASSES && tightened; pass++) {
                    tightened = false;
                    for (int row = 0; row < localRows; row++) {
                        double lowest = 0.0;
                        double highest = 0.0;
                        for (int column = 0; column < localColumns; column++) {
                            double coefficient = localTableau[row * localColumns + column];
                            if (coefficient != 0.0) {
                                lowest += coefficient * lexBounds[(coefficient > 0.0 ? 0 : localSlots) + column];
                                highest += coefficient * lexBounds[(coefficient > 0.0 ? localSlots : 0) + column];
                            }
                        }
                        double rowLow = lexBounds[localColumns + row];
                        double rowHigh = lexBounds[localSlots + localColumns + row];
                        for (int column = 0; column < localColumns; column++) {
                            double coefficient = localTableau[row * localColumns + column];
                            if (coefficient == 0.0) {
                                continue;
                            }
                            double ownLowest = coefficient * lexBounds[(coefficient > 0.0 ? 0 : localSlots) + column];
                            double ownHighest = coefficient * lexBounds[(coefficient > 0.0 ? localSlots : 0) + column];
                            double upper = coefficient > 0.0 ? (rowHigh - (lowest - ownLowest)) / coefficient
                                    : (rowLow - (highest - ownHighest)) / coefficient;
                            double lower = coefficient > 0.0 ? (rowLow - (highest - ownHighest)) / coefficient
                                    : (rowHigh - (lowest - ownLowest)) / coefficient;
                            upper = Math.floor(upper + INTEGRALITY_TOLERANCE);
                            lower = Math.ceil(lower - INTEGRALITY_TOLERANCE);
                            if (upper < lexBounds[localSlots + column] && upper >= lexBounds[column]) {
                                lexBounds[localSlots + column] = upper;
                                tightened = true;
                            }
                            if (lower > lexBounds[column] && lower <= lexBounds[localSlots + column]) {
                                lexBounds[column] = lower;
                                tightened = true;
                            }
                        }
                    }
                }
                for (int member = 0; member < end - start; member++) {
                    int variable = variablesByComponent[start + member];
                    int indicatorColumn = indicatorOfVariable[variable] < 0 ? -1
                            : localColumnOf[columns + indicatorOfVariable[variable]];
                    if (indicatorColumn < 0) {
                        continue;
                    }
                    int row = localRowOf[indicatorRowStart + indicatorOfVariable[variable]];
                    // The row's upper bound is minus the fixed columns' share of x.
                    double valueMaximum = -lexBounds[localSlots + localColumns + row];
                    for (int column = 0; column < localColumns; column++) {
                        double coefficient = localTableau[row * localColumns + column];
                        if (column != indicatorColumn && coefficient != 0.0) {
                            valueMaximum += coefficient * lexBounds[(coefficient > 0.0 ? localSlots : 0) + column];
                        }
                    }
                    double tightBound = Math.max(1.0, Math.floor(valueMaximum + INTEGRALITY_TOLERANCE));
                    double looseBound = -localTableau[row * localColumns + indicatorColumn];
                    if (tightBound < looseBound) {
                        localTableau[row * localColumns + indicatorColumn] = -tightBound;
                        lexBounds[localColumns + row] += looseBound - tightBound;
                    }
                }
                int[] localBasis = new int[localRows + localColumns];
                for (int row = 0; row < localRows; row++) {
                    localBasis[row] = localColumns + row;
                }
                for (int column = 0; column < localColumns; column++) {
                    localBasis[localRows + column] = column;
                }
                boolean[] localAtUpper = new boolean[localSlots];
                double[] localValues = new double[localRows];
                double[] localRowWeights = new double[localRows];
                int[] localRowOfSlot = new int[localSlots];
                double[] localBounds = new double[2 * localSlots];
                int[] memberSlot = new int[end - start];
                int[] memberIndicatorSlot = new int[end - start];
                for (int member = 0; member < end - start; member++) {
                    int variable = variablesByComponent[start + member];
                    int slot = slotOfVariable[variable];
                    memberSlot[member] = slot < tieColumns ? localColumnOf[slot]
                            : localColumns + localRowOf[slot - tieColumns];
                    memberIndicatorSlot[member] = indicatorOfVariable[variable] < 0 ? -1
                            : localColumnOf[columns + indicatorOfVariable[variable]];
                }
                int fewest = 0;
                for (int position = start; position < end; position++) {
                    fewest += witness[variablesByComponent[position]] > 0.0 ? 1 : 0;
                }
                int position = start - 1;
                while (position < end) {
                    int lexVariable = position < start ? -1 : variablesByComponent[position];
                    double[] searchStart = lexBounds.clone();
                    int cap = fewest - 1;
                    if (lexVariable >= 0) {
                        int slot = memberSlot[position - start];
                        int indicatorSlot = memberIndicatorSlot[position - start];
                        double value = witness[lexVariable];
                        if (value <= lexBounds[slot]) {
                            lexBounds[localSlots + slot] = value;
                            if (indicatorSlot >= 0 && value == 0.0) {
                                lexBounds[localSlots + indicatorSlot] = 0.0;
                            } else if (indicatorSlot >= 0) {
                                lexBounds[indicatorSlot] = 1.0;
                            }
                            position++;
                            continue;
                        }
                        searchStart[localSlots + slot] = value - 1.0;
                        if (indicatorSlot >= 0 && value == 1.0) {
                            searchStart[localSlots + indicatorSlot] = 0.0;
                        }
                        cap = fewest;
                    }
                    boolean found = false;
                    searchBounds.clear();
                    searchRelaxations.clear();
                    searchFrontier.clear();
                    searchBounds.add(searchStart);
                    searchRelaxations.add(Double.NEGATIVE_INFINITY);
                    searchFrontier.add(0);
                    while (!searchFrontier.isEmpty() && !(found && lexVariable >= 0)) {
                        int node = searchFrontier.poll();
                        double[] nodeTieBounds = searchBounds.get(node);
                        searchBounds.set(node, null);
                        if (searchRelaxations.get(node) > cap + INTEGRALITY_TOLERANCE) {
                            continue;
                        }
                        System.arraycopy(nodeTieBounds, 0, localBounds, 0, localBounds.length);
                        // Each nonbasic slot moves to the bound its reduced cost favours, which
                        // keeps the basis dual feasible: every bound that changes here is finite.
                        for (int column = 0; column < localColumns; column++) {
                            int slot = localBasis[localRows + column];
                            double reducedCost = localTableau[localRows * localColumns + column];
                            localAtUpper[slot] = localBounds[localSlots + slot] < Double.POSITIVE_INFINITY
                                    && (reducedCost < 0.0 || reducedCost == 0.0 && localAtUpper[slot]);
                        }
                        if (!dualSimplex(localTableau, localBasis, localBounds, localAtUpper, localRows,
                                localColumns, localValues, localRowWeights)) {
                            continue;
                        }
                        Arrays.fill(localRowOfSlot, -1);
                        for (int row = 0; row < localRows; row++) {
                            localRowOfSlot[localBasis[row]] = row;
                        }
                        double nodeCount = 0.0;
                        int nonZeros = 0;
                        int branchMember = -1;
                        double branchFractionality = 0.0;
                        double branchDown = 0.0;
                        double branchUp = 0.0;
                        for (int member = 0; member < end - start; member++) {
                            int slot = memberSlot[member];
                            double value = localRowOfSlot[slot] >= 0 ? localValues[localRowOfSlot[slot]]
                                    : localBounds[(localAtUpper[slot] ? localSlots : 0) + slot];
                            int indicatorSlot = memberIndicatorSlot[member];
                            double indicatorValue = indicatorSlot < 0 ? value
                                    : localRowOfSlot[indicatorSlot] >= 0 ? localValues[localRowOfSlot[indicatorSlot]]
                                            : localBounds[(localAtUpper[indicatorSlot] ? localSlots : 0)
                                                    + indicatorSlot];
                            nodeCount += indicatorValue;
                            double fractionality = Math.abs(value - Math.rint(value));
                            if (fractionality > INTEGRALITY_TOLERANCE && fractionality > branchFractionality) {
                                branchFractionality = fractionality;
                                branchMember = member;
                                branchDown = Math.floor(value);
                                branchUp = Math.ceil(value);
                            } else if (fractionality <= INTEGRALITY_TOLERANCE && Math.rint(value) >= 1.0) {
                                nonZeros++;
                                if (indicatorValue < 1.0 - INTEGRALITY_TOLERANCE && branchMember < 0) {
                                    branchMember = member;
                                    branchDown = 0.0;
                                    branchUp = 1.0;
                                }
                            }
                        }
                        if (nodeCount > cap + INTEGRALITY_TOLERANCE) {
                            continue;
                        }
                        if (branchMember < 0) {
                            found = true;
                            fewest = nonZeros;
                            cap = lexVariable < 0 ? nonZeros - 1 : cap;
                            for (int member = 0; member < end - start; member++) {
                                int slot = memberSlot[member];
                                witness[variablesByComponent[start + member]] = Math.rint(localRowOfSlot[slot] >= 0
                                        ? localValues[localRowOfSlot[slot]]
                                        : localBounds[(localAtUpper[slot] ? localSlots : 0) + slot]);
                            }
                            continue;
                        }
                        int slot = memberSlot[branchMember];
                        int indicatorSlot = memberIndicatorSlot[branchMember];
                        double[] down = localBounds.clone();
                        down[localSlots + slot] = branchDown;
                        if (branchDown == 0.0 && indicatorSlot >= 0) {
                            down[localSlots + indicatorSlot] = 0.0;
                        }
                        double[] up = localBounds.clone();
                        up[slot] = branchUp;
                        if (indicatorSlot >= 0) {
                            up[indicatorSlot] = 1.0;
                        }
                        for (double[] child : new double[][] {down, up}) {
                            if (child[slot] <= child[localSlots + slot]) {
                                searchBounds.add(child);
                                searchRelaxations.add(nodeCount);
                                searchFrontier.add(searchBounds.size() - 1);
                            }
                        }
                    }
                    if (lexVariable < 0) {
                        position++;
                    } else if (!found) {
                        // No smaller value keeps the count: the next pass fixes the witness's.
                        lexBounds[memberSlot[position - start]] = witness[lexVariable];
                    }
                }
            }
            for (int column = 0; column < columns; column++) {
                incumbent[column] = witness[variableOfColumn[column]];
            }
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
