package ixdar.geometry.mesh.quadlayout.embedding;

/**
 * One triangle's symmetric Dirichlet energy {@code A·‖J‖²_F·(1 + 1/det²)}, with
 * its analytic gradient and its Hessian projected to positive semi-definite.
 *
 * <p>
 * The energy diverges as a triangle collapses, which is what keeps the map
 * locally injective.
 *
 * <p>
 * See also: LCBK19 Section 6.2
 */
public final class SymmetricDirichletEnergy {

    /** Corners of a triangle. */
    public static final int TRIANGLE_CORNERS = 3;

    /** Variables per triangle: three corners in two coordinates. */
    public static final int VARIABLES = 6;

    /** Sweeps of the Jacobi rotation used to project the Hessian. */
    public static final int JACOBI_SWEEPS = 6;

    /** Off-diagonal entries below this are treated as already zero. */
    public static final double JACOBI_TOLERANCE = 1.0e-12;

    /**
     * Whether {@link #evaluate} clamps the Hessian's negative eigenvalues. Only a
     * test comparing against finite differences wants the raw Hessian; the Newton
     * step needs the projection.
     */
    public boolean projectHessian = true;

    /** Energy of the triangle at the last {@link #evaluate}. */
    public double energy;

    /** Gradient by variable, ordered {@code x0, x1, x2, y0, y1, y2}. */
    public final double[] gradient = new double[VARIABLES];

    /** Hessian over the same ordering, projected to positive semi-definite. */
    public final double[][] hessian = new double[VARIABLES][VARIABLES];

    /**
     * Signed area of the triangle in the target, negative when it has folded over.
     */
    public double signedArea;

    private final double[][] eigenvectors = new double[VARIABLES][VARIABLES];
    private final double[] eigenvalues = new double[VARIABLES];

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

        double[] frobeniusGradient = new double[VARIABLES];
        double[] determinantGradient = new double[VARIABLES];
        for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
            double first = gradientOperator[corner];
            double second = gradientOperator[TRIANGLE_CORNERS + corner];
            frobeniusGradient[corner] = 2.0 * (firstU * first + secondU * second);
            frobeniusGradient[TRIANGLE_CORNERS + corner] = 2.0 * (firstV * first + secondV * second);
            determinantGradient[corner] = secondV * first - firstV * second;
            determinantGradient[TRIANGLE_CORNERS + corner] = firstU * second - secondU * first;
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
        for (int row = 0; row < VARIABLES; row++) {
            for (int column = 0; column < VARIABLES; column++) {
                double frobeniusHessian = row / TRIANGLE_CORNERS == column / TRIANGLE_CORNERS
                        ? 2.0 * (gradientOperator[row % TRIANGLE_CORNERS]
                                * gradientOperator[column % TRIANGLE_CORNERS]
                                + gradientOperator[TRIANGLE_CORNERS + row % TRIANGLE_CORNERS]
                                        * gradientOperator[TRIANGLE_CORNERS
                                                + column % TRIANGLE_CORNERS])
                        : 0.0;
                double determinantHessian = 0.0;
                if (row / TRIANGLE_CORNERS != column / TRIANGLE_CORNERS) {
                    int xIndex = row < TRIANGLE_CORNERS ? row : column;
                    int yIndex = row < TRIANGLE_CORNERS ? column : row;
                    yIndex -= TRIANGLE_CORNERS;
                    determinantHessian = gradientOperator[TRIANGLE_CORNERS + yIndex]
                            * gradientOperator[xIndex]
                            - gradientOperator[yIndex] * gradientOperator[TRIANGLE_CORNERS + xIndex];
                }
                hessian[row][column] = area * (scale * frobeniusHessian
                        + crossFactor * (frobeniusGradient[row] * determinantGradient[column]
                                + determinantGradient[row] * frobeniusGradient[column])
                        + squareFactor * determinantGradient[row] * determinantGradient[column]
                        + frobenius * crossFactor * determinantHessian);
            }
        }
        if (projectHessian) {
            projectToPositiveSemiDefinite();
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
        int base = jacobianRow * TRIANGLE_CORNERS;
        // The operator's first entry is minus the other two, so this is the same value as
        // summing all three products — but the corner differences are taken first, which is
        // exact for nearby doubles. Summing instead cancels a grid offset of hundreds against
        // a triangle spanning 1e-11 and leaves nothing.
        return gradientOperator[base + 1] * (target[1] - target[0])
                + gradientOperator[base + 2] * (target[2] - target[0]);
    }

    /**
     * Clamps the Hessian's negative eigenvalues to zero, which the Newton step
     * needs because the true Hessian is indefinite away from the minimum and
     * Cholesky requires positive semi-definiteness.
     */
    private void projectToPositiveSemiDefinite() {
        for (int row = 0; row < VARIABLES; row++) {
            for (int column = 0; column < VARIABLES; column++) {
                eigenvectors[row][column] = row == column ? 1.0 : 0.0;
            }
        }
        for (int sweep = 0; sweep < JACOBI_SWEEPS; sweep++) {
            for (int row = 0; row < VARIABLES - 1; row++) {
                for (int column = row + 1; column < VARIABLES; column++) {
                    rotate(row, column);
                }
            }
        }
        for (int index = 0; index < VARIABLES; index++) {
            eigenvalues[index] = hessian[index][index];
        }
        for (int row = 0; row < VARIABLES; row++) {
            for (int column = row; column < VARIABLES; column++) {
                double sum = 0.0;
                for (int index = 0; index < VARIABLES; index++) {
                    if (eigenvalues[index] <= 0.0) {
                        continue;
                    }
                    sum += eigenvalues[index] * eigenvectors[row][index] * eigenvectors[column][index];
                }
                hessian[row][column] = sum;
                hessian[column][row] = sum;
            }
        }
    }

    /**
     * One Jacobi rotation zeroing a symmetric off-diagonal pair of the Hessian,
     * accumulating the rotation into the eigenvector basis.
     *
     * @param pivotRow    row of the entry to zero
     * @param pivotColumn column of the entry to zero
     */
    private void rotate(int pivotRow, int pivotColumn) {
        double offDiagonal = hessian[pivotRow][pivotColumn];
        if (Math.abs(offDiagonal) < JACOBI_TOLERANCE) {
            return;
        }
        double difference = hessian[pivotColumn][pivotColumn] - hessian[pivotRow][pivotRow];
        double theta = difference / (2.0 * offDiagonal);
        double tangent = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1.0));
        if (theta == 0.0) {
            tangent = 1.0;
        }
        double cosine = 1.0 / Math.sqrt(tangent * tangent + 1.0);
        double sine = tangent * cosine;
        for (int index = 0; index < VARIABLES; index++) {
            double atRow = hessian[pivotRow][index];
            double atColumn = hessian[pivotColumn][index];
            hessian[pivotRow][index] = cosine * atRow - sine * atColumn;
            hessian[pivotColumn][index] = sine * atRow + cosine * atColumn;
        }
        for (int index = 0; index < VARIABLES; index++) {
            double atRow = hessian[index][pivotRow];
            double atColumn = hessian[index][pivotColumn];
            hessian[index][pivotRow] = cosine * atRow - sine * atColumn;
            hessian[index][pivotColumn] = sine * atRow + cosine * atColumn;
        }
        for (int index = 0; index < VARIABLES; index++) {
            double atRow = eigenvectors[index][pivotRow];
            double atColumn = eigenvectors[index][pivotColumn];
            eigenvectors[index][pivotRow] = cosine * atRow - sine * atColumn;
            eigenvectors[index][pivotColumn] = sine * atRow + cosine * atColumn;
        }
    }
}
