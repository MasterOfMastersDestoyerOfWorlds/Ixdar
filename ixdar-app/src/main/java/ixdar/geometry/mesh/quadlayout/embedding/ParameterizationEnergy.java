package ixdar.geometry.mesh.quadlayout.embedding;

/**
 * One triangle's fit-to-reference energy {@code A·‖J − Q‖²_F}, the LCBK19 §7
 * objective: difference between the output grid map and the input
 * parametrization, with the per-triangle quarter-turn {@code Q} absorbing
 * chart/frame rotations. Quadratic, so the Hessian is constant and PSD;
 * injectivity is the line search's job, not the energy's.
 */
public class ParameterizationEnergy {

    public static final int TRIANGLE_CORNERS = 3;
    public static final int VARIABLES = 6;

    public double energy;
    public final double[] gradient = new double[VARIABLES];
    public final double[][] hessian = new double[VARIABLES][VARIABLES];
    public double signedArea;

    /**
     * Frozen 2x2 target {@code {a, b, c, d}} for row-major {@code [[a,b],[c,d]]}.
     */
    public final double[] target = new double[4];

    /**
     * Whether energyOnly treats a fold as infinite; off for initially-degenerate
     * triangles.
     */
    public boolean foldGuard = true;

    /**
     * Snaps a Jacobian's rotation to the nearest quarter turn and stores it as the
     * target. Call once per triangle at gather time with the initial map's
     * Jacobian.
     */
    public void freezeTarget(double j00, double j01, double j10, double j11) {
        // polar angle of the closest rotation to J
        double angle = Math.atan2(j10 - j01, j00 + j11);
        int quarter = (int) Math.round(angle / (0.5 * Math.PI));
        double snapped = quarter * 0.5 * Math.PI;
        double cos = Math.cos(snapped);
        double sin = Math.sin(snapped);
        target[0] = cos;
        target[1] = -sin;
        target[2] = sin;
        target[3] = cos;
    }

    public void evaluate(double[] gradientOperator, double area, double[] targetX,
            double[] targetY) {
        double j00 = row(gradientOperator, 0, targetX);
        double j01 = row(gradientOperator, 1, targetX);
        double j10 = row(gradientOperator, 0, targetY);
        double j11 = row(gradientOperator, 1, targetY);
        signedArea = j00 * j11 - j01 * j10;
        double d00 = j00 - target[0];
        double d01 = j01 - target[1];
        double d10 = j10 - target[2];
        double d11 = j11 - target[3];
        energy = area * (d00 * d00 + d01 * d01 + d10 * d10 + d11 * d11);
        for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
            double first = gradientOperator[corner];
            double second = gradientOperator[TRIANGLE_CORNERS + corner];
            gradient[corner] = 2.0 * area * (d00 * first + d01 * second);
            gradient[TRIANGLE_CORNERS + corner] = 2.0 * area * (d10 * first + d11 * second);
        }
        for (int rowIndex = 0; rowIndex < VARIABLES; rowIndex++) {
            for (int column = 0; column < VARIABLES; column++) {
                hessian[rowIndex][column] = rowIndex / TRIANGLE_CORNERS == column / TRIANGLE_CORNERS
                        ? 2.0 * area * (gradientOperator[rowIndex % TRIANGLE_CORNERS]
                                * gradientOperator[column % TRIANGLE_CORNERS]
                                + gradientOperator[TRIANGLE_CORNERS
                                        + rowIndex % TRIANGLE_CORNERS]
                                        * gradientOperator[TRIANGLE_CORNERS
                                                + column % TRIANGLE_CORNERS])
                        : 0.0;
            }
        }
    }

    /**
     * Energy alone; infinite on a fold so the line search preserves injectivity.
     */
    public double energyOnly(double[] gradientOperator, double area, double[] targetX,
            double[] targetY) {

        double j00 = row(gradientOperator, 0, targetX);
        double j01 = row(gradientOperator, 1, targetX);
        double j10 = row(gradientOperator, 0, targetY);
        double j11 = row(gradientOperator, 1, targetY);
        if (foldGuard && j00 * j11 - j01 * j10 <= 0.0)
            return Double.POSITIVE_INFINITY;
        double d00 = j00 - target[0];
        double d01 = j01 - target[1];
        double d10 = j10 - target[2];
        double d11 = j11 - target[3];
        return area * (d00 * d00 + d01 * d01 + d10 * d10 + d11 * d11);
    }

    private double row(double[] gradientOperator, int jacobianRow, double[] targetCoord) {
        int base = jacobianRow * TRIANGLE_CORNERS;
        return gradientOperator[base] * targetCoord[0] + gradientOperator[base + 1] * targetCoord[1]
                + gradientOperator[base + 2] * targetCoord[2];
    }

}
