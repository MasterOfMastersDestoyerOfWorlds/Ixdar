package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.SymmetricDirichletEnergy;

/**
 * The optimizer's element kernel against finite differences: the analytic gradient and raw
 * Hessian must match central differences of the energy, and the projected Hessian must be
 * positive semi-definite, because the Newton assembly and the line search both trust them.
 */
class SymmetricDirichletEnergyTest {

    /** Central-difference step, balanced between truncation and cancellation. */
    private static final double STEP = 1.0e-6;

    /** Relative tolerance for a finite-difference match. */
    private static final double TOLERANCE = 1.0e-4;

    /**
     * Gradient operator of a unit right reference triangle {@code (0,0),(1,0),(0,1)}: row one is
     * {@code d/du}, row two {@code d/dv} over the three corners.
     */
    private static final double[] OPERATOR = { -1.0, 1.0, 0.0, -1.0, 0.0, 1.0 };

    /** Reference area of that triangle. */
    private static final double AREA = 0.5;

    /** A positively oriented, visibly distorted target. */
    private static final double[] TARGET_X = { 0.05, 1.3, 0.2 };

    /** Second coordinates of the same target. */
    private static final double[] TARGET_Y = { -0.02, -0.1, 0.9 };

    @Test
    void energyOnlyMatchesEvaluate() {
        SymmetricDirichletEnergy element = new SymmetricDirichletEnergy();
        element.evaluate(OPERATOR, AREA, TARGET_X.clone(), TARGET_Y.clone());
        double energyOnly = element.energyOnly(OPERATOR, AREA, TARGET_X.clone(), TARGET_Y.clone());
        assertEquals(element.energy, energyOnly, TOLERANCE * Math.abs(energyOnly),
                "evaluate and energyOnly must agree at the same point");
    }

    @Test
    void gradientMatchesFiniteDifferences() {
        SymmetricDirichletEnergy element = new SymmetricDirichletEnergy();
        element.evaluate(OPERATOR, AREA, TARGET_X.clone(), TARGET_Y.clone());
        double[] analytic = element.gradient.clone();
        for (int variable = 0; variable < SymmetricDirichletEnergy.VARIABLES; variable++) {
            double difference = (perturbedEnergy(element, variable, STEP)
                    - perturbedEnergy(element, variable, -STEP)) / (2.0 * STEP);
            assertEquals(analytic[variable], difference,
                    TOLERANCE * Math.max(1.0, Math.abs(difference)),
                    "gradient entry " + variable);
        }
    }

    @Test
    void rawHessianMatchesFiniteDifferencesOfTheGradient() {
        SymmetricDirichletEnergy element = new SymmetricDirichletEnergy();
        element.projectHessian = false;
        element.evaluate(OPERATOR, AREA, TARGET_X.clone(), TARGET_Y.clone());
        double[][] analytic = new double[SymmetricDirichletEnergy.VARIABLES][];
        for (int row = 0; row < SymmetricDirichletEnergy.VARIABLES; row++) {
            analytic[row] = element.hessian[row].clone();
        }
        for (int column = 0; column < SymmetricDirichletEnergy.VARIABLES; column++) {
            double[] forward = perturbedGradient(element, column, STEP);
            double[] backward = perturbedGradient(element, column, -STEP);
            for (int row = 0; row < SymmetricDirichletEnergy.VARIABLES; row++) {
                double difference = (forward[row] - backward[row]) / (2.0 * STEP);
                assertEquals(analytic[row][column], difference,
                        TOLERANCE * Math.max(1.0, Math.abs(difference)),
                        "hessian entry (" + row + ", " + column + ")");
            }
        }
    }

    @Test
    void projectedHessianIsPositiveSemiDefinite() {
        SymmetricDirichletEnergy element = new SymmetricDirichletEnergy();
        element.evaluate(OPERATOR, AREA, TARGET_X.clone(), TARGET_Y.clone());
        double[] probe = new double[SymmetricDirichletEnergy.VARIABLES];
        for (int seed = 0; seed < SymmetricDirichletEnergy.VARIABLES; seed++) {
            for (int index = 0; index < probe.length; index++) {
                probe[index] = Math.sin(seed * probe.length + index + 1.0);
            }
            double quadraticForm = 0.0;
            for (int row = 0; row < probe.length; row++) {
                for (int column = 0; column < probe.length; column++) {
                    quadraticForm += probe[row] * element.hessian[row][column] * probe[column];
                }
            }
            assertTrue(quadraticForm >= -TOLERANCE,
                    "projected Hessian must be positive semi-definite, got vᵀHv=" + quadraticForm);
        }
    }

    /**
     * The energy with one variable displaced, variables ordered {@code x0..x2, y0..y2}.
     *
     * @param element      evaluator to reuse
     * @param variable     variable to displace
     * @param displacement signed displacement
     * @return the energy at the displaced point
     */
    private double perturbedEnergy(SymmetricDirichletEnergy element, int variable,
            double displacement) {
        double[] targetX = TARGET_X.clone();
        double[] targetY = TARGET_Y.clone();
        if (variable < SymmetricDirichletEnergy.TRIANGLE_CORNERS) {
            targetX[variable] += displacement;
        } else {
            targetY[variable - SymmetricDirichletEnergy.TRIANGLE_CORNERS] += displacement;
        }
        return element.energyOnly(OPERATOR, AREA, targetX, targetY);
    }

    /**
     * The analytic gradient with one variable displaced.
     *
     * @param element      evaluator to reuse; its projection flag is respected
     * @param variable     variable to displace
     * @param displacement signed displacement
     * @return a copy of the gradient at the displaced point
     */
    private double[] perturbedGradient(SymmetricDirichletEnergy element, int variable,
            double displacement) {
        double[] targetX = TARGET_X.clone();
        double[] targetY = TARGET_Y.clone();
        if (variable < SymmetricDirichletEnergy.TRIANGLE_CORNERS) {
            targetX[variable] += displacement;
        } else {
            targetY[variable - SymmetricDirichletEnergy.TRIANGLE_CORNERS] += displacement;
        }
        element.evaluate(OPERATOR, AREA, targetX, targetY);
        return element.gradient.clone();
    }
}
