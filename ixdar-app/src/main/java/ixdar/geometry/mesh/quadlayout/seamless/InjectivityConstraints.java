package ixdar.geometry.mesh.quadlayout.seamless;

import java.util.HashMap;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.quadlayout.solver.system.LazyConstraints;
import ixdar.platform.Platforms;

/**
 * BCE13 §3.1's convex consistent-orientation constraints: per reference
 * triangle, six linear inequalities keeping each mapped corner inside its
 * trisector sector around the reference's first Fermat point.
 *
 * <p>
 * See also: BCE13 Section 3.1, Equation 4, Figure 4
 */
public final class InjectivityConstraints implements LazyConstraints.ConstraintSet {

    /** Constraint margin as a fraction of the smallest reference edge (BCE13 §3.1). */
    public static final double EPSILON_EDGE_FRACTION = 0.01;

    /**
     * Reference angle above which a triangle is virtually split at its altitude —
     * the Fermat construction needs no angle over 120°, and BCE13 §3.1 splits past
     * 100° because larger angles put the Fermat point near a vertex.
     */
    public static final double VIRTUAL_SPLIT_ANGLE_DEGREES = 100.0;

    /**
     * Normalized constraint value below which a constraint is activated even
     * before it is violated (BCE13 §3.4 "Lazy Constraints").
     */
    public static final double ACTIVATION_THRESHOLD = 0.5;

    /** Corners of a triangle. */
    public static final int CORNERS = 3;

    /** Inequalities per (sub)triangle: two sector bounds per corner. */
    public static final int CONSTRAINTS_PER_TRIANGLE = 6;

    /** Coefficient slots per constraint: u then v of each face corner. */
    public static final int COEFFICIENTS_PER_CONSTRAINT = 6;

    /** Height factor of an equilateral triangle over its base, {@code √3/2}. */
    private static final double EQUILATERAL_HEIGHT = Math.sqrt(3.0) / 2.0;

    public final SeamlessParameterization seamless;

    /** Owning active face of each constraint. */
    public int[] faceByConstraint;

    /**
     * Linear form of each constraint over its face's corner charts, laid out as
     * {@code u0, u1, u2, v0, v1, v2} per constraint.
     */
    public double[] cornerCoefficients;

    /** Margin ε of each constraint, in chart units (BCE13 §3.1). */
    public double[] rawThreshold;

    /** Normalizer δ of each constraint, making its reference value one. */
    public double[] normalizer;

    /** Constraints built, in face order. */
    private int constraintCount;

    /** Final-DOF indices of each constraint's expanded gradient, built lazily. */
    private int[][] gradientDofs;

    /** Coefficients matching {@link #gradientDofs}. */
    private double[][] gradientCoefs;

    /** Growing constraint storage cursor while building. */
    private int cursor;

    /**
     * Stores the parametrization whose faces the constraints cover.
     *
     * @param seamless built-up seamless parametrization with per-face geometry and
     *                 a DOF system
     */
    public InjectivityConstraints(SeamlessParameterization seamless) {
        this.seamless = seamless;
    }

    /**
     * Builds the constraint set: per face with area, the reference triangle is the
     * energy's target shape; references with an angle over
     * {@link #VIRTUAL_SPLIT_ANGLE_DEGREES} are split at their altitude foot, and
     * each (sub)triangle contributes six trisector inequalities.
     *
     * @throws IllegalStateException when a reference triangle is negatively
     *                               oriented, which means the target field itself
     *                               reverses orientation
     * @return this, built
     */
    public InjectivityConstraints build() {
        int capacity = seamless.faceCount * CONSTRAINTS_PER_TRIANGLE * 2;
        faceByConstraint = new int[capacity];
        cornerCoefficients = new double[capacity * COEFFICIENTS_PER_CONSTRAINT];
        rawThreshold = new double[capacity];
        normalizer = new double[capacity];
        double[][] reference = new double[CORNERS][2];
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f rel = new Vector3f();
        for (int activeFace = 0; activeFace < seamless.faceCount; activeFace++) {
            if (seamless.faceArea[activeFace] <= 0.0) {
                continue;
            }
            int faceId = seamless.mesh.faceIdAt(activeFace);
            seamless.mesh.vertexPosition(seamless.mesh.faceVertexAt(faceId, 0), p0);
            seamless.mesh.vertexPosition(seamless.mesh.faceVertexAt(faceId, 1), p1);
            seamless.mesh.vertexPosition(seamless.mesh.faceVertexAt(faceId, 2), p2);
            Vector3f xAxis = seamless.crossField.faceX[activeFace];
            Vector3f yAxis = seamless.crossField.faceY[activeFace];
            double[] localX = new double[CORNERS];
            double[] localY = new double[CORNERS];
            rel.set(p1).sub(p0);
            localX[1] = rel.dot(xAxis);
            localY[1] = rel.dot(yAxis);
            rel.set(p2).sub(p0);
            localX[2] = rel.dot(xAxis);
            localY[2] = rel.dot(yAxis);
            double scale = 1.0 / seamless.targetQuadEdgeLength;
            double targetUx = seamless.faceUtxLocal[activeFace];
            double targetUy = seamless.faceUtyLocal[activeFace];
            double targetVx = seamless.faceVtxLocal[activeFace];
            double targetVy = seamless.faceVtyLocal[activeFace];
            for (int corner = 0; corner < CORNERS; corner++) {
                reference[corner][0] = scale
                        * (targetUx * localX[corner] + targetUy * localY[corner]);
                reference[corner][1] = scale
                        * (targetVx * localX[corner] + targetVy * localY[corner]);
            }
            if (signedArea(reference[0], reference[1], reference[2]) <= 0.0) {
                throw new IllegalStateException("reference triangle of face " + activeFace
                        + " is negatively oriented; the target field reverses orientation");
            }
            emitTriangleOrSplit(activeFace, reference);
        }
        constraintCount = cursor;
        gradientDofs = new int[constraintCount][];
        gradientCoefs = new double[constraintCount][];
        Platforms.log("[injectivity] constraints=%d over %d faces%n", constraintCount,
                seamless.faceCount);
        return this;
    }

    @Override
    public int constraintCount() {
        return constraintCount;
    }

    /**
     * Evaluates every constraint's normalized value {@code δ·(raw − ε)}, one at
     * the reference shape, negative when violated.
     *
     * @param solution current final-DOF solution
     * @param out      receives one value per constraint
     */
    @Override
    public void evaluate(double[] solution, double[] out) {
        double[] cornerU = new double[CORNERS];
        double[] cornerV = new double[CORNERS];
        int loadedFace = -1;
        for (int constraint = 0; constraint < constraintCount; constraint++) {
            int face = faceByConstraint[constraint];
            if (face != loadedFace) {
                for (int corner = 0; corner < CORNERS; corner++) {
                    int chartVertex = seamless.cutGraph
                            .cornerToChartVertex[face * CORNERS + corner];
                    cornerU[corner] = seamless.dofSystem
                            .evaluateChartComponent(chartVertex, 0, solution);
                    cornerV[corner] = seamless.dofSystem
                            .evaluateChartComponent(chartVertex, 1, solution);
                }
                loadedFace = face;
            }
            int at = constraint * COEFFICIENTS_PER_CONSTRAINT;
            double raw = 0.0;
            for (int corner = 0; corner < CORNERS; corner++) {
                raw += cornerCoefficients[at + corner] * cornerU[corner]
                        + cornerCoefficients[at + CORNERS + corner] * cornerV[corner];
            }
            out[constraint] = normalizer[constraint] * (raw - rawThreshold[constraint]);
        }
    }

    /**
     * The final-DOF indices of one constraint's expanded gradient, built on first
     * use through the chart vertices' final-DOF expansions.
     *
     * @param constraint constraint index
     * @return the gradient's final-DOF indices
     */
    @Override
    public int[] gradientDofs(int constraint) {
        expandGradient(constraint);
        return gradientDofs[constraint];
    }

    /**
     * Coefficients matching {@link #gradientDofs(int)}, scaled by the constraint's
     * normalizer so the gradient is of the normalized constraint.
     *
     * @param constraint constraint index
     * @return the gradient's coefficients
     */
    @Override
    public double[] gradientCoefs(int constraint) {
        expandGradient(constraint);
        return gradientCoefs[constraint];
    }

    @Override
    public double bound(int constraint) {
        return normalizer[constraint] * rawThreshold[constraint];
    }

    @Override
    public double activationThreshold() {
        return ACTIVATION_THRESHOLD;
    }

    /**
     * Emits a face's constraints, splitting the reference at its altitude foot
     * first when its largest angle exceeds the split threshold (BCE13 §3.1,
     * Figure 4 right).
     *
     * @param activeFace owning face
     * @param reference  the face's three reference corners
     */
    private void emitTriangleOrSplit(int activeFace, double[][] reference) {
        double[][] identity = { { 1.0, 0.0, 0.0 }, { 0.0, 1.0, 0.0 }, { 0.0, 0.0, 1.0 } };
        int obtuse = -1;
        for (int corner = 0; corner < CORNERS; corner++) {
            double[] here = reference[corner];
            double[] next = reference[(corner + 1) % CORNERS];
            double[] previous = reference[(corner + 2) % CORNERS];
            double ax = next[0] - here[0];
            double ay = next[1] - here[1];
            double bx = previous[0] - here[0];
            double by = previous[1] - here[1];
            double angle = Math.acos((ax * bx + ay * by)
                    / (Math.hypot(ax, ay) * Math.hypot(bx, by)));
            if (Math.toDegrees(angle) > VIRTUAL_SPLIT_ANGLE_DEGREES) {
                obtuse = corner;
            }
        }
        if (obtuse < 0) {
            emitTriangle(activeFace, identity, reference);
            return;
        }
        int edgeStart = (obtuse + 1) % CORNERS;
        int edgeEnd = (obtuse + 2) % CORNERS;
        double[] start = reference[edgeStart];
        double[] end = reference[edgeEnd];
        double edgeX = end[0] - start[0];
        double edgeY = end[1] - start[1];
        double along = ((reference[obtuse][0] - start[0]) * edgeX
                + (reference[obtuse][1] - start[1]) * edgeY)
                / (edgeX * edgeX + edgeY * edgeY);
        double[] footWeights = new double[CORNERS];
        footWeights[edgeStart] = 1.0 - along;
        footWeights[edgeEnd] = along;
        double[][] first = { identity[obtuse], identity[edgeStart], footWeights };
        double[][] second = { identity[obtuse], footWeights, identity[edgeEnd] };
        emitTriangle(activeFace, first, positionsOf(first, reference));
        emitTriangle(activeFace, second, positionsOf(second, reference));
    }

    /**
     * The 2D positions of sub-triangle corners given their barycentric weight rows
     * over the face's reference corners.
     *
     * @param weights   one weight row per sub-triangle corner
     * @param reference the face's three reference corners
     * @return the sub-triangle's corner positions
     */
    private double[][] positionsOf(double[][] weights, double[][] reference) {
        double[][] positions = new double[CORNERS][2];
        for (int corner = 0; corner < CORNERS; corner++) {
            for (int source = 0; source < CORNERS; source++) {
                positions[corner][0] += weights[corner][source] * reference[source][0];
                positions[corner][1] += weights[corner][source] * reference[source][1];
            }
        }
        return positions;
    }

    /**
     * Emits one (sub)triangle's six constraints: the Fermat-point trisector rays,
     * one sector per corner, each bounded by two of BCE13 Equation 4's normalized
     * half-plane inequalities with the ε margin.
     *
     * @param activeFace owning face
     * @param weights    barycentric weight row of each corner over the face's
     *                   corners
     * @param corners    the (sub)triangle's reference corner positions
     */
    private void emitTriangle(int activeFace, double[][] weights, double[][] corners) {
        double[] fermat = fermatPoint(corners);
        double totalArea = signedArea(corners[0], corners[1], corners[2]);
        double[] alpha = new double[CORNERS];
        for (int corner = 0; corner < CORNERS; corner++) {
            alpha[corner] = signedArea(fermat, corners[(corner + 1) % CORNERS],
                    corners[(corner + 2) % CORNERS]) / totalArea;
        }
        double[][] direction = new double[CORNERS][2];
        for (int corner = 0; corner < CORNERS; corner++) {
            double dx = corners[corner][0] - fermat[0];
            double dy = corners[corner][1] - fermat[1];
            double length = Math.hypot(dx, dy);
            direction[corner][0] = dx / length;
            direction[corner][1] = dy / length;
        }
        double[][] ray = new double[CORNERS][2];
        for (int corner = 0; corner < CORNERS; corner++) {
            int before = (corner + 2) % CORNERS;
            double bx = direction[before][0] + direction[corner][0];
            double by = direction[before][1] + direction[corner][1];
            double length = Math.hypot(bx, by);
            ray[corner][0] = bx / length;
            ray[corner][1] = by / length;
        }
        double epsilon = EPSILON_EDGE_FRACTION * smallestEdge(corners);
        double[] centreWeights = new double[CORNERS];
        for (int corner = 0; corner < CORNERS; corner++) {
            for (int source = 0; source < CORNERS; source++) {
                centreWeights[source] += alpha[corner] * weights[corner][source];
            }
        }
        for (int corner = 0; corner < CORNERS; corner++) {
            emitHalfPlane(activeFace, weights[corner], centreWeights, corners[corner], fermat,
                    perp(ray[corner]), 1.0, epsilon);
            emitHalfPlane(activeFace, weights[corner], centreWeights, corners[corner], fermat,
                    perp(ray[(corner + 1) % CORNERS]), -1.0, epsilon);
        }
    }

    /**
     * Emits one half-plane inequality {@code sign·(P − m)·n − ε ≥ 0} as a linear
     * form over the face's corner charts, with the normalizer making its reference
     * value one.
     *
     * @param activeFace    owning face
     * @param cornerWeights barycentric weights of the constrained corner
     * @param centreWeights barycentric weights of the trisector centre
     * @param cornerAt      the corner's reference position
     * @param fermat        the trisector centre's reference position
     * @param normal        the half-plane normal, unit length
     * @param sign          {@code +1} for the sector's lower ray, {@code -1} for
     *                      its upper
     * @param epsilon       the margin ε in chart units
     */
    private void emitHalfPlane(int activeFace, double[] cornerWeights, double[] centreWeights,
            double[] cornerAt, double[] fermat, double[] normal, double sign, double epsilon) {
        double referenceValue = sign * ((cornerAt[0] - fermat[0]) * normal[0]
                + (cornerAt[1] - fermat[1]) * normal[1]) - epsilon;
        if (referenceValue <= 0.0) {
            throw new IllegalStateException("degenerate trisector on face " + activeFace
                    + ": reference constraint value " + referenceValue);
        }
        int at = cursor * COEFFICIENTS_PER_CONSTRAINT;
        for (int source = 0; source < CORNERS; source++) {
            double weight = sign * (cornerWeights[source] - centreWeights[source]);
            cornerCoefficients[at + source] = weight * normal[0];
            cornerCoefficients[at + CORNERS + source] = weight * normal[1];
        }
        faceByConstraint[cursor] = activeFace;
        rawThreshold[cursor] = epsilon;
        normalizer[cursor] = 1.0 / referenceValue;
        cursor++;
    }

    /**
     * The first Fermat point of a positively oriented triangle with no angle over
     * 120°: the intersection of two lines joining a corner to the outward
     * equilateral apex over its opposite edge.
     *
     * @param corners the triangle's corner positions
     * @return the Fermat point
     */
    private double[] fermatPoint(double[][] corners) {
        double[][] apex = new double[2][];
        for (int corner = 0; corner < 2; corner++) {
            double[] start = corners[(corner + 1) % CORNERS];
            double[] end = corners[(corner + 2) % CORNERS];
            double midX = (start[0] + end[0]) / 2.0;
            double midY = (start[1] + end[1]) / 2.0;
            double edgeX = end[0] - start[0];
            double edgeY = end[1] - start[1];
            // The outward normal of edge (i+1, i+2) in a ccw triangle is the cw perp.
            apex[corner] = new double[] {
                    midX + EQUILATERAL_HEIGHT * edgeY,
                    midY - EQUILATERAL_HEIGHT * edgeX };
        }
        double d0x = apex[0][0] - corners[0][0];
        double d0y = apex[0][1] - corners[0][1];
        double d1x = apex[1][0] - corners[1][0];
        double d1y = apex[1][1] - corners[1][1];
        double denominator = d0x * d1y - d0y * d1x;
        double t = ((corners[1][0] - corners[0][0]) * d1y
                - (corners[1][1] - corners[0][1]) * d1x) / denominator;
        return new double[] { corners[0][0] + t * d0x, corners[0][1] + t * d0y };
    }

    /**
     * Expands one constraint's corner-chart linear form into final-DOF space,
     * caching the result.
     *
     * @param constraint constraint index
     */
    private void expandGradient(int constraint) {
        if (gradientDofs[constraint] != null) {
            return;
        }
        Map<Integer, Double> accumulator = new HashMap<>();
        int face = faceByConstraint[constraint];
        int at = constraint * COEFFICIENTS_PER_CONSTRAINT;
        for (int corner = 0; corner < CORNERS; corner++) {
            int chartVertex = seamless.cutGraph.cornerToChartVertex[face * CORNERS + corner];
            for (int component = 0; component < 2; component++) {
                double coefficient = normalizer[constraint]
                        * cornerCoefficients[at + component * CORNERS + corner];
                if (coefficient == 0.0) {
                    continue;
                }
                int[] dofs = seamless.dofSystem.chartVertexFinalDofs[chartVertex][component];
                double[] coefs = seamless.dofSystem.chartVertexFinalCoefs[chartVertex][component];
                for (int index = 0; index < dofs.length; index++) {
                    accumulator.merge(dofs[index], coefficient * coefs[index], Double::sum);
                }
            }
        }
        int[] dofs = new int[accumulator.size()];
        double[] coefs = new double[accumulator.size()];
        int index = 0;
        for (Map.Entry<Integer, Double> entry : accumulator.entrySet()) {
            dofs[index] = entry.getKey();
            coefs[index] = entry.getValue();
            index++;
        }
        gradientDofs[constraint] = dofs;
        gradientCoefs[constraint] = coefs;
    }

    /**
     * The perpendicular of a vector, rotated counter-clockwise.
     *
     * @param vector a 2D vector
     * @return its ccw perpendicular
     */
    private double[] perp(double[] vector) {
        return new double[] { -vector[1], vector[0] };
    }

    /**
     * The smallest edge length of a triangle.
     *
     * @param corners the triangle's corner positions
     * @return the shortest side
     */
    private double smallestEdge(double[][] corners) {
        double smallest = Double.POSITIVE_INFINITY;
        for (int corner = 0; corner < CORNERS; corner++) {
            double[] here = corners[corner];
            double[] next = corners[(corner + 1) % CORNERS];
            smallest = Math.min(smallest, Math.hypot(next[0] - here[0], next[1] - here[1]));
        }
        return smallest;
    }

    /**
     * Twice the signed area of a 2D triangle, positive counter-clockwise.
     *
     * @param first  first corner
     * @param second second corner
     * @param third  third corner
     * @return the signed area measure
     */
    private double signedArea(double[] first, double[] second, double[] third) {
        return (second[0] - first[0]) * (third[1] - first[1])
                - (third[0] - first[0]) * (second[1] - first[1]);
    }
}
