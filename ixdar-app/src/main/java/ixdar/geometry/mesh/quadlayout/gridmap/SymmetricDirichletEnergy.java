package ixdar.geometry.mesh.quadlayout.gridmap;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * One triangle's symmetric Dirichlet energy {@code A·‖J‖²_F·(1 + 1/det²)}, with
 * its analytic gradient and SPH17's composite-majorization positive
 * semi-definite Hessian.
 *
 * <p>
 * The energy diverges as a triangle collapses, which is what keeps the map
 * locally injective.
 *
 * <p>
 * See also: LCBK19 Section 6.2; SPH17 Sections 3-4
 */
public final class SymmetricDirichletEnergy {

    /** Variables per triangle: three corners in two coordinates. */
    public static final int VARIABLES = 6;

    /**
     * Fraction of {@code ‖α‖+‖β‖} smoothing a curvature weight's {@code 0/0}
     * cone point toward its analytic limit {@code h_ΣΣ+h_σσ}.
     */
    public static final double DEGENERATE_NORM_FRACTION = 1.0e-8;

    /**
     * Whether {@link #evaluate} builds the composite-majorization PSD Hessian.
     * Only a test comparing against finite differences wants the raw Hessian;
     * the Newton step needs positive semi-definiteness.
     */
    public boolean projectHessian = true;

    /** Energy of the triangle at the last {@link #evaluate}. */
    public double energy;

    /** Gradient by variable, ordered {@code x0, x1, x2, y0, y1, y2}. */
    public final double[] gradient = new double[VARIABLES];

    /** Hessian over the same ordering, positive semi-definite by majorization. */
    public final double[][] hessian = new double[VARIABLES][VARIABLES];

    /**
     * Signed area of the triangle in the target, negative when it has folded over.
     */
    public double signedArea;

    private final double[] frobeniusGradient = new double[VARIABLES];
    private final double[] determinantGradient = new double[VARIABLES];
    private final double[] largestSingularGradient = new double[VARIABLES];
    private final double[] smallestSingularGradient = new double[VARIABLES];
    private final double[] alphaRotatedDirection = new double[VARIABLES];
    private final double[] betaRotatedDirection = new double[VARIABLES];

    /**
     * Evaluates the energy, gradient and projected Hessian of one triangle.
     *
     * @param gradientOperator the constant {@code 2×3} operator taking corner
     *                         coordinates to a row of the Jacobian, row-major
     * @param area             source area weighting the triangle
     * @param targetX          the three corners' first target coordinate
     * @param targetY          the three corners' second target coordinate
     */
    public void evaluate(double[] gradientOperator, double area, double[] targetX,
            double[] targetY) {
        double firstU = row(gradientOperator, 0, targetX);
        double secondU = row(gradientOperator, 1, targetX);
        double firstV = row(gradientOperator, 0, targetY);
        double secondV = row(gradientOperator, 1, targetY);
        double frobenius = firstU * firstU + secondU * secondU + firstV * firstV + secondV * secondV;
        double determinant = firstU * secondV - secondU * firstV;
        signedArea = determinant;
        double inverseSquared = 1.0 / (determinant * determinant);
        energy = area * frobenius * (1.0 + inverseSquared);

        for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
            double first = gradientOperator[corner];
            double second = gradientOperator[HalfEdgeMesh.TRIANGLE_CORNERS + corner];
            frobeniusGradient[corner] = 2.0 * (firstU * first + secondU * second);
            frobeniusGradient[HalfEdgeMesh.TRIANGLE_CORNERS + corner] = 2.0 * (firstV * first + secondV * second);
            determinantGradient[corner] = secondV * first - firstV * second;
            determinantGradient[HalfEdgeMesh.TRIANGLE_CORNERS + corner] = firstU * second - secondU * first;
        }

        double scale = 1.0 + inverseSquared;
        double crossFactor = -2.0 / (determinant * determinant * determinant);
        double squareFactor = 6.0 * frobenius
                / (determinant * determinant * determinant * determinant);
        for (int row = 0; row < VARIABLES; row++) {
            gradient[row] = area
                    * (scale * frobeniusGradient[row] + frobenius * crossFactor
                            * determinantGradient[row]);
        }
        if (projectHessian) {
            composeMajorizedHessian(gradientOperator, area, firstU, secondU, firstV, secondV);
            return;
        }
        for (int row = 0; row < VARIABLES; row++) {
            for (int column = 0; column < VARIABLES; column++) {
                double frobeniusHessian = row / HalfEdgeMesh.TRIANGLE_CORNERS == column / HalfEdgeMesh.TRIANGLE_CORNERS
                        ? 2.0 * (gradientOperator[row % HalfEdgeMesh.TRIANGLE_CORNERS]
                                * gradientOperator[column % HalfEdgeMesh.TRIANGLE_CORNERS]
                                + gradientOperator[HalfEdgeMesh.TRIANGLE_CORNERS + row % HalfEdgeMesh.TRIANGLE_CORNERS]
                                        * gradientOperator[HalfEdgeMesh.TRIANGLE_CORNERS
                                                + column % HalfEdgeMesh.TRIANGLE_CORNERS])
                        : 0.0;
                double determinantHessian = 0.0;
                if (row / HalfEdgeMesh.TRIANGLE_CORNERS != column / HalfEdgeMesh.TRIANGLE_CORNERS) {
                    int xIndex = row < HalfEdgeMesh.TRIANGLE_CORNERS ? row : column;
                    int yIndex = row < HalfEdgeMesh.TRIANGLE_CORNERS ? column : row;
                    yIndex -= HalfEdgeMesh.TRIANGLE_CORNERS;
                    determinantHessian = gradientOperator[HalfEdgeMesh.TRIANGLE_CORNERS + yIndex]
                            * gradientOperator[xIndex]
                            - gradientOperator[yIndex] * gradientOperator[HalfEdgeMesh.TRIANGLE_CORNERS + xIndex];
                }
                hessian[row][column] = area * (scale * frobeniusHessian
                        + crossFactor * (frobeniusGradient[row] * determinantGradient[column]
                                + determinantGradient[row] * frobeniusGradient[column])
                        + squareFactor * determinantGradient[row] * determinantGradient[column]
                        + frobenius * crossFactor * determinantHessian);
            }
        }
    }

    /**
     * Builds SPH17's composite-majorization PSD Hessian into {@link #hessian}:
     * four rank-one terms from the singular-value gradients and the rotated
     * similarity directions (Eq. 9/17/22/23 with Eq. 18 term gathering).
     *
     * @param gradientOperator the constant {@code 2×3} operator, row-major
     * @param area             source area weighting the triangle
     * @param firstU           Jacobian entry {@code ∂x/∂u}
     * @param secondU          Jacobian entry {@code ∂x/∂v}
     * @param firstV           Jacobian entry {@code ∂y/∂u}
     * @param secondV          Jacobian entry {@code ∂y/∂v}
     */
    private void composeMajorizedHessian(double[] gradientOperator, double area,
            double firstU, double secondU, double firstV, double secondV) {
        double alphaFirst = (firstU + secondV) / 2.0;
        double alphaSecond = (firstV - secondU) / 2.0;
        double betaFirst = (firstU - secondV) / 2.0;
        double betaSecond = (firstV + secondU) / 2.0;
        double alphaNorm = Math.sqrt(alphaFirst * alphaFirst + alphaSecond * alphaSecond);
        double betaNorm = Math.sqrt(betaFirst * betaFirst + betaSecond * betaSecond);
        double largest = alphaNorm + betaNorm;
        double smallest = alphaNorm - betaNorm;

        double normFloor = DEGENERATE_NORM_FRACTION * largest;
        double alphaUnitFirst = alphaNorm > 0.0 ? alphaFirst / alphaNorm : 1.0;
        double alphaUnitSecond = alphaNorm > 0.0 ? alphaSecond / alphaNorm : 0.0;
        double betaUnitFirst = betaNorm > 0.0 ? betaFirst / betaNorm : 1.0;
        double betaUnitSecond = betaNorm > 0.0 ? betaSecond / betaNorm : 0.0;
        for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
            double first = gradientOperator[corner];
            double second = gradientOperator[HalfEdgeMesh.TRIANGLE_CORNERS + corner];
            double alphaGradientX = (first * alphaUnitFirst - second * alphaUnitSecond) / 2.0;
            double alphaGradientY = (second * alphaUnitFirst + first * alphaUnitSecond) / 2.0;
            double betaGradientX = (first * betaUnitFirst + second * betaUnitSecond) / 2.0;
            double betaGradientY = (-second * betaUnitFirst + first * betaUnitSecond) / 2.0;
            largestSingularGradient[corner] = alphaGradientX + betaGradientX;
            largestSingularGradient[HalfEdgeMesh.TRIANGLE_CORNERS + corner] = alphaGradientY
                    + betaGradientY;
            smallestSingularGradient[corner] = alphaGradientX - betaGradientX;
            smallestSingularGradient[HalfEdgeMesh.TRIANGLE_CORNERS + corner] = alphaGradientY
                    - betaGradientY;
            alphaRotatedDirection[corner] = -(first * alphaUnitSecond + second * alphaUnitFirst)
                    / 2.0;
            alphaRotatedDirection[HalfEdgeMesh.TRIANGLE_CORNERS + corner] = (first * alphaUnitFirst
                    - second * alphaUnitSecond) / 2.0;
            betaRotatedDirection[corner] = (second * betaUnitFirst - first * betaUnitSecond) / 2.0;
            betaRotatedDirection[HalfEdgeMesh.TRIANGLE_CORNERS + corner] = (first * betaUnitFirst
                    + second * betaUnitSecond) / 2.0;
        }

        double largestFourth = largest * largest * largest * largest;
        double smallestFourth = smallest * smallest * smallest * smallest;
        double largestSecondDerivative = area * (2.0 + 6.0 / largestFourth);
        double smallestSecondDerivative = area * (2.0 + 6.0 / smallestFourth);
        double largestFirstDerivative = area * 2.0 * (largest - 1.0 / (largest * largest * largest));
        double smallestFirstDerivative = area * 2.0
                * (smallest - 1.0 / (smallest * smallest * smallest));
        double curvatureLimit = largestSecondDerivative + smallestSecondDerivative;
        double alphaCurvatureWeight = (Math.max(largestFirstDerivative, 0.0)
                + Math.max(smallestFirstDerivative, 0.0) + curvatureLimit * normFloor)
                / (alphaNorm + normFloor);
        double betaCurvatureWeight = (Math.max(largestFirstDerivative, 0.0)
                + Math.max(-smallestFirstDerivative, 0.0) + curvatureLimit * normFloor)
                / (betaNorm + normFloor);
        for (int row = 0; row < VARIABLES; row++) {
            for (int column = row; column < VARIABLES; column++) {
                double value = largestSecondDerivative
                        * largestSingularGradient[row] * largestSingularGradient[column]
                        + smallestSecondDerivative
                                * smallestSingularGradient[row] * smallestSingularGradient[column]
                        + alphaCurvatureWeight
                                * alphaRotatedDirection[row] * alphaRotatedDirection[column]
                        + betaCurvatureWeight
                                * betaRotatedDirection[row] * betaRotatedDirection[column];
                hessian[row][column] = value;
                hessian[column][row] = value;
            }
        }
    }

    /**
     * The energy alone, without the derivatives, for the line search's trial
     * points.
     *
     * @param gradientOperator the constant {@code 2×3} operator, row-major
     * @param area             source area weighting the triangle
     * @param targetX          the three corners' first target coordinate
     * @param targetY          the three corners' second target coordinate
     * @return the energy, or {@link Double#POSITIVE_INFINITY} where the triangle
     *         has folded
     */
    public double energyOnly(double[] gradientOperator, double area, double[] targetX,
            double[] targetY) {
        double firstU = row(gradientOperator, 0, targetX);
        double secondU = row(gradientOperator, 1, targetX);
        double firstV = row(gradientOperator, 0, targetY);
        double secondV = row(gradientOperator, 1, targetY);
        double determinant = firstU * secondV - secondU * firstV;
        if (determinant <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        double frobenius = firstU * firstU + secondU * secondU + firstV * firstV + secondV * secondV;
        return area * frobenius * (1.0 + 1.0 / (determinant * determinant));
    }

    /**
     * One row of the Jacobian, which is the gradient operator applied to one target
     * coordinate.
     *
     * @param gradientOperator the {@code 2×3} operator, row-major
     * @param jacobianRow      row to take, {@code 0} or {@code 1}
     * @param target           the three corners' coordinate
     * @return the Jacobian entry
     */
    private double row(double[] gradientOperator, int jacobianRow, double[] target) {
        int base = jacobianRow * HalfEdgeMesh.TRIANGLE_CORNERS;
        // The operator's first entry is minus the other two, so this is the same value
        // as
        // summing all three products — but the corner differences are taken first,
        // which is
        // exact for nearby doubles. Summing instead cancels a grid offset of hundreds
        // against
        // a triangle spanning 1e-11 and leaves nothing.
        return gradientOperator[base + 1] * (target[1] - target[0])
                + gradientOperator[base + 2] * (target[2] - target[0]);
    }

}
