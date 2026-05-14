package ixdar.geometry.mesh.quadlayout.seamless.exact;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.CutGraph;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * MC19 (Mandad–Campen 2019, "Exact Constraint Satisfaction for Truly Seamless
 * Parametrization") projection of an approximately-seamless parametrization
 * onto the exactly-seamless solution space. After {@link #project()}, the seam
 * transition equation across every interior cut edge is satisfied exactly in
 * real arithmetic, with every output still representable as a standard
 * {@code float}.
 *
 * <p>Implements the §5.3.1 specialization: enumerate branches by walking the
 * cut graph in place, build a tiny reduced constraint matrix {@code C̄} over
 * node-sector variables only (one row per branch per component), apply §4 to
 * that, then derive non-node sector values per-branch by walking forward from
 * the start-node value with the rotation-by-{@code r·π/2} transition formula.
 *
 * <p>Mirrors the role of {@code crossfield.SmoothEnergySystem} in the cross-field
 * pipeline: owns matrix construction, the projection algorithm, and the
 * writeback into the parent {@link SeamlessParameterization}.
 */
public final class SeamlessProjector {

    private static final int CORNERS_PER_FACE = SeamlessParameterization.CORNERS_PER_FACE;
    private static final int COMPONENTS_PER_CHART_VERTEX = 2;
    private static final int U_COMPONENT = 0;
    private static final int V_COMPONENT = 1;
    public final SeamlessParameterization seamless;
    public final CutGraph cutGraph;
    public final CrossField crossField;
    public final HalfEdgeMesh mesh;

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
        this.cutGraph = seamless.cutGraph;
        this.crossField = seamless.crossField;
        this.mesh = seamless.mesh;
    }

    /**
     * Run the MC19 §5.3.1 projection. Mutates {@code seamless.uCorner},
     * {@code seamless.vCorner}, {@code seamless.cutTranslationS}, and
     * {@code seamless.cutTranslationT}.
     *
     * @throws ArithmeticException if any projected value lands outside the
     *         chosen F_d range {@code (-d, +d)}, breaking the exact-arithmetic
     *         guarantee (MC19 §7 overflow)
     */
    public void project() {
        int chartVertexCount = cutGraph.chartVertexCount;

        // Initial per-chart-vertex (u, v) from the parametrization's corner arrays.
        // Every corner mapped to the same chart vertex carries the same value after
        // the BZK09 solve.
        double[] chartU = new double[chartVertexCount];
        double[] chartV = new double[chartVertexCount];
        int totalCorners = seamless.faceCount * CORNERS_PER_FACE;
        for (int cornerIdx = 0; cornerIdx < totalCorners; cornerIdx++) {
            int chartVertex = cutGraph.cornerToChartVertex[cornerIdx];
            chartU[chartVertex] = seamless.uCorner[cornerIdx];
            chartV[chartVertex] = seamless.vCorner[cornerIdx];
        }
        double[] chartUInitial = chartU.clone();
        double[] chartVInitial = chartV.clone();

        // Phase 1: walk every branch, collecting (edges, plus-chartVertex sequence,
        // minus-chartVertex sequence, rotation, start/end nodes).
        Set<Integer> singularityVertexIds = new HashSet<>();
        for (Singularity s : crossField.singularities) {
            singularityVertexIds.add(s.vertexId());
        }
        boolean[] walkedCutEdge = new boolean[seamless.edgeCount];
        List<int[]> branchPlusChart = new ArrayList<>();
        List<int[]> branchMinusChart = new ArrayList<>();
        List<Integer> branchRotation = new ArrayList<>();
        for (int seedEdge = 0; seedEdge < seamless.edgeCount; seedEdge++) {
            if (!cutGraph.isCutEdge[seedEdge] || walkedCutEdge[seedEdge]) {
                continue;
            }
            if (seamless.edgeFaceA[seedEdge] < 0 || seamless.edgeFaceB[seedEdge] < 0) {
                continue;
            }
            walkBranchFromSeed(seedEdge, singularityVertexIds, walkedCutEdge,
                    branchPlusChart, branchMinusChart, branchRotation);
        }
        int branchCount = branchPlusChart.size();

        // Phase 2: assign a column index for each (node chart vertex, component) pair.
        Map<Integer, Integer> chartVertexToColumn = new HashMap<>();
        for (int b = 0; b < branchCount; b++) {
            int[] plus = branchPlusChart.get(b);
            int[] minus = branchMinusChart.get(b);
            int endPos = plus.length - 1;
            assignNodeColumn(chartVertexToColumn, plus[0]);
            assignNodeColumn(chartVertexToColumn, minus[0]);
            assignNodeColumn(chartVertexToColumn, plus[endPos]);
            assignNodeColumn(chartVertexToColumn, minus[endPos]);
        }
        int nodeChartVertexCount = chartVertexToColumn.size();
        int reducedVariableCount = COMPONENTS_PER_CHART_VERTEX * nodeChartVertexCount;
        int reducedRowCount = COMPONENTS_PER_CHART_VERTEX * branchCount;

        // Phase 3: build the reduced constraint matrix C̄.
        BigInteger[][] reducedMatrix = new BigInteger[reducedRowCount][reducedVariableCount];
        for (int r = 0; r < reducedRowCount; r++) {
            for (int c = 0; c < reducedVariableCount; c++) {
                reducedMatrix[r][c] = BigInteger.ZERO;
            }
        }
        BigInteger[] reducedRhs = new BigInteger[reducedRowCount];
        for (int i = 0; i < reducedRowCount; i++) {
            reducedRhs[i] = BigInteger.ZERO;
        }
        for (int b = 0; b < branchCount; b++) {
            int[] plus = branchPlusChart.get(b);
            int[] minus = branchMinusChart.get(b);
            int endPos = plus.length - 1;
            int rotation = branchRotation.get(b);
            fillBranchReducedRows(reducedMatrix, b * COMPONENTS_PER_CHART_VERTEX,
                    chartVertexToColumn, plus[0], plus[endPos], minus[0], minus[endPos], rotation);
        }

        // Phase 4: run §4 on C̄ to get exact node values.
        IrrefResult result = ExactArithmetic.reduceToIrref(reducedMatrix, reducedRhs);
        double[] nodeXBar = new double[reducedVariableCount];
        for (Map.Entry<Integer, Integer> entry : chartVertexToColumn.entrySet()) {
            int chartVertex = entry.getKey();
            int columnBase = COMPONENTS_PER_CHART_VERTEX * entry.getValue();
            nodeXBar[columnBase + U_COMPONENT] = chartUInitial[chartVertex];
            nodeXBar[columnBase + V_COMPONENT] = chartVInitial[chartVertex];
        }
        double scale = ExactArithmetic.chooseFdScale(chartUInitial);
        double scaleV = ExactArithmetic.chooseFdScale(chartVInitial);
        if (scaleV > scale) {
            scale = scaleV;
        }
        double[] nodeXExact = ExactArithmetic.evaluate(result, nodeXBar, scale);

        // Phase 5: scatter exact node values into chartU/V.
        for (Map.Entry<Integer, Integer> entry : chartVertexToColumn.entrySet()) {
            int chartVertex = entry.getKey();
            int columnBase = COMPONENTS_PER_CHART_VERTEX * entry.getValue();
            chartU[chartVertex] = nodeXExact[columnBase + U_COMPONENT];
            chartV[chartVertex] = nodeXExact[columnBase + V_COMPONENT];
        }

        // Phase 6: walk each branch forward, filling non-node sector chart vertices.
        for (int b = 0; b < branchCount; b++) {
            int[] plus = branchPlusChart.get(b);
            int[] minus = branchMinusChart.get(b);
            int rotation = branchRotation.get(b);
            backSubstituteBranch(plus, minus, rotation, chartU, chartV,
                    chartUInitial, chartVInitial, scale);
        }

        // Phase 7: overflow check — every projected value must still be in F_d.
        verifyFdRangeContained(chartU, chartV, scale);

        // Phase 8: writeback to per-corner arrays and recompute (s, t) translations.
        for (int cornerIdx = 0; cornerIdx < totalCorners; cornerIdx++) {
            int chartVertex = cutGraph.cornerToChartVertex[cornerIdx];
            seamless.uCorner[cornerIdx] = (float) chartU[chartVertex];
            seamless.vCorner[cornerIdx] = (float) chartV[chartVertex];
        }
        recomputeCutTranslations();
    }

    /**
     * Walk the branch that contains {@code seedEdge}, in both directions if
     * necessary, recording its edges and per-position plus/minus chart vertices
     * into the three lists. Marks every visited edge in {@code walkedCutEdge}.
     *
     * @param seedEdge              an unwalked interior cut edge
     * @param singularityVertexIds  set of mesh vertex ids that are singularities
     * @param walkedCutEdge         per-active-edge marker, updated as edges are visited
     * @param branchPlusChart       accumulator: chain of {@code +}-side chart vertex IDs
     * @param branchMinusChart      accumulator: chain of {@code -}-side chart vertex IDs
     * @param branchRotation        accumulator: branch rotation r ∈ {0..3}
     */
    private void walkBranchFromSeed(int seedEdge, Set<Integer> singularityVertexIds,
            boolean[] walkedCutEdge, List<int[]> branchPlusChart,
            List<int[]> branchMinusChart, List<Integer> branchRotation) {
        int[] seedEndpoints = edgeEndpointsActive(seedEdge);
        int activeVertexA = seedEndpoints[0];
        int activeVertexB = seedEndpoints[1];

        // Walk backward from one endpoint to find the start node (or detect a loop).
        int backwardSeedEdge = seedEdge;
        int backwardSeedVertex = activeVertexA;
        while (!isNode(backwardSeedVertex, singularityVertexIds)) {
            int nextEdge = otherCutEdgeAtActiveVertex(backwardSeedVertex, backwardSeedEdge);
            if (nextEdge < 0) {
                break;
            }
            int[] nextEndpoints = edgeEndpointsActive(nextEdge);
            int otherEndpoint = (nextEndpoints[0] == backwardSeedVertex) ? nextEndpoints[1] : nextEndpoints[0];
            backwardSeedVertex = otherEndpoint;
            backwardSeedEdge = nextEdge;
            if (backwardSeedEdge == seedEdge) {
                throw new IllegalStateException(
                        "MC19: cut graph contains a node-free closed loop (cycle constraint required)");
            }
        }
        int startNodeActiveVertex = backwardSeedVertex;
        int firstActiveEdge = backwardSeedEdge;

        // Walk forward from the start node along the first edge, recording chain.
        int rotation = cutGraph.cutRotation[firstActiveEdge];
        int currentActiveVertex = startNodeActiveVertex;
        int currentActiveEdge = firstActiveEdge;
        int currentPlusActiveFace = seamless.edgeFaceA[firstActiveEdge];
        int currentMinusActiveFace = seamless.edgeFaceB[firstActiveEdge];

        List<Integer> chainPlus = new ArrayList<>();
        List<Integer> chainMinus = new ArrayList<>();
        chainPlus.add(chartVertexOfFaceAtActiveVertex(currentPlusActiveFace, currentActiveVertex));
        chainMinus.add(chartVertexOfFaceAtActiveVertex(currentMinusActiveFace, currentActiveVertex));

        while (true) {
            walkedCutEdge[currentActiveEdge] = true;
            int[] endpoints = edgeEndpointsActive(currentActiveEdge);
            int nextActiveVertex = (endpoints[0] == currentActiveVertex) ? endpoints[1] : endpoints[0];
            int plusAtNext = chartVertexOfFaceAtActiveVertex(currentPlusActiveFace, nextActiveVertex);
            int minusAtNext = chartVertexOfFaceAtActiveVertex(currentMinusActiveFace, nextActiveVertex);
            chainPlus.add(plusAtNext);
            chainMinus.add(minusAtNext);

            if (isNode(nextActiveVertex, singularityVertexIds)) {
                break;
            }

            int nextEdge = otherCutEdgeAtActiveVertex(nextActiveVertex, currentActiveEdge);
            if (nextEdge < 0 || walkedCutEdge[nextEdge]) {
                throw new IllegalStateException(
                        "MC19: branch walk terminated unexpectedly at non-node vertex");
            }
            int[] nextFaces = facesPlusMinusForEdgeAtVertex(nextEdge, nextActiveVertex, plusAtNext);
            currentPlusActiveFace = nextFaces[0];
            currentMinusActiveFace = nextFaces[1];
            currentActiveEdge = nextEdge;
            currentActiveVertex = nextActiveVertex;
        }

        branchPlusChart.add(toIntArray(chainPlus));
        branchMinusChart.add(toIntArray(chainMinus));
        branchRotation.add(rotation);
    }

    /**
     * Fill the two-row block in {@code reducedMatrix} for one branch's
     * {@code T_{m-1}} equation: {@code R_r · (u_0^+ − u_m^+) − (u_0^- − u_m^-) = 0}.
     *
     * @param reducedMatrix         the {@code C̄} matrix being filled
     * @param rowU                  index of this branch's u-component row
     * @param chartVertexToColumn   chart-vertex → column-index-of-node map
     * @param plusStart             chart vertex of the {@code +} sector at the start node
     * @param plusEnd               chart vertex of the {@code +} sector at the end node
     * @param minusStart            chart vertex of the {@code -} sector at the start node
     * @param minusEnd              chart vertex of the {@code -} sector at the end node
     * @param rotation              the branch's integer rotation r ∈ {0..3}
     */
    private static void fillBranchReducedRows(BigInteger[][] reducedMatrix, int rowU,
            Map<Integer, Integer> chartVertexToColumn,
            int plusStart, int plusEnd, int minusStart, int minusEnd, int rotation) {
        BigInteger cos = BigInteger.valueOf(ExactArithmetic.integerCosine(rotation));
        BigInteger sin = BigInteger.valueOf(ExactArithmetic.integerSine(rotation));
        BigInteger negCos = cos.negate();
        BigInteger negSin = sin.negate();
        int colPlusStartU = nodeColumn(chartVertexToColumn, plusStart, U_COMPONENT);
        int colPlusEndU = nodeColumn(chartVertexToColumn, plusEnd, U_COMPONENT);
        int colPlusStartV = nodeColumn(chartVertexToColumn, plusStart, V_COMPONENT);
        int colPlusEndV = nodeColumn(chartVertexToColumn, plusEnd, V_COMPONENT);
        int colMinusStartU = nodeColumn(chartVertexToColumn, minusStart, U_COMPONENT);
        int colMinusEndU = nodeColumn(chartVertexToColumn, minusEnd, U_COMPONENT);
        int colMinusStartV = nodeColumn(chartVertexToColumn, minusStart, V_COMPONENT);
        int colMinusEndV = nodeColumn(chartVertexToColumn, minusEnd, V_COMPONENT);

        // U-component: cos·(u^+_0 − u^+_m) − sin·(v^+_0 − v^+_m) − (u^-_0 − u^-_m) = 0
        reducedMatrix[rowU][colPlusStartU] = reducedMatrix[rowU][colPlusStartU].add(cos);
        reducedMatrix[rowU][colPlusEndU] = reducedMatrix[rowU][colPlusEndU].add(negCos);
        reducedMatrix[rowU][colPlusStartV] = reducedMatrix[rowU][colPlusStartV].add(negSin);
        reducedMatrix[rowU][colPlusEndV] = reducedMatrix[rowU][colPlusEndV].add(sin);
        reducedMatrix[rowU][colMinusStartU] = reducedMatrix[rowU][colMinusStartU].subtract(BigInteger.ONE);
        reducedMatrix[rowU][colMinusEndU] = reducedMatrix[rowU][colMinusEndU].add(BigInteger.ONE);

        // V-component: sin·(u^+_0 − u^+_m) + cos·(v^+_0 − v^+_m) − (v^-_0 − v^-_m) = 0
        int rowV = rowU + 1;
        reducedMatrix[rowV][colPlusStartU] = reducedMatrix[rowV][colPlusStartU].add(sin);
        reducedMatrix[rowV][colPlusEndU] = reducedMatrix[rowV][colPlusEndU].add(negSin);
        reducedMatrix[rowV][colPlusStartV] = reducedMatrix[rowV][colPlusStartV].add(cos);
        reducedMatrix[rowV][colPlusEndV] = reducedMatrix[rowV][colPlusEndV].add(negCos);
        reducedMatrix[rowV][colMinusStartV] = reducedMatrix[rowV][colMinusStartV].subtract(BigInteger.ONE);
        reducedMatrix[rowV][colMinusEndV] = reducedMatrix[rowV][colMinusEndV].add(BigInteger.ONE);
    }

    /**
     * Walk one branch forward, filling its non-node positions: snap each
     * {@code +} sector to F_d (it stays a free variable), then derive each
     * {@code -} sector from the previous via
     * {@code (u^-_k, v^-_k) = (u^-_{k-1}, v^-_{k-1}) + R_r · ((u^+_k, v^+_k) − (u^+_{k-1}, v^+_{k-1}))}.
     *
     * @param plusChart    {@code +} sector chart vertex per position, length m+1
     * @param minusChart   {@code -} sector chart vertex per position, length m+1
     * @param rotation     branch rotation r ∈ {0..3}
     * @param chartU       mutable per-chart-vertex u array; node entries already set
     * @param chartV       mutable per-chart-vertex v array; node entries already set
     * @param chartUInitial original u values, for snapping non-node {@code +} sides
     * @param chartVInitial original v values
     * @param scale        F_d scale {@code d}
     */
    private static void backSubstituteBranch(int[] plusChart, int[] minusChart, int rotation,
            double[] chartU, double[] chartV,
            double[] chartUInitial, double[] chartVInitial, double scale) {
        int cos = ExactArithmetic.integerCosine(rotation);
        int sin = ExactArithmetic.integerSine(rotation);
        int positionCount = plusChart.length;
        int endPos = positionCount - 1;
        double previousPlusU = chartU[plusChart[0]];
        double previousPlusV = chartV[plusChart[0]];
        double previousMinusU = chartU[minusChart[0]];
        double previousMinusV = chartV[minusChart[0]];
        for (int position = 1; position < positionCount; position++) {
            int plusCv = plusChart[position];
            int minusCv = minusChart[position];
            double currentPlusU;
            double currentPlusV;
            if (position == endPos) {
                currentPlusU = chartU[plusCv];
                currentPlusV = chartV[plusCv];
            } else {
                currentPlusU = ExactArithmetic.truncateToFd(chartUInitial[plusCv], scale);
                currentPlusV = ExactArithmetic.truncateToFd(chartVInitial[plusCv], scale);
                chartU[plusCv] = currentPlusU;
                chartV[plusCv] = currentPlusV;
            }
            double deltaPlusU = currentPlusU - previousPlusU;
            double deltaPlusV = currentPlusV - previousPlusV;
            double impliedMinusU = previousMinusU + cos * deltaPlusU - sin * deltaPlusV;
            double impliedMinusV = previousMinusV + sin * deltaPlusU + cos * deltaPlusV;
            if (position != endPos) {
                chartU[minusCv] = impliedMinusU;
                chartV[minusCv] = impliedMinusV;
            }
            previousPlusU = currentPlusU;
            previousPlusV = currentPlusV;
            previousMinusU = impliedMinusU;
            previousMinusV = impliedMinusV;
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
            if (!cutGraph.isCutEdge[activeEdge]) {
                continue;
            }
            int faceA = seamless.edgeFaceA[activeEdge];
            int faceB = seamless.edgeFaceB[activeEdge];
            if (faceA < 0 || faceB < 0) {
                continue;
            }
            int cornerAStart = seamless.edgeCornerInA[activeEdge];
            int cornerBStart = seamless.edgeCornerInB[activeEdge];
            int rotation = cutGraph.cutRotation[activeEdge];
            int cos = ExactArithmetic.integerCosine(rotation);
            int sin = ExactArithmetic.integerSine(rotation);
            float uA = seamless.uCorner[faceA * CORNERS_PER_FACE + cornerAStart];
            float vA = seamless.vCorner[faceA * CORNERS_PER_FACE + cornerAStart];
            float uB = seamless.uCorner[faceB * CORNERS_PER_FACE + cornerBStart];
            float vB = seamless.vCorner[faceB * CORNERS_PER_FACE + cornerBStart];
            seamless.cutTranslationS[activeEdge] = uB - (cos * uA - sin * vA);
            seamless.cutTranslationT[activeEdge] = vB - (sin * uA + cos * vA);
        }
    }

    /**
     * Check that every projected value still lies in the chosen F_d range
     * {@code (-d, +d)} — the only condition under which the safeDot / makeDiv
     * machinery in {@link ExactArithmetic} produces exact results. The paper's
     * §7 worst-case condition {@code ‖x − x̄‖∞ / max|x̄| < 1} is the special case
     * where {@code d = 2·max|x̄|}; our larger {@code d} (for float-cast safety)
     * gives correspondingly more headroom.
     *
     * @param uExact projected u values
     * @param vExact projected v values
     * @param scale  the F_d scale {@code d}
     * @throws ArithmeticException if any output magnitude reaches {@code d}
     */
    private static void verifyFdRangeContained(double[] uExact, double[] vExact, double scale) {
        for (int i = 0; i < uExact.length; i++) {
            if (Math.abs(uExact[i]) >= scale || Math.abs(vExact[i]) >= scale) {
                throw new ArithmeticException(
                        "MC19 projection produced a value outside F_d range (-d, +d); d = "
                                + scale + ", offending index " + i);
            }
        }
    }

    /**
     * Test whether an active vertex is a node in the MC19 §5.3 sense (cut-degree
     * not equal to 2, or a singularity).
     *
     * @param activeVertex          dense vertex index
     * @param singularityVertexIds  set of mesh vertex ids that are singularities
     * @return {@code true} iff the vertex is a node
     */
    private boolean isNode(int activeVertex, Set<Integer> singularityVertexIds) {
        if (cutGraph.cutDegree[activeVertex] != 2) {
            return true;
        }
        int vertexId = mesh.vertexIdAt(activeVertex);
        return singularityVertexIds.contains(vertexId);
    }

    /**
     * Return the two active-vertex endpoints of an active edge.
     *
     * @param activeEdge dense edge index
     * @return length-2 array of active-vertex indices
     */
    private int[] edgeEndpointsActive(int activeEdge) {
        int edgeId = mesh.edgeIdAt(activeEdge);
        int halfEdge = mesh.edgeHalfEdge(edgeId);
        int vStartId = mesh.halfEdgeVertex(halfEdge);
        int vEndId = mesh.halfEdgeEndVertex(halfEdge);
        return new int[] {
                cutGraph.activeVertexIndex(vStartId),
                cutGraph.activeVertexIndex(vEndId),
        };
    }

    /**
     * At a cut-degree-2 active vertex, return the cut edge other than
     * {@code currentActiveEdge}. Returns {@code -1} if no such edge exists
     * (degenerate / boundary case).
     *
     * @param activeVertex      the vertex to inspect
     * @param currentActiveEdge the cut edge to exclude
     * @return the other interior cut edge, or {@code -1}
     */
    private int otherCutEdgeAtActiveVertex(int activeVertex, int currentActiveEdge) {
        int vertexId = mesh.vertexIdAt(activeVertex);
        int incidentCount = mesh.vertexEdgeCount(vertexId);
        for (int i = 0; i < incidentCount; i++) {
            int edgeId = mesh.vertexEdgeAt(vertexId, i);
            if (mesh.isBoundaryEdge(edgeId)) {
                continue;
            }
            int activeEdge = crossField.edgeIdToActive.get(edgeId);
            if (activeEdge == currentActiveEdge) {
                continue;
            }
            if (!cutGraph.isCutEdge[activeEdge]) {
                continue;
            }
            if (seamless.edgeFaceA[activeEdge] < 0 || seamless.edgeFaceB[activeEdge] < 0) {
                continue;
            }
            return activeEdge;
        }
        return -1;
    }

    /**
     * Chart vertex of a face's corner at a given active vertex.
     *
     * @param activeFace    dense face index
     * @param activeVertex  dense vertex index
     * @return chart vertex id at the (face, vertex) corner
     */
    private int chartVertexOfFaceAtActiveVertex(int activeFace, int activeVertex) {
        int faceId = mesh.faceIdAt(activeFace);
        int vertexId = mesh.vertexIdAt(activeVertex);
        for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
            if (mesh.faceVertexAt(faceId, corner) == vertexId) {
                return cutGraph.cornerToChartVertex[activeFace * CORNERS_PER_FACE + corner];
            }
        }
        throw new IllegalStateException(
                "face " + activeFace + " has no corner at active vertex " + activeVertex);
    }

    /**
     * Pick which of {@code activeEdge}'s two faces lies on the {@code +} side of
     * the branch, by matching chart-vertex continuity at {@code activeVertex}.
     *
     * @param activeEdge          dense edge index
     * @param activeVertex        dense vertex index
     * @param plusChartAtVertex   the {@code +} sector chart vertex at this vertex
     * @return length-2 array {@code [plusFace, minusFace]} (active face indices)
     */
    private int[] facesPlusMinusForEdgeAtVertex(int activeEdge, int activeVertex, int plusChartAtVertex) {
        int faceA = seamless.edgeFaceA[activeEdge];
        int faceB = seamless.edgeFaceB[activeEdge];
        int chartA = chartVertexOfFaceAtActiveVertex(faceA, activeVertex);
        if (chartA == plusChartAtVertex) {
            return new int[] { faceA, faceB };
        }
        int chartB = chartVertexOfFaceAtActiveVertex(faceB, activeVertex);
        if (chartB == plusChartAtVertex) {
            return new int[] { faceB, faceA };
        }
        throw new IllegalStateException(
                "neither face of active edge " + activeEdge + " carries chart vertex "
                        + plusChartAtVertex + " at active vertex " + activeVertex);
    }

    /**
     * Assign a column index to a chart vertex in the reduced node-only system
     * if it does not already have one.
     *
     * @param chartVertexToColumn the map being built
     * @param chartVertex         the chart vertex to register
     */
    private static void assignNodeColumn(Map<Integer, Integer> chartVertexToColumn, int chartVertex) {
        if (!chartVertexToColumn.containsKey(chartVertex)) {
            chartVertexToColumn.put(chartVertex, chartVertexToColumn.size());
        }
    }

    /**
     * Resolve a (chart vertex, component) pair to a flat column index in the
     * reduced system.
     *
     * @param chartVertexToColumn the chart-vertex → column-of-node map
     * @param chartVertex         the chart vertex
     * @param component           {@link #U_COMPONENT} or {@link #V_COMPONENT}
     * @return the flat column index in {@code C̄}
     */
    private static int nodeColumn(Map<Integer, Integer> chartVertexToColumn,
            int chartVertex, int component) {
        return COMPONENTS_PER_CHART_VERTEX * chartVertexToColumn.get(chartVertex) + component;
    }

    /**
     * Convert an integer list to a primitive array.
     *
     * @param list source list of integers
     * @return a freshly allocated {@code int[]} with the same contents
     */
    private static int[] toIntArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i);
        }
        return out;
    }
}
