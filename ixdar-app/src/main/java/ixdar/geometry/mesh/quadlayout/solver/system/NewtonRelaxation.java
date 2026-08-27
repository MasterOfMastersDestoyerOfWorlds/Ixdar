package ixdar.geometry.mesh.quadlayout.solver.system;

import java.util.Arrays;

import ixdar.geometry.mesh.quadlayout.solver.DirectSolver;
import ixdar.geometry.mesh.quadlayout.solver.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.solver.OrderingMethod;
import ixdar.platform.Platforms;

/**
 * Damped Newton over a {@link DofSystem}: assemble at the current solution,
 * solve for the step through a retained Cholesky factor, line-search under an
 * Armijo test, repeat until converged. A quadratic objective converges in one
 * step.
 *
 * <p>See also: SPH17 Section 5, RPP17 Section 3
 */
public final class NewtonRelaxation {

    /** Relative energy drop below which an iteration counts as stalled. */
    public static final double CONVERGENCE = 1.0e-4;

    /** Consecutive stalled iterations before the relaxation stops. */
    public static final int STALL_LIMIT = 3;

    /** Line-search backtracking factor. */
    public static final double BACKTRACK = 0.5;

    /** Line-search backtracks before the step is abandoned. */
    public static final int MAX_BACKTRACKS = 5;

    /** Armijo sufficient-decrease slope. */
    public static final double ARMIJO_SLOPE = 1.0e-4;

    /** Fraction of the maximal non-inverting step the search starts from. */
    public static final double MAX_STEP_MARGIN = 0.8;

    /** Smallest ridge added to the Hessian diagonal. */
    public static final double RIDGE_FLOOR = 1.0e-12;

    /** Ridge relative to the largest Hessian diagonal entry. */
    public static final double RIDGE_RELATIVE = 1.0e-10;

    /** Iterations between progress lines when {@link #verboseIterations} is off. */
    public static final int ITERATION_LOG_STRIDE = 1;

    public final DofSystem dofs;

    /** Fills the Hessian and negative gradient at a solution, on a fixed sparsity. */
    public final SystemAssembler assembler;

    /** The fixed strict-upper sparsity, packed {@code (row << 32) | column}. */
    public final long[] upperKeys;

    /** Largest step that stays feasible; null means unbounded. */
    public final StepLimit stepLimit;

    /** Newton iterations to run; {@link #timeBudgetMilliseconds} usually stops earlier. */
    public int maxIterations = 10_000;

    /** Wall-clock budget for the whole relaxation. */
    public long timeBudgetMilliseconds = 60_000;

    /** Whether every iteration logs a progress line. */
    public boolean verboseIterations;

    public double energyBefore;
    public double energyAfter;
    public double acceptedStep;
    public double gradientDotDirection;
    public double lastAlphaMax;
    public int iterationCount;
    public int stallCount;

    /** Fills the reduced Hessian and negative gradient at a candidate solution. */
    public interface SystemAssembler {

        /**
         * Accumulates the system at x into the given arrays, all pre-zeroed by
         * the caller except {@code diagonal}/{@code rightHandSide} which the
         * assembler must overwrite fully.
         *
         * @param x             candidate solution
         * @param diagonal      receives the Hessian diagonal
         * @param upperValues   receives strict-upper values matching the keys
         * @param rightHandSide receives the negative gradient
         */
        void assemble(double[] x, double[] diagonal, double[] upperValues, double[] rightHandSide);
    }

    /** The largest feasible step along a direction. */
    public interface StepLimit {

        /**
         * The largest step from x along delta that stays feasible.
         *
         * @param x     current solution
         * @param delta the Newton direction
         * @return the maximal step, infinite when unbounded
         */
        double maxStep(double[] x, double[] delta);
    }

    /**
     * Stores the system and its Newton hooks.
     *
     * @param dofs      the DOF system relaxed in place
     * @param assembler Hessian/gradient assembly on the fixed sparsity
     * @param upperKeys the fixed strict-upper sparsity
     * @param stepLimit feasible-step bound, or null for unbounded
     */
    public NewtonRelaxation(DofSystem dofs, SystemAssembler assembler, long[] upperKeys,
            StepLimit stepLimit) {
        this.dofs = dofs;
        this.assembler = assembler;
        this.upperKeys = upperKeys;
        this.stepLimit = stepLimit;
    }

    /**
     * Runs the relaxation, mutating {@code dofs.solution} in place and pushing
     * the result through the system's write-back hook when one is set.
     */
    public void run() {
        int size = dofs.dofCount;
        double[] diagonal = new double[size];
        double[] rightHandSide = new double[size];
        double[] upperValues = new double[upperKeys.length];
        double[] heldStart = new double[size];
        NormalMatrix matrix = null;
        DirectSolver.CholeskyHandle handle = null;
        int[] valueSources = null;
        double[] factorValues = null;
        energyBefore = dofs.energy();
        double energy = energyBefore;
        long startedAt = System.currentTimeMillis();
        for (int iteration = 0; iteration < maxIterations
                && System.currentTimeMillis() - startedAt < timeBudgetMilliseconds; iteration++) {
            Arrays.fill(diagonal, 0.0);
            Arrays.fill(rightHandSide, 0.0);
            Arrays.fill(upperValues, 0.0);
            assembler.assemble(dofs.solution, diagonal, upperValues, rightHandSide);
            double largestDiagonal = 0.0;
            for (double value : diagonal) {
                largestDiagonal = Math.max(largestDiagonal, value);
            }
            double ridge = Math.max(RIDGE_FLOOR, RIDGE_RELATIVE * largestDiagonal);
            for (int index = 0; index < size; index++) {
                diagonal[index] += ridge;
            }
            if (matrix == null) {
                matrix = new NormalMatrix(diagonal, upperKeys, upperValues, rightHandSide);
                handle = DirectSolver.factorize(matrix, dofs.frozen, OrderingMethod.AMD);
                valueSources = DirectSolver.valueSources(handle, matrix, dofs.frozen, upperKeys);
                factorValues = new double[valueSources.length];
            } else {
                matrix.refreshValues(diagonal, upperValues, rightHandSide);
                DirectSolver.refactorizeHandleValues(handle, matrix.diagonal, upperValues,
                        valueSources, factorValues);
            }
            double[] delta = new double[size];
            DirectSolver.solveCompact(handle, matrix, rightHandSide, delta, heldStart, dofs.frozen);
            gradientDotDirection = 0.0;
            for (int index = 0; index < size; index++) {
                gradientDotDirection -= rightHandSide[index] * delta[index];
            }
            double stepped = takeStep(matrix, delta, energy);
            iterationCount++;
            if (verboseIterations || iteration % ITERATION_LOG_STRIDE == 0) {
                Platforms.log("[newton]   iteration %d energy %.6e (%.3f%%) step=%.3e"
                        + " alphaMax=%.3e%n", iteration, stepped,
                        100.0 * (energy - stepped) / Math.max(1.0e-30, energy), acceptedStep,
                        lastAlphaMax);
            }
            boolean noProgress = stepped >= energy * (1.0 - CONVERGENCE);
            stallCount = noProgress ? stallCount + 1 : 0;
            energy = stepped;
            if (acceptedStep == 0.0 || stallCount >= STALL_LIMIT) {
                break;
            }
        }
        if (handle != null) {
            DirectSolver.releaseHandle(handle);
        }
        energyAfter = energy;
        if (dofs.writeBack != null) {
            dofs.writeBack.run();
        }
    }

    /**
     * Moves along the Newton direction from {@code min(1, 0.8 * alphaMax)},
     * backtracking under the Armijo test; accepts into {@code dofs.solution}.
     *
     * @param matrix  the assembled system, for the quadratic-energy fallback
     * @param delta   the Newton displacement of every coordinate
     * @param current the energy being improved on
     * @return the energy reached
     */
    private double takeStep(NormalMatrix matrix, double[] delta, double current) {
        double alphaMax = stepLimit != null
                ? stepLimit.maxStep(dofs.solution, delta)
                : Double.POSITIVE_INFINITY;
        lastAlphaMax = alphaMax;
        double step = Math.min(1.0, MAX_STEP_MARGIN * alphaMax);
        if (!(step > 0.0)) {
            acceptedStep = 0.0;
            return current;
        }
        double[] trial = new double[dofs.dofCount];
        for (int backtrack = 0; backtrack < MAX_BACKTRACKS; backtrack++) {
            for (int index = 0; index < dofs.dofCount; index++) {
                trial[index] = dofs.solution[index] + step * delta[index];
            }
            double trialEnergy = dofs.energy != null
                    ? dofs.energy.energy(trial)
                    : matrix.quadraticEnergy(trial);
            if (trialEnergy <= current + ARMIJO_SLOPE * step * gradientDotDirection) {
                System.arraycopy(trial, 0, dofs.solution, 0, dofs.dofCount);
                acceptedStep = step;
                return trialEnergy;
            }
            step *= BACKTRACK;
        }
        acceptedStep = 0.0;
        return current;
    }
}
