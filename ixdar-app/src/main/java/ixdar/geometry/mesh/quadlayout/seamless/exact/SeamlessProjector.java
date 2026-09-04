package ixdar.geometry.mesh.quadlayout.seamless.exact;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.CutGraph;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessUv;
import ixdar.platform.Platforms;

/**
 * Projects an approximately-seamless parametrization onto the exactly-seamless
 * solution space. After {@link #project()}, the seam transition equation across
 * every interior cut edge holds exactly in real arithmetic, with every output
 * still representable as a standard {@code float}. Results are written back
 * into the parent {@link SeamlessParameterization}.
 *
 * <p>
 * See also: MC19 Section 5.3.1
 */
public final class SeamlessProjector {

    private static final int CORNERS_PER_FACE = SeamlessUv.CORNERS_PER_FACE;
    private static final int COMPONENTS_PER_CHART_VERTEX = 2;
    private static final int U_COMPONENT = 0;
    private static final int V_COMPONENT = 1;
    private static final int ROSY_ROTATION_COUNT = 4;
    /**
     * MC19 §5.4: post-projection injectivity repair caps. {@code MAX_UNFLIP_PASSES}
     * bounds the outer fixed-point loop; each pass scans the flipped face list once
     * and attempts a single vertex move per face.
     */
    private static final int MAX_UNFLIP_PASSES = 20;
    /**
     * Padding factor on the bounding rectangle used to seed the Sutherland-Hodgman
     * kernel-polygon clip — fraction of bbox extent added on each side so the seed
     * contains all link vertices with margin.
     */
    private static final double KERNEL_BBOX_PAD_FRACTION = 0.1;
    /**
     * Strict-positivity margin (relative to local edge magnitude) used when
     * accepting a kernel point; ensures the moved vertex lands strictly inside
     * every half-plane rather than on a boundary line, avoiding zero-area follow-up
     * triangles.
     */
    private static final double KERNEL_INTERIOR_MARGIN = 1.0e-9;
    public final SeamlessParameterization seamless;
    public final CutGraph cutGraph;
    public final CrossField crossField;
    public final HalfEdgeMesh mesh;

    /**
     * Bind this projector to a built {@link SeamlessParameterization}. The
     * projector mutates {@code seamless.uv.uCorner}, {@code seamless.uv.vCorner},
     * {@code seamless.uv.cutTranslationS}, and {@code seamless.uv.cutTranslationT} when
     * {@link #project()} is called.
     *
     * @param seamless the parametrization to project onto the exact-constraint
     *                 manifold; must have already had
     *                 {@link SeamlessParameterization#build()} run
     */
    public SeamlessProjector(SeamlessParameterization seamless) {
        this.seamless = seamless;
        this.cutGraph = seamless.uv.cutGraph;
        this.crossField = seamless.field;
        this.mesh = seamless.mesh;
    }

    /**
     * Run the MC19 §5.3.1 projection. Mutates {@code seamless.uv.uCorner},
     * {@code seamless.uv.vCorner}, {@code seamless.uv.cutTranslationS}, and
     * {@code seamless.uv.cutTranslationT}.
     *
     * @throws ArithmeticException if any projected value lands outside the chosen
     *                             F_d range {@code (-d, +d)}, breaking the
     *                             exact-arithmetic guarantee (MC19 §7 overflow)
     */
    public void project() {
        int chartVertexCount = cutGraph.chartVertexCount;

        // Initial per-chart-vertex (u, v) from the parametrization's corner arrays.
        // Every corner mapped to the same chart vertex carries the same value after
        // the BZK09 solve.
        double[] chartU = new double[chartVertexCount];
        double[] chartV = new double[chartVertexCount];
        int totalCorners = seamless.uv.faceCount * CORNERS_PER_FACE;
        for (int cornerIdx = 0; cornerIdx < totalCorners; cornerIdx++) {
            int chartVertex = cutGraph.cornerToChartVertex[cornerIdx];
            chartU[chartVertex] = seamless.uv.uCorner[cornerIdx];
            chartV[chartVertex] = seamless.uv.vCorner[cornerIdx];
        }
        double[] chartUInitial = chartU.clone();
        double[] chartVInitial = chartV.clone();

        // Phase 1: walk every branch, collecting (edges, plus-chartVertex sequence,
        // minus-chartVertex sequence, rotation, start/end nodes).
        Set<Integer> singularityVertexIds = crossField.singularVertexIds();
        boolean[] walkedCutEdge = new boolean[seamless.uv.edgeCount];
        List<int[]> branchPlusChart = new ArrayList<>();
        List<int[]> branchMinusChart = new ArrayList<>();
        List<Integer> branchRotation = new ArrayList<>();
        for (int seedEdge = 0; seedEdge < seamless.uv.edgeCount; seedEdge++) {
            if (!cutGraph.isCutEdge[seedEdge] || walkedCutEdge[seedEdge]) {
                continue;
            }
            if (seamless.uv.edgeFaceA[seedEdge] < 0 || seamless.uv.edgeFaceB[seedEdge] < 0) {
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

        // Phase 7: MC19 §5.4 injectivity repair. §5.3's exact-equality projection
        // makes no inequality guarantee — small adjustments can introduce local
        // foldovers. MC19 §5.4: for each inverted face, move one of its
        // interior, non-cut chart vertices into the kernel of its 1-ring (the
        // intersection of half-planes spanned by link edges). Repeat until
        // either no flips remain or a pass makes no progress.
        repairFlipsInChartSpace(chartU, chartV);

        // Phase 8: overflow check — every projected value must still be in F_d.
        verifyFdRangeContained(chartU, chartV, scale);

        // Phase 9: writeback to per-corner arrays and recompute (s, t) translations.
        for (int cornerIdx = 0; cornerIdx < totalCorners; cornerIdx++) {
            int chartVertex = cutGraph.cornerToChartVertex[cornerIdx];
            seamless.uv.uCorner[cornerIdx] = chartU[chartVertex];
            seamless.uv.vCorner[cornerIdx] = chartV[chartVertex];
        }
        recomputeCutTranslations();
    }

    /**
     * Walk the branch that contains {@code seedEdge}, in both directions if
     * necessary, recording its edges and per-position plus/minus chart vertices
     * into the three lists. Marks every visited edge in {@code walkedCutEdge}.
     *
     * @param seedEdge             an unwalked interior cut edge
     * @param singularityVertexIds set of mesh vertex ids that are singularities
     * @param walkedCutEdge        per-active-edge marker, updated as edges are
     *                             visited
     * @param branchPlusChart      accumulator: chain of {@code +}-side chart vertex
     *                             IDs
     * @param branchMinusChart     accumulator: chain of {@code -}-side chart vertex
     *                             IDs
     * @param branchRotation       accumulator: branch rotation r ∈ {0..3}
     */
    private void walkBranchFromSeed(int seedEdge, Set<Integer> singularityVertexIds,
            boolean[] walkedCutEdge, List<int[]> branchPlusChart,
            List<int[]> branchMinusChart, List<Integer> branchRotation) {
        int[] seedEndpoints = edgeEndpointsActive(seedEdge);
        int activeVertexA = seedEndpoints[0];

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
        int currentActiveVertex = startNodeActiveVertex;
        int currentActiveEdge = firstActiveEdge;
        int currentPlusActiveFace = seamless.uv.edgeFaceA[firstActiveEdge];
        int currentMinusActiveFace = seamless.uv.edgeFaceB[firstActiveEdge];

        List<Integer> chainPlus = new ArrayList<>();
        List<Integer> chainMinus = new ArrayList<>();
        chainPlus.add(chartVertexOfFaceAtActiveVertex(currentPlusActiveFace, currentActiveVertex));
        chainMinus.add(chartVertexOfFaceAtActiveVertex(currentMinusActiveFace, currentActiveVertex));

        int branchEffectiveRotation = -1;
        while (true) {
            int effectiveRotation = effectivePlusToMinusRotation(currentActiveEdge, currentPlusActiveFace);
            if (branchEffectiveRotation < 0) {
                branchEffectiveRotation = effectiveRotation;
            } else if (effectiveRotation != branchEffectiveRotation) {
                throw new IllegalStateException(
                        "MC19: branch has inconsistent plus→minus rotations along chain: "
                                + branchEffectiveRotation + " vs " + effectiveRotation
                                + " at active edge " + currentActiveEdge);
            }

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
        branchRotation.add(branchEffectiveRotation);
    }

    /**
     * Rotation from the {@code +} sector to the {@code -} sector for one chain
     * edge. {@link CutGraph#cutRotation} is stored A-to-B, so it applies directly
     * only when the chain labels {@code A} as plus; otherwise the answer is its
     * inverse.
     *
     * @param activeEdge     dense edge index in the chain
     * @param plusActiveFace active face that has been labeled the {@code +} side at
     *                       this edge
     * @return rotation r ∈ {0..3} such that f_minus = R_r · f_plus + t on this edge
     */
    private int effectivePlusToMinusRotation(int activeEdge, int plusActiveFace) {
        int edgeRotation = cutGraph.cutRotation[activeEdge];
        if (plusActiveFace == seamless.uv.edgeFaceA[activeEdge]) {
            return edgeRotation;
        }
        return (ROSY_ROTATION_COUNT - edgeRotation) % ROSY_ROTATION_COUNT;
    }

    /**
     * Fill the two-row block in {@code reducedMatrix} for one branch's
     * {@code T_{m-1}} equation:
     * {@code R_r · (u_0^+ − u_m^+) − (u_0^- − u_m^-) = 0}.
     *
     * @param reducedMatrix       the {@code C̄} matrix being filled
     * @param rowU                index of this branch's u-component row
     * @param chartVertexToColumn chart-vertex → column-index-of-node map
     * @param plusStart           chart vertex of the {@code +} sector at the start
     *                            node
     * @param plusEnd             chart vertex of the {@code +} sector at the end
     *                            node
     * @param minusStart          chart vertex of the {@code -} sector at the start
     *                            node
     * @param minusEnd            chart vertex of the {@code -} sector at the end
     *                            node
     * @param rotation            the branch's integer rotation r ∈ {0..3}
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
     * Walk one branch forward, filling its non-node positions: snap each {@code +}
     * sector to F_d (it stays a free variable), then derive each {@code -} sector
     * from the previous via
     * {@code (u^-_k, v^-_k) = (u^-_{k-1}, v^-_{k-1}) + R_r · ((u^+_k, v^+_k) − (u^+_{k-1}, v^+_{k-1}))}.
     *
     * @param plusChart     {@code +} sector chart vertex per position, length m+1
     * @param minusChart    {@code -} sector chart vertex per position, length m+1
     * @param rotation      branch rotation r ∈ {0..3}
     * @param chartU        mutable per-chart-vertex u array; node entries already
     *                      set
     * @param chartV        mutable per-chart-vertex v array; node entries already
     *                      set
     * @param chartUInitial original u values, for snapping non-node {@code +} sides
     * @param chartVInitial original v values
     * @param scale         F_d scale {@code d}
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
        for (int activeEdge = 0; activeEdge < seamless.uv.edgeCount; activeEdge++) {
            if (!cutGraph.isCutEdge[activeEdge]) {
                continue;
            }
            int faceA = seamless.uv.edgeFaceA[activeEdge];
            int faceB = seamless.uv.edgeFaceB[activeEdge];
            if (faceA < 0 || faceB < 0) {
                continue;
            }
            int cornerAStart = seamless.uv.edgeCornerInA[activeEdge];
            int cornerBStart = seamless.uv.edgeCornerInB[activeEdge];
            int rotation = cutGraph.cutRotation[activeEdge];
            int cos = ExactArithmetic.integerCosine(rotation);
            int sin = ExactArithmetic.integerSine(rotation);
            double uA = seamless.uv.uCorner[faceA * CORNERS_PER_FACE + cornerAStart];
            double vA = seamless.uv.vCorner[faceA * CORNERS_PER_FACE + cornerAStart];
            double uB = seamless.uv.uCorner[faceB * CORNERS_PER_FACE + cornerBStart];
            double vB = seamless.uv.vCorner[faceB * CORNERS_PER_FACE + cornerBStart];
            seamless.uv.cutTranslationS[activeEdge] = uB - (cos * uA - sin * vA);
            seamless.uv.cutTranslationT[activeEdge] = vB - (sin * uA + cos * vA);
        }
    }

    /**
     * Check that every projected value still lies in the chosen F_d range
     * {@code (-d, +d)}, the only condition under which {@link ExactArithmetic}
     * produces exact results.
     *
     * <p>
     * See also: MC19 Section 7
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
     * @param activeVertex         dense vertex index
     * @param singularityVertexIds set of mesh vertex ids that are singularities
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
            if (seamless.uv.edgeFaceA[activeEdge] < 0 || seamless.uv.edgeFaceB[activeEdge] < 0) {
                continue;
            }
            return activeEdge;
        }
        return -1;
    }

    /**
     * Chart vertex of a face's corner at a given active vertex.
     *
     * @param activeFace   dense face index
     * @param activeVertex dense vertex index
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
     * @param activeEdge        dense edge index
     * @param activeVertex      dense vertex index
     * @param plusChartAtVertex the {@code +} sector chart vertex at this vertex
     * @return length-2 array {@code [plusFace, minusFace]} (active face indices)
     */
    private int[] facesPlusMinusForEdgeAtVertex(int activeEdge, int activeVertex, int plusChartAtVertex) {
        int faceA = seamless.uv.edgeFaceA[activeEdge];
        int faceB = seamless.uv.edgeFaceB[activeEdge];
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
     * Assign a column index to a chart vertex in the reduced node-only system if it
     * does not already have one.
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

    /**
     * Each pass collects every flipped face and attempts to move one of its
     * interior, non-cut chart vertices into the kernel of its 1-ring, until no
     * flips remain or a pass produces no successful move.
     *
     * <p>
     * See also: MC19 Section 5.4
     *
     * @param chartU mutable per-chart-vertex u, updated in place on successful
     *               moves
     * @param chartV mutable per-chart-vertex v, updated in place on successful
     *               moves
     */
    private void repairFlipsInChartSpace(double[] chartU, double[] chartV) {
        int initialFlipped = -1;
        int repaired = 0;
        for (int pass = 0; pass < MAX_UNFLIP_PASSES; pass++) {
            List<Integer> flipped = findFlippedFaces(chartU, chartV);
            if (initialFlipped < 0) {
                initialFlipped = flipped.size();
                if (initialFlipped == 0) {
                    return;
                }
            }
            if (flipped.isEmpty()) {
                break;
            }
            boolean anyFixed = false;
            for (int activeFace : flipped) {
                if (tryUnflipFace(activeFace, chartU, chartV)) {
                    anyFixed = true;
                    repaired++;
                }
            }
            if (!anyFixed) {
                break;
            }
        }
        if (initialFlipped > 0) {
            int remaining = findFlippedFaces(chartU, chartV).size();
            Platforms.log("[seamless] §5.4 repair: %d initial flips, %d moves, %d remaining%n",
                    initialFlipped, repaired, remaining);
        }
    }

    /**
     * Enumerate active faces whose chart-space signed area is non-positive (flipped
     * or degenerate).
     *
     * @param chartU per-chart-vertex u
     * @param chartV per-chart-vertex v
     * @return list of active-face indices with signed area &lt;= 0
     */
    private List<Integer> findFlippedFaces(double[] chartU, double[] chartV) {
        List<Integer> out = new ArrayList<>();
        for (int activeFace = 0; activeFace < seamless.uv.faceCount; activeFace++) {
            if (isFaceFlippedOrDegenerate(activeFace, chartU, chartV)) {
                out.add(activeFace);
            }
        }
        return out;
    }

    /**
     * Test whether one active face has non-positive signed area in chart space.
     *
     * @param activeFace dense face index
     * @param chartU     per-chart-vertex u
     * @param chartV     per-chart-vertex v
     * @return {@code true} iff the chart-space orientation is non-CCW
     */
    private boolean isFaceFlippedOrDegenerate(int activeFace, double[] chartU, double[] chartV) {
        int base = activeFace * CORNERS_PER_FACE;
        int cv0 = cutGraph.cornerToChartVertex[base];
        int cv1 = cutGraph.cornerToChartVertex[base + 1];
        int cv2 = cutGraph.cornerToChartVertex[base + 2];
        return orient2dHelper(chartU[cv0], chartV[cv0],
                chartU[cv1], chartV[cv1], chartU[cv2], chartV[cv2]) <= 0.0;
    }

    /**
     * Walk this face's three corners, look up each corresponding chart vertex, and
     * if the corner's mesh vertex is interior to the mesh AND not on the cut graph,
     * attempt to compute its 1-ring kernel and move it. Returns on the first
     * successful move.
     *
     * @param activeFace flipped face being repaired
     * @param chartU     per-chart-vertex u, mutated on success
     * @param chartV     per-chart-vertex v, mutated on success
     * @return {@code true} iff one of the corners was successfully moved
     */
    private boolean tryUnflipFace(int activeFace, double[] chartU, double[] chartV) {
        int base = activeFace * CORNERS_PER_FACE;
        int faceId = mesh.faceIdAt(activeFace);
        int cv0 = cutGraph.cornerToChartVertex[base];
        int cv1 = cutGraph.cornerToChartVertex[base + 1];
        int cv2 = cutGraph.cornerToChartVertex[base + 2];
        boolean cornersCollapsed = cv0 == cv1 || cv1 == cv2 || cv2 == cv0;
        for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
            int chartVertex = cutGraph.cornerToChartVertex[base + corner];
            int vertexId = mesh.faceVertexAt(faceId, corner);
            String skipReason = null;
            if (mesh.isBoundaryVertex(vertexId)) {
                skipReason = "boundary";
            } else {
                int activeVertex = cutGraph.activeVertexIndex(vertexId);
                if (cutGraph.cutDegree[activeVertex] > 0) {
                    skipReason = "cutDegree=" + cutGraph.cutDegree[activeVertex];
                }
            }
            if (skipReason != null) {
                Platforms.log(
                        "[seamless] §5.4 repair: face %d corner=%d cv=%d v=%d skipped (%s)%n",
                        activeFace, corner, chartVertex, vertexId, skipReason);
                continue;
            }
            double[] kernelPoint = computeOneRingKernelPoint(chartVertex, vertexId, chartU, chartV);
            if (kernelPoint == null) {
                Platforms.log(
                        "[seamless] §5.4 repair: face %d corner=%d cv=%d v=%d kernel empty"
                                + " (cornersCollapsed=%s; cv0=%d cv1=%d cv2=%d)%n",
                        activeFace, corner, chartVertex, vertexId, cornersCollapsed,
                        cv0, cv1, cv2);
                continue;
            }
            double previousU = chartU[chartVertex];
            double previousV = chartV[chartVertex];
            chartU[chartVertex] = kernelPoint[0];
            chartV[chartVertex] = kernelPoint[1];
            if (isFaceFlippedOrDegenerate(activeFace, chartU, chartV)) {
                // Move didn't actually un-flip — kernel computation produced a
                // point that doesn't satisfy this face's constraint. Roll back
                // and try the next corner so we don't oscillate.
                Platforms.log(
                        "[seamless] §5.4 repair: face %d corner=%d cv=%d v=%d move rolled back"
                                + " (cornersCollapsed=%s; cv0=%d cv1=%d cv2=%d):"
                                + " old=(%.4e,%.4e) new=(%.4e,%.4e)%n",
                        activeFace, corner, chartVertex, vertexId, cornersCollapsed,
                        cv0, cv1, cv2, previousU, previousV, kernelPoint[0], kernelPoint[1]);
                chartU[chartVertex] = previousU;
                chartV[chartVertex] = previousV;
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Centroid of the 1-ring kernel: the intersection of the half-planes left of
     * each link edge of {@code chartVertex} in chart space, computed by
     * Sutherland-Hodgman clipping of a bounding rectangle.
     *
     * <p>
     * See also: MC19 Section 5.4
     *
     * @param chartVertex chart vertex to move
     * @param vertexId    mesh vertex id this chart vertex corresponds to (since we
     *                    filtered to non-cut interior vertices, there is exactly
     *                    one mesh vertex per chart vertex)
     * @param chartU      per-chart-vertex u
     * @param chartV      per-chart-vertex v
     * @return {@code [u, v]} of a kernel point, or {@code null} when the
     *         intersection is empty
     */
    private double[] computeOneRingKernelPoint(int chartVertex, int vertexId,
            double[] chartU, double[] chartV) {
        int faceCount = mesh.vertexFaceCount(vertexId);
        if (faceCount < 3) {
            return null;
        }
        int[] linkAChart = new int[faceCount];
        int[] linkBChart = new int[faceCount];
        for (int i = 0; i < faceCount; i++) {
            int faceId = mesh.vertexFaceAt(vertexId, i);
            int activeFace = crossField.faceIdToActive.get(faceId);
            int base = activeFace * CORNERS_PER_FACE;
            int cornerCv = -1;
            for (int c = 0; c < CORNERS_PER_FACE; c++) {
                if (mesh.faceVertexAt(faceId, c) == vertexId) {
                    cornerCv = c;
                    break;
                }
            }
            if (cornerCv < 0) {
                return null;
            }
            int linkACorner = (cornerCv + 1) % CORNERS_PER_FACE;
            int linkBCorner = (cornerCv + 2) % CORNERS_PER_FACE;
            // For CCW-oriented face (cv, A, B), the link edge is A→B; chart
            // vertex moves to the LEFT of A→B keep the face CCW.
            linkAChart[i] = cutGraph.cornerToChartVertex[base + linkACorner];
            linkBChart[i] = cutGraph.cornerToChartVertex[base + linkBCorner];
        }

        double minU = Double.POSITIVE_INFINITY;
        double maxU = Double.NEGATIVE_INFINITY;
        double minV = Double.POSITIVE_INFINITY;
        double maxV = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < faceCount; i++) {
            int a = linkAChart[i];
            int b = linkBChart[i];
            minU = Math.min(minU, Math.min(chartU[a], chartU[b]));
            maxU = Math.max(maxU, Math.max(chartU[a], chartU[b]));
            minV = Math.min(minV, Math.min(chartV[a], chartV[b]));
            maxV = Math.max(maxV, Math.max(chartV[a], chartV[b]));
        }
        double padU = Math.max((maxU - minU), 1.0) * KERNEL_BBOX_PAD_FRACTION;
        double padV = Math.max((maxV - minV), 1.0) * KERNEL_BBOX_PAD_FRACTION;
        List<double[]> poly = new ArrayList<>(4);
        poly.add(new double[] { minU - padU, minV - padV });
        poly.add(new double[] { maxU + padU, minV - padV });
        poly.add(new double[] { maxU + padU, maxV + padV });
        poly.add(new double[] { minU - padU, maxV + padV });

        for (int i = 0; i < faceCount; i++) {
            int a = linkAChart[i];
            int b = linkBChart[i];
            double margin = KERNEL_INTERIOR_MARGIN
                    * Math.hypot(chartU[b] - chartU[a], chartV[b] - chartV[a]);
            poly = clipPolygonByLeftHalfPlane(poly,
                    chartU[a], chartV[a], chartU[b], chartV[b], margin);
            if (poly.isEmpty()) {
                return null;
            }
        }

        double cu = 0.0;
        double cv = 0.0;
        for (double[] p : poly) {
            cu += p[0];
            cv += p[1];
        }
        cu /= poly.size();
        cv /= poly.size();
        return new double[] { cu, cv };
    }

    /**
     * Sutherland-Hodgman clip of {@code poly} by the half-plane strictly to the
     * left of directed line {@code (ax, ay) → (bx, by)} with a positive
     * {@code margin} so output points lie strictly interior to the half-plane.
     *
     * @param poly   convex polygon vertices in order
     * @param ax     line start x
     * @param ay     line start y
     * @param bx     line end x
     * @param by     line end y
     * @param margin minimum orient2d value an output point must satisfy
     * @return clipped polygon (possibly empty)
     */
    private static List<double[]> clipPolygonByLeftHalfPlane(List<double[]> poly,
            double ax, double ay, double bx, double by, double margin) {
        List<double[]> out = new ArrayList<>(poly.size() + 1);
        int n = poly.size();
        if (n == 0) {
            return out;
        }
        for (int i = 0; i < n; i++) {
            double[] p0 = poly.get(i);
            double[] p1 = poly.get((i + 1) % n);
            double c0 = orient2dHelper(ax, ay, bx, by, p0[0], p0[1]);
            double c1 = orient2dHelper(ax, ay, bx, by, p1[0], p1[1]);
            boolean p0In = c0 >= margin;
            boolean p1In = c1 >= margin;
            if (p0In) {
                out.add(p0);
            }
            if (p0In != p1In) {
                double denom = c0 - c1;
                if (denom == 0.0) {
                    continue;
                }
                double t = (c0 - margin) / denom;
                out.add(new double[] {
                        p0[0] + t * (p1[0] - p0[0]),
                        p0[1] + t * (p1[1] - p0[1]),
                });
            }
        }
        return out;
    }

    /**
     * Signed-doubled-area orient2d, positive when {@code (px, py)} lies to the left
     * of directed line {@code (ax, ay) → (bx, by)}.
     *
     * @param ax line start x
     * @param ay line start y
     * @param bx line end x
     * @param by line end y
     * @param px query x
     * @param py query y
     * @return signed area
     */
    private static double orient2dHelper(double ax, double ay, double bx, double by,
            double px, double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }
}
