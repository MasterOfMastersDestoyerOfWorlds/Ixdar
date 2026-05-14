package ixdar.geometry.mesh.quadlayout.seamless.exact;

import java.math.BigInteger;

import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * MC19 (Mandad–Campen 2019, "Exact Constraint Satisfaction for Truly Seamless
 * Parametrization") projection of an approximately-seamless parametrization
 * onto the exactly-seamless solution space. After {@link #project()}, the seam
 * transition equation across every interior cut edge is satisfied exactly in
 * real arithmetic, with every output still representable as a standard
 * {@code double}.
 *
 * <p>Mirrors the role of {@code crossfield.SmoothEnergySystem} in the cross-field
 * pipeline: owns the constraint system construction, the projection algorithm,
 * and the writeback into the parent {@link SeamlessParameterization}.
 *
 * <p>v1: generic §4 path on the full transition-constraint matrix. Coefficients
 * are integers in {−1, 0, +1}; the matrix is built as a dense
 * {@link BigInteger}[][]. The §5.3.1 specialization (orders-of-magnitude
 * speedup on large meshes) is a follow-up.
 */
public final class SeamlessProjector {

    private static final int CORNERS_PER_FACE = SeamlessParameterization.CORNERS_PER_FACE;
    private static final int COMPONENTS_PER_CHART_VERTEX = 2;
    private static final int U_COMPONENT = 0;
    private static final int V_COMPONENT = 1;
    private static final double OVERFLOW_RELATIVE_BOUND = 1.0;

    public final SeamlessParameterization seamless;

    /**
     * Bind this projector to a built {@link SeamlessParameterization}. The
     * projector mutates {@code seamless.uCorner}, {@code seamless.vCorner},
     * {@code seamless.cutTranslationS}, and {@code seamless.cutTranslationT} when
     * {@link #project()} is called.
     *
     * @param seamless the parametrization to project onto the exact-constraint
     *                 manifold; must have already had {@link
     *                 SeamlessParameterization#build()} run
     */
    public SeamlessProjector(SeamlessParameterization seamless) {
        this.seamless = seamless;
    }

    /**
     * Run the MC19 projection: build the transition-constraint matrix, reduce it
     * to integer reduced row echelon form, evaluate exact values for every chart
     * vertex's {@code (u, v)}, write them back into the parametrization's corner
     * arrays, and recompute the per-cut-edge {@code (s, t)} translations from
     * the projected sectors.
     *
     * @throws ArithmeticException if the projection moves any output by more
     *         than the input's maximum absolute value (MC19 §7 overflow bound)
     */
    public void project() {
        int chartVertexCount = seamless.cutGraph.chartVertexCount;
        int variableCount = COMPONENTS_PER_CHART_VERTEX * chartVertexCount;

        int interiorCutEdgeCount = countInteriorCutEdges();
        int constraintCount = COMPONENTS_PER_CHART_VERTEX * interiorCutEdgeCount;

        BigInteger[][] matrix = new BigInteger[constraintCount][variableCount];
        for (int r = 0; r < constraintCount; r++) {
            for (int c = 0; c < variableCount; c++) {
                matrix[r][c] = BigInteger.ZERO;
            }
        }
        BigInteger[] rhs = new BigInteger[constraintCount];
        for (int i = 0; i < constraintCount; i++) {
            rhs[i] = BigInteger.ZERO;
        }

        fillTransitionConstraints(matrix);
        IrrefResult result = ExactArithmetic.reduceToIrref(matrix, rhs);

        double[] xBar = collectChartVertexValues(chartVertexCount);
        double scale = ExactArithmetic.chooseFdScale(xBar);
        double[] xExact = ExactArithmetic.evaluate(result, xBar, scale);

        verifyOverflowBound(xBar, xExact);
        scatterChartVertexValuesToCorners(xExact);
        recomputeCutTranslations();
    }

    /**
     * Count interior cut edges (cut, with valid faces on both sides). Boundary
     * cut edges contribute no transition equation.
     *
     * @return the number of interior cut edges
     */
    private int countInteriorCutEdges() {
        int count = 0;
        for (int activeEdge = 0; activeEdge < seamless.edgeCount; activeEdge++) {
            if (!seamless.cutGraph.isCutEdge[activeEdge]) {
                continue;
            }
            if (seamless.edgeFaceA[activeEdge] < 0 || seamless.edgeFaceB[activeEdge] < 0) {
                continue;
            }
            count++;
        }
        return count;
    }

    /**
     * For every interior cut edge, add two transition rows to the matrix — one
     * per scalar component of the 2D constraint
     * {@code r · (f_A(a) − f_A(b)) − (f_B(a) − f_B(b)) = 0}, where {@code r ∈ {0..3}}
     * is the integer rotation in {@link ixdar.geometry.mesh.quadlayout.seamless.CutGraph#cutRotation}.
     *
     * @param matrix the zeroed constraint matrix to fill
     */
    private void fillTransitionConstraints(BigInteger[][] matrix) {
        int row = 0;
        for (int activeEdge = 0; activeEdge < seamless.edgeCount; activeEdge++) {
            if (!seamless.cutGraph.isCutEdge[activeEdge]) {
                continue;
            }
            int faceA = seamless.edgeFaceA[activeEdge];
            int faceB = seamless.edgeFaceB[activeEdge];
            if (faceA < 0 || faceB < 0) {
                continue;
            }
            int cornerAStart = seamless.edgeCornerInA[activeEdge];
            int cornerBStart = seamless.edgeCornerInB[activeEdge];
            int cornerAEnd = (cornerAStart + 1) % CORNERS_PER_FACE;
            int cornerBEnd = (cornerBStart + CORNERS_PER_FACE - 1) % CORNERS_PER_FACE;
            int chartAStart = seamless.cutGraph.cornerToChartVertex[faceA * CORNERS_PER_FACE + cornerAStart];
            int chartAEnd = seamless.cutGraph.cornerToChartVertex[faceA * CORNERS_PER_FACE + cornerAEnd];
            int chartBStart = seamless.cutGraph.cornerToChartVertex[faceB * CORNERS_PER_FACE + cornerBStart];
            int chartBEnd = seamless.cutGraph.cornerToChartVertex[faceB * CORNERS_PER_FACE + cornerBEnd];

            int rotation = seamless.cutGraph.cutRotation[activeEdge];
            BigInteger rotationCosine = BigInteger.valueOf(ExactArithmetic.integerCosine(rotation));
            BigInteger rotationSine = BigInteger.valueOf(ExactArithmetic.integerSine(rotation));
            BigInteger negatedCosine = rotationCosine.negate();
            BigInteger negatedSine = rotationSine.negate();

            int rowU = row;
            int colAStartU = variableIndex(chartAStart, U_COMPONENT);
            int colAEndU = variableIndex(chartAEnd, U_COMPONENT);
            int colAStartV = variableIndex(chartAStart, V_COMPONENT);
            int colAEndV = variableIndex(chartAEnd, V_COMPONENT);
            int colBStartU = variableIndex(chartBStart, U_COMPONENT);
            int colBEndU = variableIndex(chartBEnd, U_COMPONENT);
            int colBStartV = variableIndex(chartBStart, V_COMPONENT);
            int colBEndV = variableIndex(chartBEnd, V_COMPONENT);

            matrix[rowU][colAStartU] = matrix[rowU][colAStartU].add(rotationCosine);
            matrix[rowU][colAEndU] = matrix[rowU][colAEndU].add(negatedCosine);
            matrix[rowU][colAStartV] = matrix[rowU][colAStartV].add(negatedSine);
            matrix[rowU][colAEndV] = matrix[rowU][colAEndV].add(rotationSine);
            matrix[rowU][colBStartU] = matrix[rowU][colBStartU].subtract(BigInteger.ONE);
            matrix[rowU][colBEndU] = matrix[rowU][colBEndU].add(BigInteger.ONE);

            int rowV = row + 1;
            matrix[rowV][colAStartU] = matrix[rowV][colAStartU].add(rotationSine);
            matrix[rowV][colAEndU] = matrix[rowV][colAEndU].add(negatedSine);
            matrix[rowV][colAStartV] = matrix[rowV][colAStartV].add(rotationCosine);
            matrix[rowV][colAEndV] = matrix[rowV][colAEndV].add(negatedCosine);
            matrix[rowV][colBStartV] = matrix[rowV][colBStartV].subtract(BigInteger.ONE);
            matrix[rowV][colBEndV] = matrix[rowV][colBEndV].add(BigInteger.ONE);

            row += COMPONENTS_PER_CHART_VERTEX;
        }
    }

    /**
     * Read the current {@code (u, v)} of every chart vertex from the
     * parametrization's corner arrays. Every corner that maps to the same chart
     * vertex carries the same {@code (u, v)} after the previous solve, so we
     * just pick the first corner found for each chart vertex.
     *
     * @param chartVertexCount the total number of chart vertices
     * @return a length-{@code 2*chartVertexCount} vector with u at even indices
     *         and v at odd indices, indexed by {@link #variableIndex(int, int)}
     */
    private double[] collectChartVertexValues(int chartVertexCount) {
        double[] values = new double[COMPONENTS_PER_CHART_VERTEX * chartVertexCount];
        boolean[] populated = new boolean[chartVertexCount];
        int totalCorners = seamless.faceCount * CORNERS_PER_FACE;
        for (int cornerIdx = 0; cornerIdx < totalCorners; cornerIdx++) {
            int chartVertex = seamless.cutGraph.cornerToChartVertex[cornerIdx];
            if (populated[chartVertex]) {
                continue;
            }
            values[variableIndex(chartVertex, U_COMPONENT)] = seamless.uCorner[cornerIdx];
            values[variableIndex(chartVertex, V_COMPONENT)] = seamless.vCorner[cornerIdx];
            populated[chartVertex] = true;
        }
        return values;
    }

    /**
     * Write the projected exact values back into every corner via the
     * {@code cornerToChartVertex} map.
     *
     * @param xExact the projected length-{@code 2*chartVertexCount} value vector
     */
    private void scatterChartVertexValuesToCorners(double[] xExact) {
        int totalCorners = seamless.faceCount * CORNERS_PER_FACE;
        for (int cornerIdx = 0; cornerIdx < totalCorners; cornerIdx++) {
            int chartVertex = seamless.cutGraph.cornerToChartVertex[cornerIdx];
            seamless.uCorner[cornerIdx] = (float) xExact[variableIndex(chartVertex, U_COMPONENT)];
            seamless.vCorner[cornerIdx] = (float) xExact[variableIndex(chartVertex, V_COMPONENT)];
        }
    }

    /**
     * Recompute {@code cutTranslationS}/{@code cutTranslationT} from the freshly
     * projected sector values per MC19 Eq. 7: for every interior cut edge with
     * rotation {@code r} and any vertex {@code v} on it,
     * {@code t_AB(v) = f_B(v) − R_r · f_A(v)}. Picks the edge's start vertex.
     */
    private void recomputeCutTranslations() {
        for (int activeEdge = 0; activeEdge < seamless.edgeCount; activeEdge++) {
            if (!seamless.cutGraph.isCutEdge[activeEdge]) {
                continue;
            }
            int faceA = seamless.edgeFaceA[activeEdge];
            int faceB = seamless.edgeFaceB[activeEdge];
            if (faceA < 0 || faceB < 0) {
                continue;
            }
            int cornerAStart = seamless.edgeCornerInA[activeEdge];
            int cornerBStart = seamless.edgeCornerInB[activeEdge];
            int rotation = seamless.cutGraph.cutRotation[activeEdge];
            int rotationCosine = ExactArithmetic.integerCosine(rotation);
            int rotationSine = ExactArithmetic.integerSine(rotation);
            float uA = seamless.uCorner[faceA * CORNERS_PER_FACE + cornerAStart];
            float vA = seamless.vCorner[faceA * CORNERS_PER_FACE + cornerAStart];
            float uB = seamless.uCorner[faceB * CORNERS_PER_FACE + cornerBStart];
            float vB = seamless.vCorner[faceB * CORNERS_PER_FACE + cornerBStart];
            seamless.cutTranslationS[activeEdge] = uB - (rotationCosine * uA - rotationSine * vA);
            seamless.cutTranslationT[activeEdge] = vB - (rotationSine * uA + rotationCosine * vA);
        }
    }

    /**
     * Assert MC19 §7 overflow bound: the projection must not move any output by
     * more than the input's maximum absolute value, i.e.
     * {@code ‖x − x̄‖∞ / max_i |x̄_i| < 1}. The paper reports their experiments
     * never exceed {@code 10⁻⁵}; a violation here signals either a degenerate
     * input or an implementation bug.
     *
     * @param xBar   the input approximate values
     * @param xExact the projected exact values
     * @throws ArithmeticException if the bound is violated
     */
    private static void verifyOverflowBound(double[] xBar, double[] xExact) {
        double maxAbs = 0.0;
        double maxDelta = 0.0;
        for (int i = 0; i < xBar.length; i++) {
            double a = Math.abs(xBar[i]);
            if (a > maxAbs) {
                maxAbs = a;
            }
            double delta = Math.abs(xExact[i] - xBar[i]);
            if (delta > maxDelta) {
                maxDelta = delta;
            }
        }
        if (maxAbs == 0.0) {
            return;
        }
        double relative = maxDelta / maxAbs;
        if (relative >= OVERFLOW_RELATIVE_BOUND) {
            throw new ArithmeticException(
                    "MC19 §7 overflow bound exceeded: ‖x − x̄‖∞ / max|x̄| = "
                            + relative + " (must be < 1)");
        }
    }

    /**
     * Map a (chart vertex, component) pair to a flat variable index. {@code u}
     * lives at even indices, {@code v} at odd.
     *
     * @param chartVertex the chart vertex index in {@code [0, chartVertexCount)}
     * @param component   {@link #U_COMPONENT} or {@link #V_COMPONENT}
     * @return the flat variable index
     */
    private static int variableIndex(int chartVertex, int component) {
        return COMPONENTS_PER_CHART_VERTEX * chartVertex + component;
    }
}
