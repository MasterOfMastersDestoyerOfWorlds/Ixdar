package ixdar.geometry.mesh.quadlayout.solver;

import java.util.Arrays;

import ixdar.geometry.mesh.quadlayout.solver.chol.CholeskyBackend;
import ixdar.geometry.mesh.quadlayout.solver.matrix.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.ordering.OrderingMethod;

/**
 * Recovers a singular system's null vector by inverse iteration on {@code A + σI} and reads the
 * cause off its support. The caller supplies the DOF-to-chart and DOF-to-vertex maps.
 */
public final class SingularSystemDiagnoser {

    /** Inverse-iteration steps; the eigenvalue gap is ten orders, so a handful converges. */
    public static final int INVERSE_ITERATIONS = 24;

    /** Fraction of the largest null-vector component an entry must reach to count as support. */
    public static final double SUPPORT_FRACTION = 1.0e-3;

    /** Relative spread under which a null vector counts as constant over its support. */
    public static final double CONSTANT_TOLERANCE = 1.0e-6;

    /** Mixing multiplier of the deterministic start vector, so no run picks a different one. */
    private static final long START_MIX_MULTIPLIER = 0x9E3779B97F4A7C15L;

    /** Second mixing multiplier of the deterministic start vector. */
    private static final long START_MIX_SCRAMBLE = 0xFF51AFD7ED558CCDL;

    /** Bits discarded when a mixed long becomes a double in {@code [0, 1)}. */
    private static final int START_MIX_SHIFT = 11;

    /** Fold width of the start vector's mixing steps. */
    private static final int START_MIX_FOLD = 33;

    /** Scale turning the kept mantissa bits into a double in {@code [0, 1)}. */
    private static final double MANTISSA_SCALE = 0x1.0p-53;

    private SingularSystemDiagnoser() {
    }

    /**
     * Diagnose a singular system: recover its null vector, record the support, and classify.
     *
     * @param matrix               the system a backend refused, unshifted; restored before return
     * @param fixed                per-variable fixed flag of the failing factorization
     * @param pivotIndex           the backend's zero-pivot row, or
     *                             {@link SingularSystemException#UNKNOWN_PIVOT}
     * @param appliedDiagonalShift shift the backend ladder used, or zero to derive one here
     * @param dofChartId           per DOF: owning chart, or -1; null when the caller has no charts
     * @param dofVertexId          per DOF: mesh vertex, or -1; null when the caller has no mesh
     * @param vertexPositionXyz    per vertex id: three coordinates; null when the caller has none
     * @param vertexIsDegenerate   per vertex id: on a (near) zero-area face; null when unknown
     * @return the filled diagnosis
     */
    public static SingularSystemDiagnosis diagnose(NormalMatrix matrix, boolean[] fixed,
            int pivotIndex, double appliedDiagonalShift, int[] dofChartId, int[] dofVertexId,
            double[] vertexPositionXyz, boolean[] vertexIsDegenerate) {
        SingularSystemDiagnosis diagnosis = new SingularSystemDiagnosis();
        diagnosis.pivotIndex = pivotIndex;
        int variableCount = matrix.variableCount;
        int freeCount = 0;
        for (int variable = 0; variable < variableCount; variable++) {
            if (!fixed[variable]) {
                freeCount++;
            }
        }
        diagnosis.dimension = freeCount;
        if (freeCount == 0) {
            return diagnosis;
        }
        double shift = appliedDiagonalShift > 0.0 ? appliedDiagonalShift
                : CholeskyBackend.diagonalShiftFor(matrix.diagonal, fixed);
        diagnosis.appliedDiagonalShift = shift;

        double[] offendingDirection = nonFiniteIndicator(matrix, fixed);
        diagnosis.nonFiniteDofCount = countSupport(offendingDirection, fixed);
        if (diagnosis.nonFiniteDofCount == 0) {
            offendingDirection = inverseIterate(matrix, fixed, shift);
            diagnosis.nullVectorEnergy = rayleighQuotient(matrix, fixed, offendingDirection);
        }
        recordSupport(diagnosis, offendingDirection, fixed, dofChartId, dofVertexId,
                vertexPositionXyz, vertexIsDegenerate);
        diagnosis.classification = classify(diagnosis);
        return diagnosis;
    }

    /**
     * A unit indicator of the free rows carrying a non-finite value. Such a row is why a backend
     * saw a zero pivot, and inverse iteration on it would only produce more NaN.
     *
     * @param matrix the system a backend refused
     * @param fixed  per-variable fixed flag
     * @return one entry per variable: 1 on a row holding NaN or an infinity, else 0
     */
    private static double[] nonFiniteIndicator(NormalMatrix matrix, boolean[] fixed) {
        double[] indicator = new double[matrix.variableCount];
        for (int row = 0; row < matrix.variableCount; row++) {
            if (fixed[row]) {
                continue;
            }
            boolean broken = !Double.isFinite(matrix.diagonal[row])
                    || !Double.isFinite(matrix.rightHandSide[row]);
            for (int entry = matrix.rowStart[row]; !broken
                    && entry < matrix.rowStart[row + 1]; entry++) {
                broken = !Double.isFinite(matrix.rowValue[entry]);
            }
            indicator[row] = broken ? 1.0 : 0.0;
        }
        return indicator;
    }

    /**
     * Count the free entries of a vector that clear the support threshold.
     *
     * @param vector the vector to measure, already scaled to a unit largest component
     * @param fixed  per-variable fixed flag
     * @return the number of free entries at or above {@link #SUPPORT_FRACTION}
     */
    private static int countSupport(double[] vector, boolean[] fixed) {
        int count = 0;
        for (int variable = 0; variable < vector.length; variable++) {
            if (!fixed[variable] && vector[variable] >= SUPPORT_FRACTION) {
                count++;
            }
        }
        return count;
    }

    /**
     * Inverse iteration on {@code A + σI}, started from a deterministic sign-mixed vector so a
     * null direction orthogonal to the constant vector is still reached.
     *
     * @param matrix the system, restored to its input values before return
     * @param fixed  per-variable fixed flag
     * @param shift  the diagonal shift σ
     * @return the converged eigenvector, scaled to a unit largest component
     */
    private static double[] inverseIterate(NormalMatrix matrix, boolean[] fixed, double shift) {
        int variableCount = matrix.variableCount;
        double[] originalDiagonal = matrix.diagonal.clone();
        double[] originalRightHandSide = matrix.rightHandSide.clone();
        for (int variable = 0; variable < variableCount; variable++) {
            if (!fixed[variable]) {
                matrix.diagonal[variable] += shift;
            }
        }
        double[] iterate = new double[variableCount];
        for (int variable = 0; variable < variableCount; variable++) {
            if (fixed[variable]) {
                continue;
            }
            long bits = (variable + 1L) * START_MIX_MULTIPLIER;
            bits ^= bits >>> START_MIX_FOLD;
            bits *= START_MIX_SCRAMBLE;
            bits ^= bits >>> START_MIX_FOLD;
            iterate[variable] = 2.0 * ((bits >>> START_MIX_SHIFT) * MANTISSA_SCALE) - 1.0;
        }
        double[] zeroStart = new double[variableCount];
        DirectSolver.CholeskyHandle handle =
                DirectSolver.factorize(matrix, fixed, OrderingMethod.AMD);
        try {
            for (int step = 0; step < INVERSE_ITERATIONS; step++) {
                System.arraycopy(iterate, 0, matrix.rightHandSide, 0, variableCount);
                DirectSolver.solveCompact(handle, matrix, matrix.rightHandSide, iterate,
                        zeroStart, fixed);
                double largest = 0.0;
                for (int variable = 0; variable < variableCount; variable++) {
                    if (!fixed[variable]) {
                        largest = Math.max(largest, Math.abs(iterate[variable]));
                    }
                }
                if (largest == 0.0 || !Double.isFinite(largest)) {
                    break;
                }
                for (int variable = 0; variable < variableCount; variable++) {
                    iterate[variable] = fixed[variable] ? 0.0 : iterate[variable] / largest;
                }
            }
        } finally {
            DirectSolver.releaseHandle(handle);
            System.arraycopy(originalDiagonal, 0, matrix.diagonal, 0, variableCount);
            System.arraycopy(originalRightHandSide, 0, matrix.rightHandSide, 0, variableCount);
        }
        return iterate;
    }

    /**
     * The energy the system still sees along a direction, {@code zᵀAz / zᵀz} over the free
     * variables; a true null direction scores near zero.
     *
     * @param matrix the unshifted system
     * @param fixed  per-variable fixed flag
     * @param vector the direction to test
     * @return the Rayleigh quotient, or zero when the vector vanishes
     */
    private static double rayleighQuotient(NormalMatrix matrix, boolean[] fixed, double[] vector) {
        double numerator = 0.0;
        double denominator = 0.0;
        for (int row = 0; row < matrix.variableCount; row++) {
            if (fixed[row]) {
                continue;
            }
            double product = matrix.diagonal[row] * vector[row];
            for (int entry = matrix.rowStart[row]; entry < matrix.rowStart[row + 1]; entry++) {
                int column = matrix.rowColumn[entry];
                if (!fixed[column]) {
                    product += matrix.rowValue[entry] * vector[column];
                }
            }
            numerator += vector[row] * product;
            denominator += vector[row] * vector[row];
        }
        return denominator == 0.0 ? 0.0 : numerator / denominator;
    }

    /**
     * Fill the diagnosis's parallel support arrays from the offending direction, keeping the
     * largest entries when it runs past {@link SingularSystemDiagnosis#MAX_RECORDED_SUPPORT}.
     *
     * @param diagnosis          diagnosis to fill
     * @param nullVector         the recovered null direction, or the non-finite-row indicator
     * @param fixed              per-variable fixed flag
     * @param dofChartId         per DOF: owning chart, or null
     * @param dofVertexId        per DOF: mesh vertex, or null
     * @param vertexPositionXyz  per vertex id: three coordinates, or null
     * @param vertexIsDegenerate per vertex id: on a (near) zero-area face, or null
     */
    private static void recordSupport(SingularSystemDiagnosis diagnosis, double[] nullVector,
            boolean[] fixed, int[] dofChartId, int[] dofVertexId, double[] vertexPositionXyz,
            boolean[] vertexIsDegenerate) {
        int variableCount = nullVector.length;
        double largest = 0.0;
        for (int variable = 0; variable < variableCount; variable++) {
            if (!fixed[variable]) {
                largest = Math.max(largest, Math.abs(nullVector[variable]));
            }
        }
        if (largest == 0.0) {
            return;
        }
        double threshold = SUPPORT_FRACTION * largest;
        int supportSize = 0;
        for (int variable = 0; variable < variableCount; variable++) {
            if (!fixed[variable] && Math.abs(nullVector[variable]) >= threshold) {
                supportSize++;
            }
        }
        diagnosis.supportSize = supportSize;

        final int[] candidates = new int[supportSize];
        int cursor = 0;
        for (int variable = 0; variable < variableCount; variable++) {
            if (!fixed[variable] && Math.abs(nullVector[variable]) >= threshold) {
                candidates[cursor] = variable;
                cursor++;
            }
        }
        int[] ranked = candidates;
        if (supportSize > SingularSystemDiagnosis.MAX_RECORDED_SUPPORT) {
            Integer[] order = new Integer[supportSize];
            for (int entry = 0; entry < supportSize; entry++) {
                order[entry] = entry;
            }
            Arrays.sort(order, (a, b) -> {
                int byMagnitude = Double.compare(Math.abs(nullVector[candidates[b]]),
                        Math.abs(nullVector[candidates[a]]));
                return byMagnitude != 0 ? byMagnitude
                        : Integer.compare(candidates[a], candidates[b]);
            });
            int[] kept = new int[SingularSystemDiagnosis.MAX_RECORDED_SUPPORT];
            for (int entry = 0; entry < kept.length; entry++) {
                kept[entry] = candidates[order[entry]];
            }
            Arrays.sort(kept);
            ranked = kept;
        }

        int recorded = ranked.length;
        diagnosis.supportDof = ranked;
        diagnosis.supportWeight = new double[recorded];
        diagnosis.supportChartId = new int[recorded];
        diagnosis.supportVertexId = new int[recorded];
        diagnosis.supportVertexPosition =
                new double[recorded * SingularSystemDiagnosis.POSITION_COMPONENTS];
        for (int entry = 0; entry < recorded; entry++) {
            int dof = ranked[entry];
            diagnosis.supportWeight[entry] = nullVector[dof] / largest;
            diagnosis.supportChartId[entry] = dofChartId == null ? -1 : dofChartId[dof];
            int vertexId = dofVertexId == null ? -1 : dofVertexId[dof];
            diagnosis.supportVertexId[entry] = vertexId;
            if (vertexId >= 0 && vertexPositionXyz != null) {
                int base = vertexId * SingularSystemDiagnosis.POSITION_COMPONENTS;
                System.arraycopy(vertexPositionXyz, base, diagnosis.supportVertexPosition,
                        entry * SingularSystemDiagnosis.POSITION_COMPONENTS,
                        SingularSystemDiagnosis.POSITION_COMPONENTS);
            }
        }

        int degenerateCount = 0;
        for (int entry = 0; entry < recorded; entry++) {
            int vertexId = diagnosis.supportVertexId[entry];
            if (vertexId >= 0 && vertexIsDegenerate != null && vertexIsDegenerate[vertexId]) {
                degenerateCount++;
            }
        }
        int[] charts = diagnosis.supportChartId.clone();
        Arrays.sort(charts);
        int distinctCharts = 0;
        for (int entry = 0; entry < charts.length; entry++) {
            if (entry == 0 || charts[entry] != charts[entry - 1]) {
                distinctCharts++;
            }
        }
        diagnosis.distinctChartCount = distinctCharts;
        diagnosis.degenerateVertexCount = degenerateCount;
    }

    /**
     * Read the cause off the support: mostly degenerate vertices means slivers, one chart moving
     * as a rigid whole means an untied chart, and a signed pair means a redundant row.
     *
     * @param diagnosis diagnosis whose support arrays are already filled
     * @return one of the {@code CLASSIFICATION_*} constants
     */
    private static int classify(SingularSystemDiagnosis diagnosis) {
        int recorded = diagnosis.supportDof.length;
        if (recorded == 0) {
            return SingularSystemDiagnosis.CLASSIFICATION_UNKNOWN;
        }
        if (diagnosis.nonFiniteDofCount > 0) {
            return diagnosis.degenerateVertexCount * 2 >= recorded
                    ? SingularSystemDiagnosis.CLASSIFICATION_SLIVER_CLUSTER
                    : SingularSystemDiagnosis.CLASSIFICATION_UNKNOWN;
        }
        if (diagnosis.degenerateVertexCount * 2 >= recorded) {
            return SingularSystemDiagnosis.CLASSIFICATION_SLIVER_CLUSTER;
        }
        double smallest = Double.POSITIVE_INFINITY;
        double largest = 0.0;
        boolean anyPositive = false;
        boolean anyNegative = false;
        for (int entry = 0; entry < recorded; entry++) {
            double weight = diagnosis.supportWeight[entry];
            smallest = Math.min(smallest, Math.abs(weight));
            largest = Math.max(largest, Math.abs(weight));
            anyPositive |= weight > 0.0;
            anyNegative |= weight < 0.0;
        }
        boolean constant = largest - smallest <= CONSTANT_TOLERANCE * largest
                && !(anyPositive && anyNegative);
        if (diagnosis.distinctChartCount == 1 && diagnosis.supportChartId[0] >= 0
                && recorded >= 2 && constant) {
            return SingularSystemDiagnosis.CLASSIFICATION_CHART_GAUGE;
        }
        if (anyPositive && anyNegative) {
            return SingularSystemDiagnosis.CLASSIFICATION_DUPLICATE_ROW;
        }
        if (diagnosis.distinctChartCount == 1 && diagnosis.supportChartId[0] >= 0) {
            return SingularSystemDiagnosis.CLASSIFICATION_CHART_GAUGE;
        }
        return recorded == 1
                ? SingularSystemDiagnosis.CLASSIFICATION_DUPLICATE_ROW
                : SingularSystemDiagnosis.CLASSIFICATION_UNKNOWN;
    }
}
