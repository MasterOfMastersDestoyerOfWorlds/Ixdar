package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.solver.system.DofSystem;
import ixdar.geometry.mesh.quadlayout.solver.system.NewtonRelaxation;

/**
 * The Newton line search must grow its step out of a collapsed barrier instead of
 * taking the capped full step, which only ever divides such an energy by 0.5625.
 */
class NewtonRelaxationStepGrowthTest {

    /** Coordinates of the separable barrier the relaxation is run on. */
    private static final int COORDINATE_COUNT = 4;

    /** Start of every coordinate: deep inside the {@code 1/x²} barrier. */
    private static final double COLLAPSED_START = 1.0e-4;

    /** Energy of one coordinate at its minimum {@code x = 1}. */
    private static final double MINIMUM_PER_COORDINATE = 2.0;

    /** Relative agreement demanded with the analytic minimum. */
    private static final double ENERGY_TOLERANCE = 1.0e-6;

    /**
     * Iterations the capped step would need from {@link #COLLAPSED_START}: it
     * multiplies each coordinate by 4/3, so escaping four decades takes over thirty.
     */
    private static final int CAPPED_STEP_ITERATIONS = 30;

    /** Iterations {@link #relaxCollapsedBarrier} is allowed before it counts as slow. */
    private static final int ITERATION_BUDGET = 12;

    /** Iterations the stop-reason run is capped at. */
    private static final int TRUNCATED_ITERATIONS = 2;

    /** Iterations the free run may use, well past what the capped step would need. */
    private static final int GENEROUS_ITERATIONS = 200;

    @Test
    void growingStepEscapesTheBarrierFasterThanTheCappedStep() {
        NewtonRelaxation newton = relaxCollapsedBarrier(GENEROUS_ITERATIONS);
        assertEquals(COORDINATE_COUNT * MINIMUM_PER_COORDINATE, newton.energyAfter,
                ENERGY_TOLERANCE * COORDINATE_COUNT * MINIMUM_PER_COORDINATE,
                "the relaxation did not reach the barrier's analytic minimum");
        assertTrue(newton.iterationCount <= ITERATION_BUDGET,
                "the step never grew: " + newton.iterationCount + " iterations, where the capped"
                        + " step needs about " + CAPPED_STEP_ITERATIONS);
        for (double coordinate : newton.dofs.solution) {
            assertTrue(coordinate > 0.0, "a coordinate crossed the barrier to " + coordinate);
        }
    }
    
    /**
     * Runs a damped Newton on the separable barrier {@code Σ xᵢ² + 1/xᵢ²} from a
     * collapsed start, with the step bound that keeps every coordinate positive.
     *
     * @param maxIterations iterations the relaxation may run
     * @return the finished relaxation, for its result fields
     */
    private NewtonRelaxation relaxCollapsedBarrier(int maxIterations) {
        DofSystem dofs = new DofSystem(COORDINATE_COUNT);
        for (int coordinate = 0; coordinate < COORDINATE_COUNT; coordinate++) {
            dofs.solution[coordinate] = COLLAPSED_START;
        }
        dofs.energy = NewtonRelaxationStepGrowthTest::barrierEnergy;
        NewtonRelaxation newton = new NewtonRelaxation(dofs,
                NewtonRelaxationStepGrowthTest::assembleBarrier, new long[0],
                NewtonRelaxationStepGrowthTest::positiveStepLimit);
        newton.maxIterations = maxIterations;
        newton.run();
        return newton;
    }

    /**
     * The separable barrier energy, infinite where a coordinate has crossed zero.
     *
     * @param x candidate coordinates
     * @return the summed energy
     */
    private static double barrierEnergy(double[] x) {
        double total = 0.0;
        for (double coordinate : x) {
            if (coordinate <= 0.0) {
                return Double.POSITIVE_INFINITY;
            }
            total += coordinate * coordinate + 1.0 / (coordinate * coordinate);
        }
        return total;
    }

    /**
     * The barrier's diagonal Hessian and negative gradient at x.
     *
     * @param x             candidate coordinates
     * @param diagonal      receives the second derivatives
     * @param upperValues   unused; the system has no off-diagonal entries
     * @param rightHandSide receives the negative first derivatives
     */
    private static void assembleBarrier(double[] x, double[] diagonal, double[] upperValues,
            double[] rightHandSide) {
        for (int coordinate = 0; coordinate < x.length; coordinate++) {
            double value = x[coordinate];
            double square = value * value;
            diagonal[coordinate] = 2.0 + 6.0 / (square * square);
            rightHandSide[coordinate] = -(2.0 * value - 2.0 / (square * value));
        }
    }

    /**
     * The largest step from x along delta that keeps every coordinate positive.
     *
     * @param x     current coordinates
     * @param delta the Newton displacement
     * @return the maximal step, infinite when no coordinate decreases
     */
    private static double positiveStepLimit(double[] x, double[] delta) {
        double limit = Double.POSITIVE_INFINITY;
        for (int coordinate = 0; coordinate < x.length; coordinate++) {
            if (delta[coordinate] < 0.0) {
                limit = Math.min(limit, -x[coordinate] / delta[coordinate]);
            }
        }
        return limit;
    }
}
