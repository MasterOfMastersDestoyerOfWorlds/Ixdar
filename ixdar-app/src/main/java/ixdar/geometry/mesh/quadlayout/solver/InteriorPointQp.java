package ixdar.geometry.mesh.quadlayout.solver;

import java.util.Arrays;

import ixdar.geometry.mesh.quadlayout.solver.chol.CholeskyBackend;
import ixdar.geometry.mesh.quadlayout.solver.chol.EjmlCholeskyFactor;
import ixdar.geometry.mesh.quadlayout.solver.chol.NativeCholeskyBackend;
import ixdar.geometry.mesh.quadlayout.solver.matrix.CompressedSparseRowArrays;
import ixdar.geometry.mesh.quadlayout.solver.matrix.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.ordering.OrderingMethod;
import ixdar.geometry.mesh.quadlayout.solver.ordering.SolverPermutation;

/**
 * Infeasible-start primal-dual interior-point solver for the convex QP
 * {@code min ½x'Hx − b'x subject to Ax ≥ c}. One instance per constraint set;
 * after the first factorization, iterations refactorize numerically only.
 *
 * <p>See also: Nocedal &amp; Wright, Numerical Optimization, §16.6.
 */
public final class InteriorPointQp {

    /** Iteration cap; a round that hits it reports {@code converged == false}. */
    public static final int MAX_ITERATIONS = 50;

    /** Fraction-to-boundary factor τ keeping slacks and multipliers positive. */
    public static final double FRACTION_TO_BOUNDARY = 0.995;

    /**
     * Fixed centering parameter σ in {@code μ = σ·(s'λ/m)}. Chosen over
     * Mehrotra's predictor-corrector because it needs one factorization and one
     * solve per iteration instead of two solves, and stays deterministic.
     */
    public static final double CENTERING_SIGMA = 0.1;

    /** Lower bound on the starting slacks {@code s = max(Ax₀ − c, floor)}. */
    public static final double SLACK_START_FLOOR = 1.0e-2;

    /** Starting value of every multiplier λ. */
    public static final double MULTIPLIER_START = 1.0;

    /** Cap on the condensed diagonal ratios λ/s, guarding late-iteration blowup. */
    public static final double RATIO_CAP = 1.0e12;

    /** Dual-residual tolerance, relative to {@code 1 + ‖b‖∞}. */
    public static final double DUAL_TOLERANCE = 1.0e-8;

    /** Primal-residual tolerance, relative to {@code 1 + ‖c‖∞}. */
    public static final double PRIMAL_TOLERANCE = 1.0e-8;

    /** Complementarity tolerance on {@code s'λ/m}, relative to {@code 1 + ‖b‖∞}. */
    public static final double COMPLEMENTARITY_TOLERANCE = 1.0e-8;

    /** Base SPD system: H in full-symmetric CSR plus the linear term b as its RHS. */
    public final NormalMatrix baseSystem;

    /** Per-constraint variable indices of the rows of A. */
    public final int[][] constraintDofs;

    /** Coefficients matching {@link #constraintDofs}. */
    public final double[][] constraintCoefs;

    /** Right-hand side c of {@code Ax ≥ c}. */
    public final double[] constraintBound;

    /** When true, use the pure-Java EJML backend even if PARDISO loads. */
    public boolean forcePureJavaBackend;

    /** Newton iterations taken by the last {@link #solve}. */
    public int iterationCount;

    /** Factorizations (first factor plus numeric refactorizations) of the last solve. */
    public int factorizationCount;

    /** True iff the last solve met all three KKT tolerances before the cap. */
    public boolean converged;

    /** Final slacks s of the last solve; kept for inspection. */
    public double[] slack;

    /** Final multipliers λ; kept for inspection. */
    public double[] multiplier;

    /** Sorted condensed upper keys: base pattern unioned with constraint pairs. */
    public long[] condensedUpperKeys;

    /** Condensed slot of each constraint pair, flattened per constraint. */
    public int[] constraintPairSlot;

    /** Offsets into {@link #constraintPairSlot}, length {@code m + 1}. */
    public int[] constraintPairStart;

    /** Base upper values scattered into the condensed layout. */
    public double[] condensedBaseUpperValues;

    /** Per-iteration condensed diagonal scratch. */
    public double[] condensedDiagonal;

    /** Per-iteration condensed upper-value scratch. */
    public double[] condensedUpperValues;

    /** Fill-reducing permutation of the condensed system, {@code perm[new] = old}. */
    public int[] permutation;

    /**
     * Backend values-buffer source per position: {@code −dof−1} for a diagonal
     * entry, else a {@link #condensedUpperKeys} slot. The values-only extraction —
     * refactorizations never rebuild the CSR/CSC structure.
     */
    public int[] factorValueSource;

    /** Reusable backend values buffer matching {@link #factorValueSource}. */
    public double[] factorValues;

    /** Backend factor of the condensed system while iterating; null outside solve. */
    public FactorizedSystem factor;

    /**
     * Store the QP. The base system and constraint arrays are referenced, not
     * copied, and must stay unchanged for the lifetime of this solver.
     *
     * @param baseSystem      SPD Hessian H with the linear term b as its
     *                        right-hand side
     * @param constraintDofs  per-constraint variable indices of A's rows
     * @param constraintCoefs coefficients matching {@code constraintDofs}
     * @param constraintBound right-hand side c of {@code Ax ≥ c}; must be
     *                        non-empty
     * @throws IllegalArgumentException if the constraint set is empty
     */
    public InteriorPointQp(NormalMatrix baseSystem, int[][] constraintDofs,
            double[][] constraintCoefs, double[] constraintBound) {
        if (constraintBound.length == 0) {
            throw new IllegalArgumentException(
                    "empty constraint set: solve the base system directly instead");
        }
        this.baseSystem = baseSystem;
        this.constraintDofs = constraintDofs;
        this.constraintCoefs = constraintCoefs;
        this.constraintBound = constraintBound;
    }

    /**
     * Run the primal-dual iteration from the warm start in {@code x}, leaving
     * the primal solution there. Terminates on the KKT tolerances or
     * {@link #MAX_ITERATIONS}; check {@link #converged}. Releases the backend
     * factor before returning.
     *
     * @param x warm-start primal point in, solution out; length must equal the
     *          base system's dimension
     */
    public void solve(double[] x) {
        int n = baseSystem.size();
        int m = constraintBound.length;
        slack = new double[m];
        multiplier = new double[m];
        for (int i = 0; i < m; i++) {
            slack[i] = Math.max(constraintDot(i, x) - constraintBound[i], SLACK_START_FLOOR);
            multiplier[i] = MULTIPLIER_START;
        }
        double dualThreshold = DUAL_TOLERANCE * (1.0 + maxAbs(baseSystem.rightHandSide));
        double primalThreshold = PRIMAL_TOLERANCE * (1.0 + maxAbs(constraintBound));
        double complementarityThreshold = COMPLEMENTARITY_TOLERANCE
                * (1.0 + maxAbs(baseSystem.rightHandSide));

        double[] hxMinusB = new double[n];
        double[] dualResidual = new double[n];
        double[] newtonRhs = new double[n];
        double[] deltaX = new double[n];
        double[] permutedRhs = new double[n];
        double[] permutedSolution = new double[n];
        double[] primalResidual = new double[m];
        double[] deltaSlack = new double[m];
        double[] deltaMultiplier = new double[m];

        iterationCount = 0;
        factorizationCount = 0;
        converged = false;
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            for (int row = 0; row < n; row++) {
                hxMinusB[row] = baseSystem.rowDot(row, x) - baseSystem.rightHandSide[row];
                dualResidual[row] = hxMinusB[row];
            }
            for (int i = 0; i < m; i++) {
                primalResidual[i] = constraintDot(i, x) - slack[i] - constraintBound[i];
                int[] dofs = constraintDofs[i];
                double[] coefs = constraintCoefs[i];
                for (int k = 0; k < dofs.length; k++) {
                    dualResidual[dofs[k]] -= multiplier[i] * coefs[k];
                }
            }
            double complementarity = 0.0;
            for (int i = 0; i < m; i++) {
                complementarity += slack[i] * multiplier[i];
            }
            complementarity /= m;
            if (maxAbs(dualResidual) <= dualThreshold
                    && maxAbs(primalResidual) <= primalThreshold
                    && complementarity <= complementarityThreshold) {
                converged = true;
                break;
            }
            double mu = CENTERING_SIGMA * complementarity;

            if (condensedUpperKeys == null) {
                buildCondensedPlan();
            }
            System.arraycopy(baseSystem.diagonal, 0, condensedDiagonal, 0, n);
            System.arraycopy(condensedBaseUpperValues, 0, condensedUpperValues, 0,
                    condensedUpperValues.length);
            for (int row = 0; row < n; row++) {
                newtonRhs[row] = -hxMinusB[row];
            }
            for (int i = 0; i < m; i++) {
                double ratio = Math.min(multiplier[i] / slack[i], RATIO_CAP);
                double rhsCoefficient = mu / slack[i] - ratio * primalResidual[i];
                int[] dofs = constraintDofs[i];
                double[] coefs = constraintCoefs[i];
                int pairCursor = constraintPairStart[i];
                for (int k = 0; k < dofs.length; k++) {
                    condensedDiagonal[dofs[k]] += ratio * coefs[k] * coefs[k];
                    newtonRhs[dofs[k]] += rhsCoefficient * coefs[k];
                    for (int j = k + 1; j < dofs.length; j++) {
                        condensedUpperValues[constraintPairSlot[pairCursor++]] += ratio * coefs[k] * coefs[j];
                    }
                }
            }

            if (factor == null) {
                factorizeCondensed(newtonRhs);
            } else {
                for (int position = 0; position < factorValues.length; position++) {
                    int source = factorValueSource[position];
                    factorValues[position] = source < 0
                            ? condensedDiagonal[-source - 1]
                            : condensedUpperValues[source];
                }
                factor.refactorize(factorValues);
            }
            factorizationCount++;

            for (int newIndex = 0; newIndex < n; newIndex++) {
                permutedRhs[newIndex] = newtonRhs[permutation[newIndex]];
            }
            factor.solve(permutedRhs, permutedSolution);
            for (int newIndex = 0; newIndex < n; newIndex++) {
                deltaX[permutation[newIndex]] = permutedSolution[newIndex];
            }

            for (int i = 0; i < m; i++) {
                deltaSlack[i] = constraintDot(i, deltaX) + primalResidual[i];
                deltaMultiplier[i] = (mu - slack[i] * multiplier[i]
                        - multiplier[i] * deltaSlack[i]) / slack[i];
            }
            double alphaPrimal = 1.0;
            double alphaDual = 1.0;
            for (int i = 0; i < m; i++) {
                if (deltaSlack[i] < 0.0) {
                    alphaPrimal = Math.min(alphaPrimal,
                            FRACTION_TO_BOUNDARY * (-slack[i] / deltaSlack[i]));
                }
                if (deltaMultiplier[i] < 0.0) {
                    alphaDual = Math.min(alphaDual,
                            FRACTION_TO_BOUNDARY * (-multiplier[i] / deltaMultiplier[i]));
                }
            }
            for (int row = 0; row < n; row++) {
                x[row] += alphaPrimal * deltaX[row];
            }
            for (int i = 0; i < m; i++) {
                slack[i] += alphaPrimal * deltaSlack[i];
                multiplier[i] += alphaDual * deltaMultiplier[i];
            }
            iterationCount++;
        }
        if (factor != null) {
            factor.release();
            factor = null;
        }
    }

    /**
     * Build the condensed pattern: union the base upper keys with every
     * constraint's gradient pairs, resolve each pair's slot, and scatter the base
     * upper values into the condensed layout.
     */
    private void buildCondensedPlan() {
        int n = baseSystem.size();
        int m = constraintBound.length;
        int baseUpperCount = 0;
        for (int row = 0; row < n; row++) {
            for (int at = baseSystem.rowStart[row]; at < baseSystem.rowStart[row + 1]; at++) {
                if (baseSystem.rowColumn[at] > row) {
                    baseUpperCount++;
                }
            }
        }
        long[] baseKeys = new long[baseUpperCount];
        int baseCursor = 0;
        for (int row = 0; row < n; row++) {
            for (int at = baseSystem.rowStart[row]; at < baseSystem.rowStart[row + 1]; at++) {
                if (baseSystem.rowColumn[at] > row) {
                    baseKeys[baseCursor++] = ((long) row << NormalMatrix.KEY_ROW_SHIFT)
                            | baseSystem.rowColumn[at];
                }
            }
        }
        Arrays.sort(baseKeys);

        constraintPairStart = new int[m + 1];
        for (int i = 0; i < m; i++) {
            int length = constraintDofs[i].length;
            constraintPairStart[i + 1] = constraintPairStart[i] + length * (length - 1) / 2;
        }
        long[] pairKeys = new long[constraintPairStart[m]];
        int pairCursor = 0;
        for (int i = 0; i < m; i++) {
            int[] dofs = constraintDofs[i];
            for (int k = 0; k < dofs.length; k++) {
                for (int j = k + 1; j < dofs.length; j++) {
                    pairKeys[pairCursor++] = ((long) Math.min(dofs[k], dofs[j]) << NormalMatrix.KEY_ROW_SHIFT)
                            | Math.max(dofs[k], dofs[j]);
                }
            }
        }
        long[] sortedPairKeys = pairKeys.clone();
        Arrays.sort(sortedPairKeys);

        long[] merged = new long[baseKeys.length + sortedPairKeys.length];
        int mergedCount = 0;
        int baseAt = 0;
        int pairAt = 0;
        while (baseAt < baseKeys.length || pairAt < sortedPairKeys.length) {
            long next;
            if (pairAt >= sortedPairKeys.length
                    || (baseAt < baseKeys.length && baseKeys[baseAt] <= sortedPairKeys[pairAt])) {
                next = baseKeys[baseAt++];
            } else {
                next = sortedPairKeys[pairAt++];
            }
            while (baseAt < baseKeys.length && baseKeys[baseAt] == next) {
                baseAt++;
            }
            while (pairAt < sortedPairKeys.length && sortedPairKeys[pairAt] == next) {
                pairAt++;
            }
            merged[mergedCount++] = next;
        }
        condensedUpperKeys = Arrays.copyOf(merged, mergedCount);

        constraintPairSlot = new int[pairKeys.length];
        for (int pair = 0; pair < pairKeys.length; pair++) {
            constraintPairSlot[pair] = Arrays.binarySearch(condensedUpperKeys, pairKeys[pair]);
        }
        condensedBaseUpperValues = new double[mergedCount];
        for (int row = 0; row < n; row++) {
            for (int at = baseSystem.rowStart[row]; at < baseSystem.rowStart[row + 1]; at++) {
                int column = baseSystem.rowColumn[at];
                if (column > row) {
                    long key = ((long) row << NormalMatrix.KEY_ROW_SHIFT) | column;
                    condensedBaseUpperValues[Arrays.binarySearch(condensedUpperKeys, key)] = baseSystem.rowValue[at];
                }
            }
        }
        condensedDiagonal = new double[n];
        condensedUpperValues = new double[mergedCount];
    }

    /**
     * First factorization of the round: AMD-order the condensed system, extract
     * the backend's triangle, construct the factor, and record each backend
     * values position's source for later values-only refills.
     *
     * @param newtonRhs current Newton right-hand side, passed through to the
     *                  condensed matrix constructor (values unused by ordering)
     */
    private void factorizeCondensed(double[] newtonRhs) {
        int n = baseSystem.size();
        NormalMatrix condensed = new NormalMatrix(condensedDiagonal, condensedUpperKeys,
                condensedUpperValues, newtonRhs);
        boolean[] noneFixed = new boolean[n];
        int[] identityIndices = new int[n];
        for (int i = 0; i < n; i++) {
            identityIndices[i] = i;
        }
        permutation = SolverPermutation.computePermutation(condensed, noneFixed,
                identityIndices, n, OrderingMethod.AMD);
        int[] inversePermutation = new int[n];
        for (int i = 0; i < n; i++) {
            inversePermutation[permutation[i]] = i;
        }
        NativeCholeskyBackend nativeBackend = forcePureJavaBackend ? null : CholeskyBackend.nativeBackend();
        if (nativeBackend != null) {
            CompressedSparseRowArrays upperCsr = condensed.toPermutedUpperCompressedSparseRow(
                    n, noneFixed, identityIndices, identityIndices, permutation, inversePermutation);
            factor = nativeBackend.factorUpper(upperCsr, n);
            factorValueSource = new int[upperCsr.values.length];
            for (int permutedRow = 0; permutedRow < n; permutedRow++) {
                for (int position = upperCsr.rowPtr[permutedRow]; position < upperCsr.rowPtr[permutedRow + 1]; position++) {
                    factorValueSource[position] = sourceOf(permutedRow, upperCsr.colIdx[position]);
                }
            }
        } else {
            NormalMatrix.CompressedSparseColumnArrays upperCsc = condensed
                    .toPermutedUpperCompressedSparseColumn(n, noneFixed, identityIndices,
                            identityIndices, permutation, inversePermutation);
            factor = new EjmlCholeskyFactor(upperCsc, n);
            factorValueSource = new int[upperCsc.values().length];
            for (int permutedColumn = 0; permutedColumn < n; permutedColumn++) {
                for (int position = upperCsc.colPtr()[permutedColumn]; position < upperCsc.colPtr()[permutedColumn + 1]; position++) {
                    factorValueSource[position] = sourceOf(upperCsc.rowIdx()[position], permutedColumn);
                }
            }
        }
        factorValues = new double[factorValueSource.length];
    }

    /**
     * Encoded source of one backend values position: {@code −dof−1} for a
     * diagonal entry, else the condensed upper slot of the entry's unpermuted key.
     *
     * @param permutedRow    entry row in the permuted index space
     * @param permutedColumn entry column in the permuted index space
     * @return the encoded source index
     */
    private int sourceOf(int permutedRow, int permutedColumn) {
        int oldRow = permutation[permutedRow];
        int oldColumn = permutation[permutedColumn];
        if (oldRow == oldColumn) {
            return -oldRow - 1;
        }
        long key = ((long) Math.min(oldRow, oldColumn) << NormalMatrix.KEY_ROW_SHIFT)
                | Math.max(oldRow, oldColumn);
        return Arrays.binarySearch(condensedUpperKeys, key);
    }

    /**
     * One constraint row's dot product with a vector.
     *
     * @param constraint constraint index
     * @param vector     full-length vector
     * @return {@code a_constraint · vector}
     */
    private double constraintDot(int constraint, double[] vector) {
        int[] dofs = constraintDofs[constraint];
        double[] coefs = constraintCoefs[constraint];
        double sum = 0.0;
        for (int k = 0; k < dofs.length; k++) {
            sum += coefs[k] * vector[dofs[k]];
        }
        return sum;
    }

    /**
     * Infinity norm of a vector.
     *
     * @param vector the vector
     * @return {@code max |vector_i|}, zero for an empty vector
     */
    private static double maxAbs(double[] vector) {
        double max = 0.0;
        for (double value : vector) {
            max = Math.max(max, Math.abs(value));
        }
        return max;
    }
}
