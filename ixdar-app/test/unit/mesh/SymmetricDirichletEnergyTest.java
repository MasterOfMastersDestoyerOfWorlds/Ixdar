package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.gridmap.SymmetricDirichletEnergy;

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

    /** Rotation angle where both singular values are one and first derivatives vanish. */
    private static final double ROTATION_ANGLE = 0.7;

    /** Translation of the rotated target, which the energy must ignore. */
    private static final double TRANSLATION_X = 0.3;

    /** Second translation coordinate. */
    private static final double TRANSLATION_Y = -0.2;

    /** Absolute tolerance for the rotation-point Hessian match, allowing the cone smoothing. */
    private static final double ROTATION_MATCH_TOLERANCE = 1.0e-6;

    /** Seed of the random-triangle sweep. */
    private static final long RANDOM_SEED = 20260810L;

    /** Random triangles checked for positive semi-definiteness. */
    private static final int RANDOM_TRIALS = 300;

    /** Decades of squash toward degeneracy the sweep covers. */
    private static final double SQUASH_DECADES = 6.0;

    /** Determinant below which a random target is re-drawn. */
    private static final double MINIMUM_TEST_DETERMINANT = 1.0e-12;

    /** Random probe directions per triangle. */
    private static final int PROBES_PER_TRIAL = 12;

    /** Relative slack of the quadratic-form nonnegativity check. */
    private static final double PSD_TOLERANCE = 1.0e-12;

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

    @Test
    void majorizedHessianMatchesRawHessianAtARotation() {
        SymmetricDirichletEnergy element = new SymmetricDirichletEnergy();
        double cosine = Math.cos(ROTATION_ANGLE);
        double sine = Math.sin(ROTATION_ANGLE);
        double[] cornerU = { 0.0, 1.0, 0.0 };
        double[] cornerV = { 0.0, 0.0, 1.0 };
        double[] targetX = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        double[] targetY = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
        for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
            targetX[corner] = cosine * cornerU[corner] - sine * cornerV[corner] + TRANSLATION_X;
            targetY[corner] = sine * cornerU[corner] + cosine * cornerV[corner] + TRANSLATION_Y;
        }
        element.evaluate(OPERATOR, AREA, targetX.clone(), targetY.clone());
        double[][] majorized = new double[SymmetricDirichletEnergy.VARIABLES][];
        for (int row = 0; row < SymmetricDirichletEnergy.VARIABLES; row++) {
            majorized[row] = element.hessian[row].clone();
        }
        element.projectHessian = false;
        element.evaluate(OPERATOR, AREA, targetX.clone(), targetY.clone());
        for (int row = 0; row < SymmetricDirichletEnergy.VARIABLES; row++) {
            for (int column = 0; column < SymmetricDirichletEnergy.VARIABLES; column++) {
                assertEquals(element.hessian[row][column], majorized[row][column],
                        ROTATION_MATCH_TOLERANCE,
                        "at a rotation both first derivatives vanish, so the majorized"
                                + " Hessian must equal the raw one at (" + row + ", " + column
                                + ")");
            }
        }
    }

    @Test
    void majorizedHessianIsPositiveSemiDefiniteOnRandomTriangles() {
        SymmetricDirichletEnergy element = new SymmetricDirichletEnergy();
        Random random = new Random(RANDOM_SEED);
        double[] probe = new double[SymmetricDirichletEnergy.VARIABLES];
        for (int trial = 0; trial < RANDOM_TRIALS; trial++) {
            double squash = Math.pow(10.0, -SQUASH_DECADES * random.nextDouble());
            double[] targetX = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
            double[] targetY = new double[HalfEdgeMesh.TRIANGLE_CORNERS];
            double determinant = 0.0;
            while (determinant <= MINIMUM_TEST_DETERMINANT) {
                for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
                    targetX[corner] = random.nextDouble() * 2.0 - 1.0;
                    targetY[corner] = (random.nextDouble() * 2.0 - 1.0) * squash;
                }
                determinant = (targetX[1] - targetX[0]) * (targetY[2] - targetY[0])
                        - (targetX[2] - targetX[0]) * (targetY[1] - targetY[0]);
            }
            element.evaluate(OPERATOR, AREA, targetX, targetY);
            double largestEntry = 0.0;
            for (int row = 0; row < SymmetricDirichletEnergy.VARIABLES; row++) {
                for (int column = 0; column < SymmetricDirichletEnergy.VARIABLES; column++) {
                    double entry = element.hessian[row][column];
                    assertTrue(Double.isFinite(entry),
                            "majorized Hessian entry must be finite at trial " + trial);
                    assertEquals(element.hessian[column][row], entry, 0.0,
                            "majorized Hessian must be exactly symmetric at trial " + trial);
                    largestEntry = Math.max(largestEntry, Math.abs(entry));
                }
            }
            for (int probeIndex = 0; probeIndex < PROBES_PER_TRIAL; probeIndex++) {
                double probeNormSquared = 0.0;
                for (int index = 0; index < probe.length; index++) {
                    probe[index] = random.nextDouble() * 2.0 - 1.0;
                    probeNormSquared += probe[index] * probe[index];
                }
                double quadraticForm = 0.0;
                for (int row = 0; row < probe.length; row++) {
                    for (int column = 0; column < probe.length; column++) {
                        quadraticForm += probe[row] * element.hessian[row][column] * probe[column];
                    }
                }
                assertTrue(quadraticForm >= -PSD_TOLERANCE * largestEntry * probeNormSquared,
                        "majorized Hessian must be positive semi-definite at trial " + trial
                                + ", got vᵀHv=" + quadraticForm);
            }
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
        if (variable < HalfEdgeMesh.TRIANGLE_CORNERS) {
            targetX[variable] += displacement;
        } else {
            targetY[variable - HalfEdgeMesh.TRIANGLE_CORNERS] += displacement;
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
        if (variable < HalfEdgeMesh.TRIANGLE_CORNERS) {
            targetX[variable] += displacement;
        } else {
            targetY[variable - HalfEdgeMesh.TRIANGLE_CORNERS] += displacement;
        }
        element.evaluate(OPERATOR, AREA, targetX, targetY);
        return element.gradient.clone();
    }
}
