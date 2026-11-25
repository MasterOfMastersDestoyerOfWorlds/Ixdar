package ixdar.geometry.mesh.quadlayout.quantization;

import ixdar.geometry.mesh.data.representation.IntIdMinHeap;
import ixdar.platform.Platforms;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Exact integer program for quantization: minimise a non-negative weighting of non-negative
 * integers under {@code Σ left = Σ right} equalities and {@code Σ ≥ 1} cover rows.
 */
public final class IntegerProgram {

    private static final double INTEGRALITY_TOLERANCE = 1e-6;

    private static final double PRUNE_TOLERANCE = 1e-7;

    private static final double REDUCED_COST_TOLERANCE = 1e-9;

    private static final int RELIABILITY = 4;

    private static final int STRONG_CANDIDATES = 8;

    private static final double SCORE_EPSILON = 1e-6;

    private static final double CUTOFF_GAIN = 1e9;

    private static final long PROGRESS_INTERVAL_NANOS = 5_000_000_000L;

    private static final double NANOS_PER_SECOND = 1e9;

    private static final String LOG_PREFIX = "[branch-bound] ";

    private static final String BUILT_IN = "built-in";

    private static final String NATIVE = "SCIP";

    private static final long NODE_BUDGET = 100_000;

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
     * bound's first optimum, with no preference among tied optima, as in LCK21a §5.
     */
    public int[] values;

    /** Weighted sum of {@link #values} under {@link #weights}. */
    public double objectiveValue;

    /** Whether any assignment satisfies every row. */
    public boolean feasible;

    /**
     * Relative gap the search may stop at: a node is pruned once its relaxation is within this
     * fraction of the incumbent. {@code 0}, the default, proves the optimum.
     */
    public double relativeGap;

    /**
     * SCIP, which finishes every program the first dive leaves open; the platform's by default,
     * {@code null} (on the web build, or to force it) leaves the whole search to the built-in solver.
     */
    public NativeIntegerProgramBackend backend;

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
        this.backend = Platforms.isInitialized() ? Platforms.get().nativeIntegerProgramBackend() : null;
    }

    /**
     * Find a minimum-weight integer assignment by branch and bound over a root tightened by
     * {@link GomoryCuts}, handing a program its first dive leaves open to {@link #backend} when it
     * loads. Among tied optima the first found is kept (LCK21a §5 states no preference).
     *
     * @throws IllegalStateException when the node budget runs out, naming the gap, or the simplex
     *                               fails to converge or the rounded optimum violates a row
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

        int[][] matrixColumns = new int[rowTotal][];
        double[][] matrixCoefficients = new double[rowTotal][];
        for (int row = 0; row < rowTotal; row++) {
            int source = tableauSourceRow[row];
            int length = rowVariables[source].length;
            matrixColumns[row] = new int[length];
            matrixCoefficients[row] = new double[length];
            // Coefficient tightening (Savelsbergh 1994, §2): on a cover row of non-negative
            // integers whose coefficients are all positive, any one variable at 1 satisfies the
            // row, so every coefficient may drop to 1 without losing an integer point.
            boolean positiveCover = source >= equalityTotal;
            for (int coefficient : rowCoefficients[source]) {
                positiveCover &= coefficient > 0;
            }
            for (int position = 0; position < length; position++) {
                matrixColumns[row][position] = columnOfVariable[rowVariables[source][position]];
                matrixCoefficients[row][position] = positiveCover ? 1.0 : rowCoefficients[source][position];
            }
        }
        double[] columnWeights = new double[columns];
        for (int column = 0; column < columns; column++) {
            columnWeights[column] = presolvedWeights[variableOfColumn[column]];
        }
        SparseDualSimplex.Matrix matrix =
                new SparseDualSimplex.Matrix(matrixColumns, matrixCoefficients, columns, columnWeights);
        int variableSlots = columns + rowTotal;
        double[] rootLower = new double[variableSlots];
        double[] rootUpper = new double[variableSlots];
        for (int column = 0; column < columns; column++) {
            rootLower[column] = columnLower[column];
            rootUpper[column] = Double.POSITIVE_INFINITY;
        }
        for (int row = 0; row < rowTotal; row++) {
            boolean equality = tableauSourceRow[row] < equalityTotal;
            rootLower[columns + row] = equality ? 0.0 : 1.0;
            rootUpper[columns + row] = equality ? 0.0 : Double.POSITIVE_INFINITY;
        }
        double[] rowLower = Arrays.copyOfRange(rootLower, columns, variableSlots);
        double[] rowUpper = Arrays.copyOfRange(rootUpper, columns, variableSlots);

        double[] incumbent = null;
        double incumbentBound = Double.POSITIVE_INFINITY;
        double pruneAt = Double.POSITIVE_INFINITY;
        // An open node keeps its bound changes, a chain shared with its ancestors, and its own
        // solved basis, from which it is refactored and re-solved when it is taken up.
        List<OpenNode> open = new ArrayList<>();
        IntIdMinHeap frontier = new IntIdMinHeap(0, true);
        // The node in hand, solved: the root first, then after each branch its better child, so the
        // search plunges to an incumbent before it widens.
        SparseDualSimplex root = new SparseDualSimplex(matrix, rootLower.clone(), rootUpper.clone());
        SparseDualSimplex node = null;
        BoundChange nodeChanges = null;
        double relaxation = 0.0;
        long startedNanos = System.nanoTime();
        Platforms.log("%sroot rows=%d columns=%d: solving the LP relaxation%n", LOG_PREFIX, rowTotal, columns);
        double uncutRelaxation = 0.0;
        if (root.solve()) {
            uncutRelaxation = root.objective();
            root = GomoryCuts.separate(root, variableSlots, PRUNE_TOLERANCE);
        } else {
            root = null;
        }
        if (root != null) {
            node = root;
            relaxation = root.objective();
            rowTotal = root.matrix.rowTotal;
            rootLower = root.lower.clone();
            rootUpper = root.upper.clone();
        }
        Platforms.log("%sroot relaxation=%.3f feasible=%b (%.3f before %d Gomory cuts) %.1fs%n", LOG_PREFIX,
                relaxation, node != null, uncutRelaxation, rowTotal - matrix.rowTotal,
                (System.nanoTime() - startedNanos) / NANOS_PER_SECOND);
        String solver = BUILT_IN;
        // The root's reduced costs, for fixing columns globally each time the incumbent improves.
        double rootRelaxation = relaxation;
        double[] rootReduced = new double[columns];
        for (int column = 0; column < columns && node != null; column++) {
            rootReduced[column] = root.reducedCost(column);
        }
        // Pseudocosts: objective gain per unit of fractionality removed, summed per column and side.
        double[] downGainSum = new double[columns];
        double[] upGainSum = new double[columns];
        int[] downGainCount = new int[columns];
        int[] upGainCount = new int[columns];
        double[] gainTotals = new double[2];
        int[] gainCounts = new int[2];
        int[] candidateSlot = new int[rowTotal];
        double[] candidateValue = new double[rowTotal];
        boolean[] strongTried = new boolean[rowTotal];
        long fixedTotal = 0;
        long strongTotal = 0;
        long nodesSolved = 0;
        long nextProgressNanos = System.nanoTime() + PROGRESS_INTERVAL_NANOS;
        while (node != null || !frontier.isEmpty()) {
            // The budget is 3.5 times the most nodes a closing quantization program has needed
            // (crawfish IMG_4109, 28,571 nodes in 84.5 s); IMG_4113 is still open at 384,093.
            boolean overBudget = nodesSolved >= NODE_BUDGET;
            if (System.nanoTime() >= nextProgressNanos || overBudget) {
                nextProgressNanos = System.nanoTime() + PROGRESS_INTERVAL_NANOS;
                double bestBound = frontier.isEmpty() ? Double.POSITIVE_INFINITY
                        : frontier.peekPriority();
                if (node != null) {
                    bestBound = Math.min(bestBound, relaxation);
                }
                Platforms.log("%snodes=%d open=%d incumbent=%.3f bound=%.3f %.0fs%n", LOG_PREFIX,
                        nodesSolved, frontier.size(), incumbentBound, bestBound,
                        (System.nanoTime() - startedNanos) / NANOS_PER_SECOND);
                if (overBudget) {
                    throw new IllegalStateException(String.format("integer program: branch and bound used its"
                            + " node budget of %d (open=%d incumbent=%.6f bound=%.6f gap=%.6f)", NODE_BUDGET,
                            frontier.size(), incumbentBound, bestBound, incumbentBound - bestBound));
                }
            }
            if (node == null) {
                int index = frontier.pollId();
                OpenNode stored = open.get(index);
                open.set(index, null);
                if (stored.relaxation >= pruneAt) {
                    continue;
                }
                double[] lower = rootLower.clone();
                double[] upper = rootUpper.clone();
                for (BoundChange change = stored.changes; change != null; change = change.previous) {
                    if (change.upper) {
                        upper[change.slot] = Math.min(upper[change.slot], change.value);
                    } else {
                        lower[change.slot] = Math.max(lower[change.slot], change.value);
                    }
                }
                SparseDualSimplex restarted = root.restart(lower, upper, stored.basisSlot, stored.atUpper);
                if (!restarted.solve()) {
                    continue;
                }
                node = restarted;
                nodeChanges = stored.changes;
                relaxation = restarted.objective();
            }
            SparseDualSimplex current = node;
            BoundChange currentChanges = nodeChanges;
            node = null;
            nodesSolved++;
            if (relaxation >= pruneAt) {
                continue;
            }
            // Reduced-cost fixing (Achterberg 2007, §7.7): moving a nonbasic column k units off its
            // bound raises this node's bound by at least k times its reduced cost, so within this
            // subtree it cannot move further than the cutoff allows.
            if (incumbent != null) {
                double allowance = pruneAt - relaxation;
                for (int column = 0; column < columns; column++) {
                    double cost = Math.abs(current.reducedCost(column));
                    if (cost <= REDUCED_COST_TOLERANCE || current.isBasic(column)) {
                        continue;
                    }
                    double reach = Math.floor(allowance / cost + INTEGRALITY_TOLERANCE);
                    if (!current.atUpper[column] && current.lower[column] + reach < current.upper[column]) {
                        current.upper[column] = current.lower[column] + reach;
                        currentChanges = new BoundChange(column, true, current.upper[column], currentChanges);
                        fixedTotal++;
                    } else if (current.atUpper[column] && current.upper[column] - reach > current.lower[column]) {
                        current.lower[column] = current.upper[column] - reach;
                        currentChanges = new BoundChange(column, false, current.lower[column], currentChanges);
                        fixedTotal++;
                    }
                }
            }
            int candidateTotal = 0;
            for (int position = 0; position < rowTotal; position++) {
                int slot = current.basisSlot[position];
                double value = current.basic[position];
                if (slot < columns && Math.abs(value - Math.rint(value)) > INTEGRALITY_TOLERANCE) {
                    candidateSlot[candidateTotal] = slot;
                    candidateValue[candidateTotal] = value;
                    strongTried[candidateTotal++] = false;
                }
            }
            if (candidateTotal == 0) {
                incumbentBound = relaxation;
                pruneAt = incumbentBound - Math.max(PRUNE_TOLERANCE, relativeGap * Math.abs(incumbentBound));
                incumbent = new double[columns];
                for (int column = 0; column < columns; column++) {
                    incumbent[column] = current.valueOf(column);
                }
                // Root reduced-cost fixing: tighten the bounds every restarted node starts from.
                for (int column = 0; column < columns; column++) {
                    if (rootReduced[column] > REDUCED_COST_TOLERANCE) {
                        double reach = Math.floor((pruneAt - rootRelaxation) / rootReduced[column]
                                + INTEGRALITY_TOLERANCE);
                        rootUpper[column] = Math.min(rootUpper[column], rootLower[column] + reach);
                    }
                }
                Platforms.log("%snew incumbent=%.3f at node %d, open=%d %.0fs%n", LOG_PREFIX, incumbentBound,
                        nodesSolved, frontier.size(), (System.nanoTime() - startedNanos) / NANOS_PER_SECOND);
                // A program the first dive leaves open goes to SCIP; every suite program closes at the
                // root or in that dive, where loading the natives would only add time.
                if (backend != null && !frontier.isEmpty() && frontier.peekPriority() < pruneAt
                        && backend.available()) {
                    solver = NATIVE;
                    incumbent = backend.solve(columnWeights, columnLower, matrixColumns, matrixCoefficients,
                            rowLower, rowUpper, relativeGap, NODE_BUDGET);
                    incumbentBound = 0.0;
                    for (int column = 0; incumbent != null && column < columns; column++) {
                        incumbentBound += columnWeights[column] * incumbent[column];
                    }
                    break;
                }
                continue;
            }

            // Reliability branching (Achterberg, Koch and Martin 2005): score each candidate by
            // the product of its down and up gains, estimated from pseudocosts once both sides have
            // RELIABILITY observations, and measured by strong branching on up to STRONG_CANDIDATES
            // unreliable ones, heaviest fractional weight first.
            double meanDown = gainCounts[0] > 0 ? gainTotals[0] / gainCounts[0] : 1.0;
            double meanUp = gainCounts[1] > 0 ? gainTotals[1] / gainCounts[1] : 1.0;
            int branchSlot = -1;
            double branchValue = 0.0;
            double bestScore = -1.0;
            SparseDualSimplex[] branchChildren = null;
            double[] branchRelaxations = null;
            for (int strong = 0; strong < STRONG_CANDIDATES; strong++) {
                int pick = -1;
                double heaviest = -1.0;
                for (int candidate = 0; candidate < candidateTotal; candidate++) {
                    int slot = candidateSlot[candidate];
                    if (strongTried[candidate] || downGainCount[slot] >= RELIABILITY && upGainCount[slot] >= RELIABILITY) {
                        continue;
                    }
                    double fractionality = Math.abs(candidateValue[candidate] - Math.rint(candidateValue[candidate]));
                    double weight = presolvedWeights[variableOfColumn[slot]] * fractionality;
                    if (weight > heaviest) {
                        heaviest = weight;
                        pick = candidate;
                    }
                }
                if (pick < 0) {
                    break;
                }
                strongTried[pick] = true;
                strongTotal++;
                int slot = candidateSlot[pick];
                double value = candidateValue[pick];
                SparseDualSimplex[] trial = new SparseDualSimplex[2];
                double[] trialRelaxation = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
                double[] gain = new double[2];
                for (int side = 0; side < 2; side++) {
                    SparseDualSimplex child = current.copy();
                    if (side == 0) {
                        child.upper[slot] = Math.floor(value);
                    } else {
                        child.lower[slot] = Math.ceil(value);
                    }
                    double unit = side == 0 ? value - Math.floor(value) : Math.ceil(value) - value;
                    if (child.solve()) {
                        trial[side] = child;
                        trialRelaxation[side] = child.objective();
                        double observed = Math.max(trialRelaxation[side] - relaxation, 0.0) / unit;
                        if (side == 0) {
                            downGainSum[slot] += observed;
                            downGainCount[slot]++;
                        } else {
                            upGainSum[slot] += observed;
                            upGainCount[slot]++;
                        }
                        gainTotals[side] += observed;
                        gainCounts[side]++;
                    }
                    gain[side] = trialRelaxation[side] >= pruneAt ? CUTOFF_GAIN : trialRelaxation[side] - relaxation;
                }
                double score = Math.max(gain[0], SCORE_EPSILON) * Math.max(gain[1], SCORE_EPSILON);
                if (score > bestScore) {
                    bestScore = score;
                    branchSlot = slot;
                    branchValue = value;
                    branchChildren = trial;
                    branchRelaxations = trialRelaxation;
                }
            }
            for (int candidate = 0; candidate < candidateTotal; candidate++) {
                int slot = candidateSlot[candidate];
                if (strongTried[candidate]) {
                    continue;
                }
                double value = candidateValue[candidate];
                double downUnits = value - Math.floor(value);
                double downCost = downGainCount[slot] > 0 ? downGainSum[slot] / downGainCount[slot] : meanDown;
                double upCost = upGainCount[slot] > 0 ? upGainSum[slot] / upGainCount[slot] : meanUp;
                double score = Math.max(downUnits * downCost, SCORE_EPSILON)
                        * Math.max((1.0 - downUnits) * upCost, SCORE_EPSILON);
                if (score > bestScore) {
                    bestScore = score;
                    branchSlot = slot;
                    branchValue = value;
                    branchChildren = null;
                    branchRelaxations = null;
                }
            }

            if (branchChildren == null) {
                branchChildren = new SparseDualSimplex[2];
                branchRelaxations = new double[] {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
                for (int side = 0; side < 2; side++) {
                    SparseDualSimplex child = side == 0 ? current.copy() : current;
                    if (side == 0) {
                        child.upper[branchSlot] = Math.floor(branchValue);
                    } else {
                        child.lower[branchSlot] = Math.ceil(branchValue);
                    }
                    if (child.solve()) {
                        branchChildren[side] = child;
                        branchRelaxations[side] = child.objective();
                        double unit = side == 0 ? branchValue - Math.floor(branchValue)
                                : Math.ceil(branchValue) - branchValue;
                        double observed = Math.max(branchRelaxations[side] - relaxation, 0.0) / unit;
                        if (side == 0) {
                            downGainSum[branchSlot] += observed;
                            downGainCount[branchSlot]++;
                        } else {
                            upGainSum[branchSlot] += observed;
                            upGainCount[branchSlot]++;
                        }
                        gainTotals[side] += observed;
                        gainCounts[side]++;
                    }
                }
            }
            BoundChange[] branchChanges = {
                new BoundChange(branchSlot, true, Math.floor(branchValue), currentChanges),
                new BoundChange(branchSlot, false, Math.ceil(branchValue), currentChanges),
            };
            boolean downOpen = branchChildren[0] != null && branchRelaxations[0] < pruneAt;
            boolean upOpen = branchChildren[1] != null && branchRelaxations[1] < pruneAt;
            // Plunge into the lower bound (the up child on a tie), and leave the other on the frontier.
            int plunge = downOpen && (!upOpen || branchRelaxations[0] < branchRelaxations[1]) ? 0 : upOpen ? 1 : -1;
            if (plunge >= 0) {
                int other = 1 - plunge;
                if (other == 0 ? downOpen : upOpen) {
                    open.add(new OpenNode(branchRelaxations[other], branchChanges[other], branchChildren[other]));
                    frontier.insertOrUpdate(open.size() - 1, branchRelaxations[other]);
                }
                node = branchChildren[plunge];
                nodeChanges = branchChanges[plunge];
                relaxation = branchRelaxations[plunge];
            }
        }
        Platforms.log(LOG_PREFIX + "done solver=%s nodes=%d incumbent=%.3f fixed=%d strong=%d %.1fs%n", solver,
                nodesSolved, incumbentBound, fixedTotal, strongTotal,
                (System.nanoTime() - startedNanos) / NANOS_PER_SECOND);

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

    /** One tightened bound of a branch and bound node, linked to the ones of its ancestors. */
    private static final class BoundChange {

        final int slot;

        final boolean upper;

        final double value;

        final BoundChange previous;

        BoundChange(int slot, boolean upper, double value, BoundChange previous) {
            this.slot = slot;
            this.upper = upper;
            this.value = value;
            this.previous = previous;
        }
    }

    /** A solved node waiting on the frontier: its relaxation, its bounds and its optimal basis. */
    private static final class OpenNode {

        final double relaxation;

        final BoundChange changes;

        final int[] basisSlot;

        final boolean[] atUpper;

        OpenNode(double relaxation, BoundChange changes, SparseDualSimplex solved) {
            this.relaxation = relaxation;
            this.changes = changes;
            this.basisSlot = solved.basisSlot.clone();
            this.atUpper = solved.atUpper.clone();
        }
    }
}
