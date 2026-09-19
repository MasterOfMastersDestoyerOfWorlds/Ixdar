package ixdar.geometry.mesh.quadlayout.solver;

import java.util.Arrays;

import ixdar.geometry.mesh.quadlayout.solver.chol.CholeskyBackend;
import ixdar.geometry.mesh.quadlayout.solver.chol.EjmlCholeskyFactor;
import ixdar.geometry.mesh.quadlayout.solver.chol.NativeCholeskyBackend;
import ixdar.geometry.mesh.quadlayout.solver.matrix.CompressedSparseRowArrays;
import ixdar.geometry.mesh.quadlayout.solver.matrix.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.ordering.OrderingMethod;
import ixdar.geometry.mesh.quadlayout.solver.ordering.SolverPermutation;
import ixdar.platform.Platforms;

/**
 * Mehrotra predictor-corrector for the convex QP {@code min ½x'Hx − b'x, Ax ≥ c};
 * both solves of an iteration share one factorization.
 *
 * <p>See also: Mehrotra 1992, SIAM J. Optim. 2(4):575-601, doi:10.1137/0802028;
 * Nocedal &amp; Wright, Numerical Optimization §16.6, Algorithm 16.4.
 */
public final class InteriorPointQp {

    /** Iteration cap; a round that hits it reports {@code converged == false}. */
    public static final int MAX_ITERATIONS = 50;

    /** Fraction-to-boundary factor τ keeping slacks and multipliers positive. */
    public static final double FRACTION_TO_BOUNDARY = 0.995;

    /**
     * Exponent of Mehrotra's centering heuristic {@code σ = (μ_aff/μ)³}: a blocked
     * affine step leaves σ near one, which pushes the iterate back onto the central
     * path instead of pinning its slacks to the boundary.
     */
    public static final double CENTERING_EXPONENT = 3.0;

    /** Floor on σ, so a long affine step still keeps a trace of centering. */
    public static final double MIN_CENTERING_SIGMA = 1.0e-8;

    /**
     * Lower bound on the starting slacks {@code s = max(|Ax₀ − c|, floor)}. Matching
     * a violated constraint's slack to its violation keeps the first
     * fraction-to-boundary step from collapsing to {@code s/|r_p|}.
     */
    public static final double SLACK_START_FLOOR = 1.0e-2;

    /** Starting value of every multiplier λ. */
    public static final double MULTIPLIER_START = 1.0;

    /** Cap on the condensed diagonal ratios λ/s, guarding late-iteration blowup. */
    public static final double RATIO_CAP = 1.0e12;

    /**
     * First rung of the Tikhonov ladder on the condensed diagonal, as a fraction of
     * the largest base-Hessian diagonal entry. It perturbs every Newton step, so it
     * stays off until a backend actually reports a zero pivot.
     */
    public static final double DIAGONAL_REGULARIZATION_FRACTION = 1.0e-10;

    /** Factor the regulariser is raised by when a factorization still reports a zero pivot. */
    public static final double REGULARIZATION_ESCALATION = 1.0e2;

    /** Escalations allowed before the singular system is reported to the caller. */
    public static final int MAX_REGULARIZATION_ESCALATIONS = 6;

    /** Dual-residual tolerance, relative to {@code 1 + ‖b‖∞}. */
    public static final double DUAL_TOLERANCE = 1.0e-8;

    /** Primal-residual tolerance, relative to {@code 1 + ‖c‖∞}. */
    public static final double PRIMAL_TOLERANCE = 1.0e-8;

    /** Complementarity tolerance on {@code s'λ/m}, relative to {@code 1 + ‖b‖∞}. */
    public static final double COMPLEMENTARITY_TOLERANCE = 1.0e-8;

    /**
     * Relative size below which {@code ‖A'w‖∞} counts as zero in the Farkas
     * certificate {@code w ≥ 0, A'w = 0, c'w > 0} that proves {@code Ax ≥ c} has no
     * solution.
     */
    public static final double CERTIFICATE_TOLERANCE = 1.0e-6;

    /**
     * Iterations between certificate tests. The multipliers need a few iterations to
     * separate before they can certify anything, and an infeasible set is worth
     * abandoning long before the diverging ratios wreck the condensed system.
     */
    public static final int CERTIFICATE_CHECK_INTERVAL = 10;

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

    /**
     * True iff the last solve ended with multipliers forming a Farkas certificate,
     * which proves no {@code x} satisfies the constraint set.
     */
    public boolean infeasible;

    /** {@code ‖A'w‖∞} of the normalized multipliers {@code w = λ/‖λ‖∞}. */
    public double certificateResidual;

    /** {@code c'w} of the normalized multipliers; positive completes the certificate. */
    public double certificateValue;

    /** Smallest {@code max(c − Ax)} over the iterates: the violation at {@link #bestSolution}. */
    public double maxViolation;

    /** Least-violating primal iterate seen, restored when the solve does not converge. */
    public double[] bestSolution;

    /** Scratch {@code A'w} of the certificate test. */
    public double[] certificateCombination;

    /** Final slacks s of the last solve; kept for inspection. */
    public double[] slack;

    /** Final multipliers λ; kept for inspection. */
    public double[] multiplier;

    /** Per-constraint ratio λ/s driving the condensed system, capped at {@link #RATIO_CAP}. */
    public double[] ratio;

    /** Primal residual {@code Ax − s − c} of the current iterate. */
    public double[] primalResidual;

    /** Dual residual {@code Hx − b − A'λ} of the current iterate. */
    public double[] dualResidual;

    /** {@code Hx − b}, the dual residual before the multiplier term. */
    public double[] hxMinusB;

    /**
     * Per-constraint numerator {@code μ − Δs_aff·Δλ_aff} of the complementarity
     * target; zero throughout the affine predictor.
     */
    public double[] centralTerm;

    /** Right-hand side of the condensed Newton system. */
    public double[] newtonRhs;

    /** Primal step Δx of the direction last solved for. */
    public double[] deltaX;

    /** Slack step Δs of the combined direction. */
    public double[] deltaSlack;

    /** Multiplier step Δλ of the combined direction. */
    public double[] deltaMultiplier;

    /** Slack step of the affine predictor, the corrector's second-order term. */
    public double[] affineDeltaSlack;

    /** Multiplier step of the affine predictor. */
    public double[] affineDeltaMultiplier;

    /** Newton right-hand side in the factor's permuted index space. */
    public double[] permutedRhs;

    /** Factor solution in the permuted index space. */
    public double[] permutedSolution;

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

    /** The regularized diagonal the backend factors: {@link #condensedDiagonal} plus the shift. */
    public double[] regularizedDiagonal;

    /**
     * Current regulariser fraction, zero until a backend reports a zero pivot. Left
     * on for the rest of the solve once the ladder has raised it.
     */
    public double regularizationFraction;

    /** Absolute floor the current fraction adds to every diagonal entry. */
    public double diagonalRegularization;

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
     * Run the primal-dual iteration from the warm start in {@code x}, leaving the
     * primal solution there. A solve that does not converge leaves the least-violating
     * iterate instead and sets {@link #infeasible} when the multipliers prove the
     * constraints have no solution.
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
            slack[i] = Math.max(Math.abs(constraintDot(i, x) - constraintBound[i]),
                    SLACK_START_FLOOR);
            multiplier[i] = MULTIPLIER_START;
        }
        double dualThreshold = DUAL_TOLERANCE * (1.0 + maxAbs(baseSystem.rightHandSide));
        double primalThreshold = PRIMAL_TOLERANCE * (1.0 + maxAbs(constraintBound));
        double complementarityThreshold = COMPLEMENTARITY_TOLERANCE
                * (1.0 + maxAbs(baseSystem.rightHandSide));

        ratio = new double[m];
        primalResidual = new double[m];
        centralTerm = new double[m];
        deltaSlack = new double[m];
        deltaMultiplier = new double[m];
        affineDeltaSlack = new double[m];
        affineDeltaMultiplier = new double[m];
        hxMinusB = new double[n];
        dualResidual = new double[n];
        newtonRhs = new double[n];
        deltaX = new double[n];
        permutedRhs = new double[n];
        permutedSolution = new double[n];

        iterationCount = 0;
        factorizationCount = 0;
        converged = false;
        infeasible = false;
        bestSolution = x.clone();
        certificateCombination = new double[n];
        maxViolation = Double.POSITIVE_INFINITY;
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
            double violation = 0.0;
            double complementarity = 0.0;
            for (int i = 0; i < m; i++) {
                violation = Math.max(violation, -(primalResidual[i] + slack[i]));
                complementarity += slack[i] * multiplier[i];
            }
            if (violation < maxViolation) {
                maxViolation = violation;
                System.arraycopy(x, 0, bestSolution, 0, n);
            }
            complementarity /= m;
            if (maxAbs(dualResidual) <= dualThreshold
                    && maxAbs(primalResidual) <= primalThreshold
                    && complementarity <= complementarityThreshold) {
                converged = true;
                break;
            }
            if (iteration > 0 && iteration % CERTIFICATE_CHECK_INTERVAL == 0) {
                checkInfeasibilityCertificate();
                if (infeasible) {
                    break;
                }
            }

            if (condensedUpperKeys == null) {
                buildCondensedPlan();
            }
            System.arraycopy(baseSystem.diagonal, 0, condensedDiagonal, 0, n);
            System.arraycopy(condensedBaseUpperValues, 0, condensedUpperValues, 0,
                    condensedUpperValues.length);
            for (int i = 0; i < m; i++) {
                ratio[i] = Math.min(multiplier[i] / slack[i], RATIO_CAP);
                int[] dofs = constraintDofs[i];
                double[] coefs = constraintCoefs[i];
                int pairCursor = constraintPairStart[i];
                for (int k = 0; k < dofs.length; k++) {
                    condensedDiagonal[dofs[k]] += ratio[i] * coefs[k] * coefs[k];
                    for (int j = k + 1; j < dofs.length; j++) {
                        condensedUpperValues[constraintPairSlot[pairCursor++]] += ratio[i]
                                * coefs[k] * coefs[j];
                    }
                }
            }

            regularizeDiagonal();
            factorizeRegularized(newtonRhs);
            factorizationCount++;

            Arrays.fill(centralTerm, 0.0);
            solveNewtonDirection(affineDeltaSlack, affineDeltaMultiplier);
            double affinePrimal = stepToBoundary(slack, affineDeltaSlack, 1.0);
            double affineDual = stepToBoundary(multiplier, affineDeltaMultiplier, 1.0);
            double affineComplementarity = 0.0;
            for (int i = 0; i < m; i++) {
                affineComplementarity += (slack[i] + affinePrimal * affineDeltaSlack[i])
                        * (multiplier[i] + affineDual * affineDeltaMultiplier[i]);
            }
            affineComplementarity /= m;
            double sigma = Math.min(1.0, Math.max(MIN_CENTERING_SIGMA,
                    Math.pow(affineComplementarity / complementarity, CENTERING_EXPONENT)));
            double mu = sigma * complementarity;

            for (int i = 0; i < m; i++) {
                centralTerm[i] = mu - affineDeltaSlack[i] * affineDeltaMultiplier[i];
            }
            solveNewtonDirection(deltaSlack, deltaMultiplier);
            double alphaPrimal = stepToBoundary(slack, deltaSlack, FRACTION_TO_BOUNDARY);
            double alphaDual = stepToBoundary(multiplier, deltaMultiplier, FRACTION_TO_BOUNDARY);
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
        if (!converged) {
            System.arraycopy(bestSolution, 0, x, 0, n);
            checkInfeasibilityCertificate();
        }
    }

    /**
     * Test the multipliers, normalized to {@code w = λ/‖λ‖∞}, as a Farkas
     * certificate: {@code A'w = 0} with {@code c'w > 0} means no {@code x} satisfies
     * {@code Ax ≥ c}. A point that already satisfies every constraint disproves any
     * such certificate, so a feasible best iterate skips the test outright.
     */
    private void checkInfeasibilityCertificate() {
        int m = constraintBound.length;
        double maxMultiplier = maxAbs(multiplier);
        if (maxMultiplier <= 0.0
                || maxViolation <= PRIMAL_TOLERANCE * (1.0 + maxAbs(constraintBound))) {
            return;
        }
        Arrays.fill(certificateCombination, 0.0);
        double maxCoefficient = 0.0;
        certificateValue = 0.0;
        for (int i = 0; i < m; i++) {
            double weight = multiplier[i] / maxMultiplier;
            certificateValue += weight * constraintBound[i];
            int[] dofs = constraintDofs[i];
            double[] coefs = constraintCoefs[i];
            for (int k = 0; k < dofs.length; k++) {
                certificateCombination[dofs[k]] += weight * coefs[k];
                maxCoefficient = Math.max(maxCoefficient, Math.abs(coefs[k]));
            }
        }
        certificateResidual = maxAbs(certificateCombination);
        infeasible = certificateResidual <= CERTIFICATE_TOLERANCE * maxCoefficient
                && certificateValue > CERTIFICATE_TOLERANCE * maxAbs(constraintBound);
    }

    /**
     * Solve the factored condensed system for the direction whose complementarity
     * numerator is {@link #centralTerm}, leaving Δx in {@link #deltaX}. Uses
     * {@link #ratio} in place of λ/s throughout, so the recovered Δλ satisfies the
     * same system Δx was computed from even where the cap bites.
     *
     * @param deltaSlackOut      receives the slack step Δs
     * @param deltaMultiplierOut receives the multiplier step Δλ
     */
    private void solveNewtonDirection(double[] deltaSlackOut, double[] deltaMultiplierOut) {
        int n = baseSystem.size();
        int m = constraintBound.length;
        for (int row = 0; row < n; row++) {
            newtonRhs[row] = -hxMinusB[row];
        }
        for (int i = 0; i < m; i++) {
            double rhsCoefficient = centralTerm[i] / slack[i] - ratio[i] * primalResidual[i];
            int[] dofs = constraintDofs[i];
            double[] coefs = constraintCoefs[i];
            for (int k = 0; k < dofs.length; k++) {
                newtonRhs[dofs[k]] += rhsCoefficient * coefs[k];
            }
        }
        for (int newIndex = 0; newIndex < n; newIndex++) {
            permutedRhs[newIndex] = newtonRhs[permutation[newIndex]];
        }
        factor.solve(permutedRhs, permutedSolution);
        for (int newIndex = 0; newIndex < n; newIndex++) {
            deltaX[permutation[newIndex]] = permutedSolution[newIndex];
        }
        for (int i = 0; i < m; i++) {
            deltaSlackOut[i] = constraintDot(i, deltaX) + primalResidual[i];
            deltaMultiplierOut[i] = centralTerm[i] / slack[i] - multiplier[i]
                    - ratio[i] * deltaSlackOut[i];
        }
    }

    /**
     * Largest step in {@code [0, 1]} keeping every entry of {@code value} positive,
     * scaled by the fraction-to-boundary factor.
     *
     * @param value strictly positive iterates, slacks or multipliers
     * @param step  the proposed step for {@code value}
     * @param tau   fraction of the distance to the boundary that may be taken
     * @return the step length
     */
    private static double stepToBoundary(double[] value, double[] step, double tau) {
        double alpha = 1.0;
        for (int i = 0; i < value.length; i++) {
            if (step[i] < 0.0) {
                alpha = Math.min(alpha, tau * (-value[i] / step[i]));
            }
        }
        return alpha;
    }

    /**
     * Refresh {@link #regularizedDiagonal}: each condensed entry scaled by
     * {@code 1 + regularizationFraction}, plus an absolute floor that keeps a DOF no
     * face and no constraint touches positive. The relative part outweighs the
     * cancellation a λ/s ratio near {@link #RATIO_CAP} inflicts on a row.
     */
    private void regularizeDiagonal() {
        diagonalRegularization = regularizationFraction
                * Math.max(maxAbs(baseSystem.diagonal), 1.0);
        for (int row = 0; row < condensedDiagonal.length; row++) {
            regularizedDiagonal[row] = condensedDiagonal[row] * (1.0 + regularizationFraction)
                    + diagonalRegularization;
        }
    }

    /**
     * Factor the regularized condensed system, cold on the first iteration and
     * values-only afterwards. A zero pivot raises the regulariser by
     * {@link #REGULARIZATION_ESCALATION} and retries from a fresh factorization.
     *
     * @param newtonRhs current Newton right-hand side, passed to a cold factorization
     * @throws SingularSystemException when the system is still singular after
     *                                 {@link #MAX_REGULARIZATION_ESCALATIONS} escalations
     */
    private void factorizeRegularized(double[] newtonRhs) {
        for (int attempt = 0;; attempt++) {
            try {
                if (factor == null) {
                    factorizeCondensed(newtonRhs);
                } else {
                    for (int position = 0; position < factorValues.length; position++) {
                        int source = factorValueSource[position];
                        factorValues[position] = source < 0
                                ? regularizedDiagonal[-source - 1]
                                : condensedUpperValues[source];
                    }
                    factor.refactorize(factorValues);
                }
                return;
            } catch (SingularSystemException singular) {
                if (attempt == MAX_REGULARIZATION_ESCALATIONS) {
                    throw singular;
                }
                if (factor != null) {
                    factor.release();
                    factor = null;
                }
                regularizationFraction = Math.max(regularizationFraction * REGULARIZATION_ESCALATION,
                        DIAGONAL_REGULARIZATION_FRACTION);
                regularizeDiagonal();
                Platforms.log("[interior-point] singular condensed system (%s);"
                        + " regularisation fraction raised to %.3e%n", singular.getMessage(),
                        regularizationFraction);
            }
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
        regularizedDiagonal = new double[n];
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
        NormalMatrix condensed = new NormalMatrix(regularizedDiagonal, condensedUpperKeys,
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
