package ixdar.geometry.mesh.quadlayout.seamless;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.NormalMatrix;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.exact.ExactArithmetic;
import ixdar.geometry.mesh.quadlayout.solver.OrderingMethod;
import ixdar.geometry.mesh.quadlayout.solver.SolverPermutation;

/**
 * DOF state plus cached SPD assembly plan for one BZK09 §5 seamless build.
 * Owns the per-cut-edge translation DOF assignment, the leftover-row
 * Gauss-Jordan elimination state, the per-chart-vertex final-DOF
 * expansions, the integer-pin tracking, the cached AMD column
 * permutation, and the per-face assembly playback log that lets the 50
 * §5.4 stiffening iterations skip rebuilding the SPD upper triangle from
 * scratch.
 *
 * <p>The non-zero pattern of the SPD matrix is invariant across all
 * solver calls within one build — only per-face IRLS weights and
 * integer-pin diagonal bumps change values. {@link #assemble(double[])}
 * builds the playback log on its first call and replays it on every
 * subsequent call: zero-and-refill values into the cached per-slot
 * arrays, no hashing.
 *
 * <p>One instance per {@link SeamlessParameterization#build()} invocation.
 * Pin state is mutated by the greedy rounder; everything else is
 * post-construction immutable.
 */
public final class SeamlessDofSystem {

    /** Corners per triangular face. */
    private static final int CORNERS_PER_FACE = 3;
    /** Components per chart vertex (0 = u, 1 = v). */
    private static final int COMPONENTS_PER_CHART_VERTEX = 2;
    /** Bit shift used to pack (row, col) into a long key. */
    private static final int KEY_ROW_SHIFT = 32;
    /** Low-32 mask used to extract col from a packed (row, col) key. */
    private static final long KEY_COL_MASK = 0xFFFFFFFFL;
    /** Halve factor for the upper-triangle double-count correction. */
    private static final double UPPER_HALVE_FACTOR = 0.5;
    /** Initial-capacity hint for the upper-triangle key set during plan build. */
    private static final int AVG_NONZEROS_PER_ROW = 8;
    /** Tolerance for the leftover-row Gauss-Jordan pivot magnitude. */
    private static final double LEFTOVER_REDUCE_TOLERANCE = 1.0e-10;
    /** Sentinel for "this edge is not an alignment edge". */
    private static final int NOT_ALIGNMENT = -1;
    /** {@link #alignmentEdgeIsoAxis} value when u_T is along the edge and v is the iso-coordinate to pin. */
    private static final int ALIGN_AXIS_V = 1;
    /** {@link #alignmentEdgeIsoAxis} value when v_T is along the edge and u is the iso-coordinate to pin. */
    private static final int ALIGN_AXIS_U = 0;

    // ===== Input references (snapshotted at construction) =====

    /** Mesh used to look up face/edge/vertex topology during the gauge BFS. */
    public final HalfEdgeMesh mesh;
    /** Cross field providing edge/face activation maps and singularity list. */
    public final CrossField crossField;
    /** Cut graph providing chart vertex assignments and leftover constraints. */
    public final CutGraph cutGraph;
    /** Active-face count. */
    public final int faceCount;
    /** Active-edge count. */
    public final int edgeCount;
    /** Active-edge → active-face A; -1 for boundary. */
    public final int[] edgeFaceA;
    /** Active-edge → active-face B; -1 for boundary. */
    public final int[] edgeFaceB;
    /** Per-face area (static for the build). */
    public final double[] faceArea;
    /** Per-face shape gradient x-coefficients, 3 per face. */
    public final double[] faceShapeB;
    /** Per-face shape gradient y-coefficients, 3 per face. */
    public final double[] faceShapeC;
    /** Per-face u-target x-component in local frame. */
    public final double[] faceUtxLocal;
    /** Per-face u-target y-component in local frame. */
    public final double[] faceUtyLocal;
    /** Per-face v-target x-component in local frame. */
    public final double[] faceVtxLocal;
    /** Per-face v-target y-component in local frame. */
    public final double[] faceVtyLocal;
    /** BZK09 target edge length. */
    public final float h;
    /** Gauge-pin penalty weight. */
    public final float gaugePinWeight;

    // ===== DOF state (set once during construction) =====

    /** Pre-leftover-elimination DOF count. */
    public final int rawDofCount;
    /** Final-DOF count after leftover-row elimination. */
    public final int dofCount;
    /** Per-active-edge: raw-DOF index of the s translation; -1 if not an interior cut edge. */
    public final int[] cutEdgeSDof;
    /** Sibling of {@link #cutEdgeSDof} for the t translation. */
    public final int[] cutEdgeTDof;
    /** Per raw DOF: dense final-DOF index, or -1 if pivoted out by leftover elimination. */
    public final int[] rawDofToFinal;
    /** Per pivot raw DOF: list of non-pivot raw DOFs it expands into; non-pivots are null. */
    public final int[][] leftoverPivotDofs;
    /** Coefficients matching {@link #leftoverPivotDofs}. */
    public final double[][] leftoverPivotCoefs;
    /** Per chart vertex, per component: final-DOF indices the chart vertex's value expands to. */
    public final int[][][] chartVertexFinalDofs;
    /** Coefficients matching {@link #chartVertexFinalDofs}. */
    public final double[][][] chartVertexFinalCoefs;
    /** True if final-DOF i must round to an integer in IGM mode. */
    public final boolean[] dofIsInteger;

    /**
     * BZK09 §5.2 alignment iso-axis per active edge: {@link #ALIGN_AXIS_V}
     * if the cross-field u-axis runs along this edge and v is the
     * iso-coordinate to pin; {@link #ALIGN_AXIS_U} for the reverse;
     * {@link #NOT_ALIGNMENT} if this edge is not in
     * {@link CrossField#alignmentEdgeIds}, or is an interior cut edge
     * (a feature edge that ended up on the cut cannot satisfy
     * {@code v_p = v_q} on both sides simultaneously; see audit doc).
     */
    public final int[] alignmentEdgeIsoAxis;

    // ===== Pin state (mutated by the greedy rounder) =====

    /** True after greedy rounding has snapped final-DOF i. */
    public final boolean[] dofPinned;
    /** Integer value pinned at final-DOF i; only valid when {@link #dofPinned}[i] is true. */
    public final double[] dofPinnedValue;
    /** Soft-pin diagonal weight applied to each pinned DOF in {@link #applyIntegerPinPenalty}. */
    public final double integerPinWeight;

    // ===== Assembly plan + AMD perm (lazily populated by the first assemble call) =====

    /** Sorted (row, col) packed-long keys — slot id = array index. */
    private long[] planUpperKeys;
    /** CSR-style: per-face upper-entry range is {@code [planPerFaceUpperStart[f], planPerFaceUpperStart[f+1])}. */
    private int[] planPerFaceUpperStart;
    /** Flat list of upper-triangle slot indices, one per (face, contribution). */
    private int[] planPerFaceUpperSlot;
    /** Per-entry coefficient (× 0.5 baked in for halve-correctness). */
    private double[] planPerFaceUpperCoef;
    /** CSR-style per-face diagonal-entry start. */
    private int[] planPerFaceDiagonalStart;
    /** Flat list of diagonal DOF indices. */
    private int[] planPerFaceDiagonalDof;
    /** Per-entry diagonal coefficient (no halve). */
    private double[] planPerFaceDiagonalCoef;
    /** CSR-style per-face RHS-entry start. */
    private int[] planPerFaceRhsStart;
    /** Flat list of RHS DOF indices. */
    private int[] planPerFaceRhsDof;
    /** Per-entry RHS coefficient (sum of u + v target contributions for this face/dof). */
    private double[] planPerFaceRhsCoef;
    /** Gauge-pin static diagonal contributions, length {@link #dofCount}. */
    private double[] planStaticDiagonal;
    /** Gauge-pin static upper contributions, indexed by slot in {@link #planUpperKeys}. */
    private double[] planStaticUpperValues;
    /** Cached AMD column permutation for this DOF system's SPD matrix. */
    private int[] cachedAmdPerm;
    /** Cached gauge-pin chart vertex picks (BFS over cut graph). */
    private int[] cachedGaugePinChartVertices;

    /**
     * Build the DOF system snapshot for one seamless build.
     *
     * @param owner the {@link SeamlessParameterization} whose mesh / cross-field
     *              / cut-graph / per-face geometry to snapshot; the constructor
     *              does not retain the reference
     */
    public SeamlessDofSystem(SeamlessParameterization owner) {
        this.mesh = owner.mesh;
        this.crossField = owner.crossField;
        this.cutGraph = owner.cutGraph;
        this.faceCount = owner.faceCount;
        this.edgeCount = owner.edgeCount;
        this.edgeFaceA = owner.edgeFaceA;
        this.edgeFaceB = owner.edgeFaceB;
        this.faceArea = owner.faceArea;
        this.faceShapeB = owner.faceShapeB;
        this.faceShapeC = owner.faceShapeC;
        this.faceUtxLocal = owner.faceUtxLocal;
        this.faceUtyLocal = owner.faceUtyLocal;
        this.faceVtxLocal = owner.faceVtxLocal;
        this.faceVtyLocal = owner.faceVtyLocal;
        this.h = owner.h;
        this.gaugePinWeight = owner.gaugePinWeight;
        this.integerPinWeight = owner.integerPinWeight;

        this.cutEdgeSDof = new int[edgeCount];
        this.cutEdgeTDof = new int[edgeCount];
        Arrays.fill(cutEdgeSDof, -1);
        Arrays.fill(cutEdgeTDof, -1);
        int sBase = 2 * cutGraph.primaryChartCount;
        int tBase = sBase + cutGraph.interiorCutEdgeCount;
        for (int activeEdge = 0; activeEdge < edgeCount; activeEdge++) {
            int dense = cutGraph.cutEdgeDenseIdx[activeEdge];
            if (dense < 0) {
                continue;
            }
            cutEdgeSDof[activeEdge] = sBase + dense;
            cutEdgeTDof[activeEdge] = tBase + dense;
        }
        this.rawDofCount = 2 * cutGraph.primaryChartCount + 2 * cutGraph.interiorCutEdgeCount;

        this.alignmentEdgeIsoAxis = computeAlignmentEdgeIsoAxes();

        this.leftoverPivotDofs = new int[rawDofCount][];
        this.leftoverPivotCoefs = new double[rawDofCount][];
        this.rawDofToFinal = new int[rawDofCount];
        this.dofCount = reduceLeftoverConstraints();

        this.chartVertexFinalDofs = new int[cutGraph.chartVertexCount][COMPONENTS_PER_CHART_VERTEX][];
        this.chartVertexFinalCoefs = new double[cutGraph.chartVertexCount][COMPONENTS_PER_CHART_VERTEX][];
        buildChartVertexFinalExpansions();

        this.dofIsInteger = new boolean[dofCount];
        this.dofPinned = new boolean[dofCount];
        this.dofPinnedValue = new double[dofCount];
        markIntegerDofs();
    }

    /**
     * Assemble the SPD matrix for the given per-face IRLS weights. First
     * call builds the playback plan (and caches it); subsequent calls
     * replay the plan into fresh value arrays.
     *
     * @param faceWeight per-face IRLS weight, length {@link #faceCount}
     * @return the assembled SPD matrix (without integer-pin diagonal
     *         penalty — apply with {@link #applyIntegerPinPenalty})
     */
    public NormalMatrix assemble(double[] faceWeight) {
        if (planUpperKeys == null) {
            buildAssemblyPlan();
        }
        double[] diag = planStaticDiagonal.clone();
        double[] upper = planStaticUpperValues.clone();
        double[] rhs = new double[dofCount];
        for (int f = 0; f < faceCount; f++) {
            double w = faceWeight[f];
            if (w == 0.0) {
                continue;
            }
            int uEnd = planPerFaceUpperStart[f + 1];
            for (int i = planPerFaceUpperStart[f]; i < uEnd; i++) {
                upper[planPerFaceUpperSlot[i]] += w * planPerFaceUpperCoef[i];
            }
            int dEnd = planPerFaceDiagonalStart[f + 1];
            for (int i = planPerFaceDiagonalStart[f]; i < dEnd; i++) {
                diag[planPerFaceDiagonalDof[i]] += w * planPerFaceDiagonalCoef[i];
            }
            int rEnd = planPerFaceRhsStart[f + 1];
            for (int i = planPerFaceRhsStart[f]; i < rEnd; i++) {
                rhs[planPerFaceRhsDof[i]] += w * planPerFaceRhsCoef[i];
            }
        }
        return new NormalMatrix(diag, planUpperKeys, upper, rhs);
    }

    /**
     * Bump the diagonal and RHS of {@code matrix} for every pinned DOF —
     * the soft-penalty form of integer constraints. Mutates the matrix in
     * place. No-op if no DOFs are pinned yet.
     *
     * @param matrix matrix to mutate; expected to be an output of
     *               {@link #assemble(double[])}
     */
    public void applyIntegerPinPenalty(NormalMatrix matrix) {
        for (int dofIdx = 0; dofIdx < dofCount; dofIdx++) {
            if (!dofPinned[dofIdx]) {
                continue;
            }
            matrix.diag[dofIdx] += integerPinWeight;
            matrix.rhs[dofIdx] += integerPinWeight * dofPinnedValue[dofIdx];
        }
    }

    /**
     * Record one integer pin from the greedy rounder.
     *
     * @param dofIdx final-DOF index to pin
     * @param value  integer value being snapped to
     */
    public void pinDof(int dofIdx, double value) {
        dofPinned[dofIdx] = true;
        dofPinnedValue[dofIdx] = value;
    }

    /**
     * Lazily compute and cache the AMD column permutation for the SPD
     * matrix. The permutation depends only on the matrix non-zero pattern,
     * which is invariant across all solver calls in one build, so this
     * runs at most once per {@link SeamlessDofSystem} instance.
     *
     * @param matrix any assembled instance of the SPD system — only its
     *               non-zero pattern is read
     * @return cached perm with {@code perm[newIdx] = oldIdx}
     */
    public int[] amdPermutation(NormalMatrix matrix) {
        if (cachedAmdPerm == null) {
            boolean[] noneFixed = new boolean[dofCount];
            int[] identityCompactOf = new int[dofCount];
            for (int i = 0; i < dofCount; i++) {
                identityCompactOf[i] = i;
            }
            cachedAmdPerm = SolverPermutation.computePermutation(
                    matrix, noneFixed, identityCompactOf, dofCount, OrderingMethod.AMD);
        }
        return cachedAmdPerm;
    }

    /**
     * Evaluate one component of a chart vertex from a solution vector by
     * summing its final-DOF expansion.
     *
     * @param chartVertex chart vertex index
     * @param component   0 for u, 1 for v
     * @param solution    current solver solution
     * @return the chart vertex's component value
     */
    public double evaluateChartComponent(int chartVertex, int component, double[] solution) {
        int[] dofs = chartVertexFinalDofs[chartVertex][component];
        double[] coefs = chartVertexFinalCoefs[chartVertex][component];
        double value = 0.0;
        for (int i = 0; i < dofs.length; i++) {
            value += coefs[i] * solution[dofs[i]];
        }
        return value;
    }

    /**
     * Evaluate the value of a raw DOF in {@code solution}: a non-pivot raw
     * DOF reads its dense final entry; a pivot raw DOF evaluates
     * recursively through {@link #leftoverPivotDofs} / {@link #leftoverPivotCoefs}.
     *
     * @param rawDof   a raw-DOF index in {@code [0, rawDofCount)}
     * @param solution current solver solution
     * @return the value of {@code rawDof} implied by {@code solution}
     */
    public double evaluateRawDof(int rawDof, double[] solution) {
        if (leftoverPivotDofs[rawDof] != null) {
            int[] subsDofs = leftoverPivotDofs[rawDof];
            double[] subsCoefs = leftoverPivotCoefs[rawDof];
            double value = 0.0;
            for (int i = 0; i < subsDofs.length; i++) {
                value += subsCoefs[i] * evaluateRawDof(subsDofs[i], solution);
            }
            return value;
        }
        return solution[rawDofToFinal[rawDof]];
    }

    /**
     * Decide, for every active edge in
     * {@link CrossField#alignmentEdgeIds}, whether the cross field's
     * u-axis or v-axis runs along it. Picks the axis whose projection
     * onto the edge direction (in face A's local frame, post
     * branch rotation) has the larger absolute value; the orthogonal
     * coordinate is the iso to pin per BZK09 §5.2.
     *
     * <p>Interior cut edges that happen to be alignment edges are
     * marked {@link #NOT_ALIGNMENT}: the {@code v_p = v_q} constraint
     * cannot hold simultaneously on both sides of a rotated cut.
     * Boundary alignment edges keep their axis because they have only
     * one face and no seam transition.
     *
     * @return per active edge: {@link #ALIGN_AXIS_U},
     *         {@link #ALIGN_AXIS_V}, or {@link #NOT_ALIGNMENT}
     */
    private int[] computeAlignmentEdgeIsoAxes() {
        int[] axis = new int[edgeCount];
        Arrays.fill(axis, NOT_ALIGNMENT);
        Vector3f startPos = new Vector3f();
        Vector3f endPos = new Vector3f();
        Vector3f edgeDir = new Vector3f();
        for (int activeEdge = 0; activeEdge < edgeCount; activeEdge++) {
            int edgeId = mesh.edgeIdAt(activeEdge);
            if (!crossField.alignmentEdgeIds.contains(edgeId)) {
                continue;
            }
            int faceA = edgeFaceA[activeEdge];
            int faceB = edgeFaceB[activeEdge];
            if (faceA < 0) {
                continue;
            }
            if (faceB >= 0 && cutGraph.isCutEdge[activeEdge]) {
                // Feature edge ended up on the cut despite the bias.
                continue;
            }
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            int startVertex = mesh.halfEdgeVertex(halfEdge);
            int endVertex = mesh.halfEdgeEndVertex(halfEdge);
            mesh.vertexPosition(startVertex, startPos);
            mesh.vertexPosition(endVertex, endPos);
            edgeDir.set(endPos).sub(startPos);
            double edgeX = edgeDir.dot(crossField.faceX[faceA]);
            double edgeY = edgeDir.dot(crossField.faceY[faceA]);
            double angle = crossField.theta[faceA]
                    + cutGraph.faceBranch[faceA] * (Math.PI / 2.0);
            double uTx = Math.cos(angle);
            double uTy = Math.sin(angle);
            // v_T = R_{π/2} u_T = (-uTy, uTx)
            double dotU = edgeX * uTx + edgeY * uTy;
            double dotV = edgeX * (-uTy) + edgeY * uTx;
            axis[activeEdge] = Math.abs(dotU) >= Math.abs(dotV) ? ALIGN_AXIS_V : ALIGN_AXIS_U;
        }
        return axis;
    }

    /**
     * Reduce the leftover-constraint system {@code L · x = 0} to a set of
     * substitution rules, one per pivoted raw DOF, by sparse Gauss-Jordan
     * elimination with partial pivoting on the largest-magnitude entry.
     * Populates {@link #leftoverPivotDofs}, {@link #leftoverPivotCoefs},
     * {@link #rawDofToFinal}.
     *
     * @return number of final DOFs after pivot elimination
     */
    private int reduceLeftoverConstraints() {
        ArrayList<HashMap<Integer, Double>> rows = new ArrayList<>();
        for (int[] record : cutGraph.leftoverConstraints) {
            int activeEdge = record[0];
            int chartA = record[1];
            int chartB = record[2];
            int rotation = cutGraph.cutRotation[activeEdge];
            int cos = ExactArithmetic.integerCosine(rotation);
            int sin = ExactArithmetic.integerSine(rotation);
            int sDof = cutEdgeSDof[activeEdge];
            int tDof = cutEdgeTDof[activeEdge];
            HashMap<Integer, Double> rowU = new HashMap<>();
            addRawExpansionTo(rowU, chartB, 0, 1.0);
            addRawExpansionTo(rowU, chartA, 0, -cos);
            addRawExpansionTo(rowU, chartA, 1, sin);
            rowU.merge(sDof, -1.0, Double::sum);
            rows.add(rowU);
            HashMap<Integer, Double> rowV = new HashMap<>();
            addRawExpansionTo(rowV, chartB, 1, 1.0);
            addRawExpansionTo(rowV, chartA, 0, -sin);
            addRawExpansionTo(rowV, chartA, 1, -cos);
            rowV.merge(tDof, -1.0, Double::sum);
            rows.add(rowV);
        }
        addAlignmentEqualityRows(rows);

        int totalRows = rows.size();
        boolean[] rowPivoted = new boolean[totalRows];
        for (int processed = 0; processed < totalRows; processed++) {
            int bestRow = -1;
            int bestPivot = -1;
            double bestMagnitude = 0.0;
            for (int rowIdx = 0; rowIdx < totalRows; rowIdx++) {
                if (rowPivoted[rowIdx]) {
                    continue;
                }
                for (Map.Entry<Integer, Double> entry : rows.get(rowIdx).entrySet()) {
                    double magnitude = Math.abs(entry.getValue());
                    if (magnitude > bestMagnitude) {
                        bestMagnitude = magnitude;
                        bestRow = rowIdx;
                        bestPivot = entry.getKey();
                    }
                }
            }
            if (bestRow < 0 || bestMagnitude < LEFTOVER_REDUCE_TOLERANCE) {
                break;
            }
            HashMap<Integer, Double> pivotRow = rows.get(bestRow);
            double pivotCoef = pivotRow.remove(bestPivot);
            int[] subsDofs = new int[pivotRow.size()];
            double[] subsCoefs = new double[pivotRow.size()];
            int idx = 0;
            for (Map.Entry<Integer, Double> entry : pivotRow.entrySet()) {
                subsDofs[idx] = entry.getKey();
                subsCoefs[idx] = -entry.getValue() / pivotCoef;
                idx++;
            }
            leftoverPivotDofs[bestPivot] = subsDofs;
            leftoverPivotCoefs[bestPivot] = subsCoefs;
            rowPivoted[bestRow] = true;
            for (int rowIdx = 0; rowIdx < totalRows; rowIdx++) {
                if (rowIdx == bestRow) {
                    continue;
                }
                HashMap<Integer, Double> otherRow = rows.get(rowIdx);
                Double otherCoef = otherRow.remove(bestPivot);
                if (otherCoef == null) {
                    continue;
                }
                for (int i = 0; i < subsDofs.length; i++) {
                    otherRow.merge(subsDofs[i], otherCoef * subsCoefs[i], Double::sum);
                }
                otherRow.entrySet().removeIf(e -> Math.abs(e.getValue()) < LEFTOVER_REDUCE_TOLERANCE);
            }
        }

        int nextFinal = 0;
        for (int rawDof = 0; rawDof < rawDofCount; rawDof++) {
            if (leftoverPivotDofs[rawDof] != null) {
                rawDofToFinal[rawDof] = -1;
            } else {
                rawDofToFinal[rawDof] = nextFinal++;
            }
        }
        return nextFinal;
    }

    /**
     * For every BZK09 §5.2 alignment edge with a decided iso-axis, add
     * one equality row {@code u_p − u_q = 0} (or v) to {@code rows}.
     * The endpoint chart vertices come from face A's corners at the
     * canonical half-edge's start/end vertices; boundary alignment
     * edges only have face A, interior non-cut alignment edges have
     * both A and B unified onto the same pair of chart vertices.
     *
     * @param rows the accumulator the §5 cut-rotation rows already
     *             populated; this method appends to it in place
     */
    private void addAlignmentEqualityRows(ArrayList<HashMap<Integer, Double>> rows) {
        for (int activeEdge = 0; activeEdge < edgeCount; activeEdge++) {
            int axis = alignmentEdgeIsoAxis[activeEdge];
            if (axis == NOT_ALIGNMENT) {
                continue;
            }
            int faceA = edgeFaceA[activeEdge];
            int cornerStartA = -1;
            int cornerEndA = -1;
            int edgeId = mesh.edgeIdAt(activeEdge);
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            int startVertex = mesh.halfEdgeVertex(halfEdge);
            int endVertex = mesh.halfEdgeEndVertex(halfEdge);
            int faceAId = mesh.faceIdAt(faceA);
            for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
                int cornerVertex = mesh.faceVertexAt(faceAId, corner);
                if (cornerVertex == startVertex) {
                    cornerStartA = corner;
                } else if (cornerVertex == endVertex) {
                    cornerEndA = corner;
                }
            }
            int chartStart = cutGraph.cornerToChartVertex[faceA * CORNERS_PER_FACE + cornerStartA];
            int chartEnd = cutGraph.cornerToChartVertex[faceA * CORNERS_PER_FACE + cornerEndA];
            int component = axis == ALIGN_AXIS_V ? 1 : 0;
            HashMap<Integer, Double> row = new HashMap<>();
            addRawExpansionTo(row, chartStart, component, 1.0);
            addRawExpansionTo(row, chartEnd, component, -1.0);
            rows.add(row);
        }
    }

    /**
     * Bake the per-cut-edge substitution and leftover-row elimination into
     * a per-chart-vertex final-DOF expansion. After this the solver works
     * in the final DOF space directly: each chart vertex's u or v is
     * {@code Σ coef · solution[finalDof]}.
     */
    private void buildChartVertexFinalExpansions() {
        for (int chartVertex = 0; chartVertex < cutGraph.chartVertexCount; chartVertex++) {
            for (int component = 0; component < COMPONENTS_PER_CHART_VERTEX; component++) {
                HashMap<Integer, Double> finalAccum = new HashMap<>();
                int[] rawDofs = rawExpansionDofs(chartVertex, component);
                double[] rawCoefs = rawExpansionCoefs(chartVertex, component);
                for (int i = 0; i < rawDofs.length; i++) {
                    accumulateRawDofAsFinal(finalAccum, rawDofs[i], rawCoefs[i]);
                }
                int size = finalAccum.size();
                int[] dofs = new int[size];
                double[] coefs = new double[size];
                int idx = 0;
                for (Map.Entry<Integer, Double> entry : finalAccum.entrySet()) {
                    dofs[idx] = entry.getKey();
                    coefs[idx] = entry.getValue();
                    idx++;
                }
                chartVertexFinalDofs[chartVertex][component] = dofs;
                chartVertexFinalCoefs[chartVertex][component] = coefs;
            }
        }
    }

    /**
     * Mark which final DOFs must round to integers: every per-cut-edge
     * {@code (s, t)} pair, the {@code (u, v)} of every primary chart
     * vertex that touches a singularity mesh vertex, and the iso-axis
     * coordinate of every primary chart vertex on a BZK09 §5.2 alignment
     * edge (handed off to {@link #markAlignmentIsoDofs}).
     * Pivot-eliminated raw DOFs are skipped (their values are determined
     * by non-eliminated free DOFs).
     */
    private void markIntegerDofs() {
        for (int activeEdge = 0; activeEdge < edgeCount; activeEdge++) {
            if (cutEdgeSDof[activeEdge] < 0) {
                continue;
            }
            markRawDofAsInteger(cutEdgeSDof[activeEdge]);
            markRawDofAsInteger(cutEdgeTDof[activeEdge]);
        }
        Set<Integer> singularVertexIds = new HashSet<>();
        for (Singularity s : crossField.singularities) {
            singularVertexIds.add(s.vertexId());
        }
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
                if (!singularVertexIds.contains(mesh.faceVertexAt(faceId, corner))) {
                    continue;
                }
                int chartVertex = cutGraph.cornerToChartVertex[activeFace * CORNERS_PER_FACE + corner];
                if (!cutGraph.chartVertexIsPrimary[chartVertex]) {
                    continue;
                }
                int primaryIdx = cutGraph.primaryChartIndex[chartVertex];
                markRawDofAsInteger(2 * primaryIdx);
                markRawDofAsInteger(2 * primaryIdx + 1);
            }
        }
        markAlignmentIsoDofs();
    }

    /**
     * BZK09 §5.2 integer iso-line pin: for every alignment edge whose
     * axis was decided in {@link #computeAlignmentEdgeIsoAxes}, mark
     * the iso-coordinate of both endpoint chart vertices as integer.
     * The pair was tied together as equal in
     * {@link #addAlignmentEqualityRows}, so leftover-row elimination
     * collapses them onto a shared expansion in
     * {@link #chartVertexFinalDofs}; the integer pin propagates through
     * every final DOF in that expansion.
     */
    private void markAlignmentIsoDofs() {
        for (int activeEdge = 0; activeEdge < edgeCount; activeEdge++) {
            int axis = alignmentEdgeIsoAxis[activeEdge];
            if (axis == NOT_ALIGNMENT) {
                continue;
            }
            int faceA = edgeFaceA[activeEdge];
            int edgeId = mesh.edgeIdAt(activeEdge);
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            int startVertex = mesh.halfEdgeVertex(halfEdge);
            int endVertex = mesh.halfEdgeEndVertex(halfEdge);
            int faceAId = mesh.faceIdAt(faceA);
            int cornerStartA = -1;
            int cornerEndA = -1;
            for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
                int cornerVertex = mesh.faceVertexAt(faceAId, corner);
                if (cornerVertex == startVertex) {
                    cornerStartA = corner;
                } else if (cornerVertex == endVertex) {
                    cornerEndA = corner;
                }
            }
            int chartStart = cutGraph.cornerToChartVertex[faceA * CORNERS_PER_FACE + cornerStartA];
            int chartEnd = cutGraph.cornerToChartVertex[faceA * CORNERS_PER_FACE + cornerEndA];
            int component = axis == ALIGN_AXIS_V ? 1 : 0;
            markChartComponentExpansionInteger(chartStart, component);
            markChartComponentExpansionInteger(chartEnd, component);
        }
    }

    /**
     * Mark every final DOF in a chart vertex's component expansion as
     * integer. For a primary chart vertex this is a single DOF (the
     * direct raw DOF after pivot survival); for a secondary one it
     * walks the seam-rotation substitution {@code chartC.v = ±partner.u
     * + (s, t)} and marks each surviving DOF. Because every coefficient
     * in {@link #chartVertexFinalDofs} is integer (seam rotations have
     * integer cos/sin, alignment rows have ±1 coefficients), an integer
     * value for every term in the expansion forces the chart vertex's
     * component to also be integer.
     *
     * @param chartVertex chart vertex index
     * @param component   0 for u, 1 for v
     */
    private void markChartComponentExpansionInteger(int chartVertex, int component) {
        int[] dofs = chartVertexFinalDofs[chartVertex][component];
        double[] coefs = chartVertexFinalCoefs[chartVertex][component];
        for (int i = 0; i < dofs.length; i++) {
            if (coefs[i] == 0.0) {
                continue;
            }
            dofIsInteger[dofs[i]] = true;
        }
    }

    /**
     * Pre-leftover-elimination raw-DOF expansion of a chart vertex's
     * component. Primary chart vertices expand to a single raw DOF;
     * secondary chart vertices expand to {@code (partnerU, partnerV,
     * s_e or t_e)} with rotation coefficients.
     *
     * @param chartVertex chart vertex index
     * @param component   0 for u, 1 for v
     * @return raw-DOF indices array
     */
    private int[] rawExpansionDofs(int chartVertex, int component) {
        if (cutGraph.chartVertexIsPrimary[chartVertex]) {
            return new int[] { 2 * cutGraph.primaryChartIndex[chartVertex] + component };
        }
        int activeEdge = cutGraph.secondaryEdge[chartVertex];
        int partner = cutGraph.secondaryPartner[chartVertex];
        int partnerBaseDof = 2 * cutGraph.primaryChartIndex[partner];
        int translationDof = (component == 0) ? cutEdgeSDof[activeEdge] : cutEdgeTDof[activeEdge];
        return new int[] { partnerBaseDof, partnerBaseDof + 1, translationDof };
    }

    /**
     * Coefficients matching {@link #rawExpansionDofs}.
     *
     * @param chartVertex chart vertex index
     * @param component   0 for u, 1 for v
     * @return coefficient array
     */
    private double[] rawExpansionCoefs(int chartVertex, int component) {
        if (cutGraph.chartVertexIsPrimary[chartVertex]) {
            return new double[] { 1.0 };
        }
        int activeEdge = cutGraph.secondaryEdge[chartVertex];
        int rotation = cutGraph.cutRotation[activeEdge];
        int cos = ExactArithmetic.integerCosine(rotation);
        int sin = ExactArithmetic.integerSine(rotation);
        if (component == 0) {
            return new double[] { cos, -sin, 1.0 };
        }
        return new double[] { sin, cos, 1.0 };
    }

    /**
     * Scatter {@code outerCoef · (raw-DOF expansion of chartVertex's
     * component)} into {@code accumulator}.
     *
     * @param accumulator raw-DOF → coefficient accumulator
     * @param chartVertex chart vertex whose component to expand
     * @param component   0 for u, 1 for v
     * @param outerCoef   multiplier applied to every term in the expansion
     */
    private void addRawExpansionTo(HashMap<Integer, Double> accumulator,
            int chartVertex, int component, double outerCoef) {
        if (outerCoef == 0.0) {
            return;
        }
        int[] dofs = rawExpansionDofs(chartVertex, component);
        double[] coefs = rawExpansionCoefs(chartVertex, component);
        for (int i = 0; i < dofs.length; i++) {
            accumulator.merge(dofs[i], outerCoef * coefs[i], Double::sum);
        }
    }

    /**
     * Fold one raw-DOF contribution into a final-DOF accumulator: a
     * non-pivot raw DOF maps directly to its dense final index; a pivot
     * raw DOF expands recursively through {@link #leftoverPivotDofs}
     * / {@link #leftoverPivotCoefs}.
     *
     * @param finalAccum final-DOF → coefficient accumulator
     * @param rawDof     the raw-DOF index whose contribution to fold in
     * @param coef       coefficient to multiply into the expansion
     */
    private void accumulateRawDofAsFinal(HashMap<Integer, Double> finalAccum,
            int rawDof, double coef) {
        if (leftoverPivotDofs[rawDof] != null) {
            int[] subsDofs = leftoverPivotDofs[rawDof];
            double[] subsCoefs = leftoverPivotCoefs[rawDof];
            for (int i = 0; i < subsDofs.length; i++) {
                accumulateRawDofAsFinal(finalAccum, subsDofs[i], coef * subsCoefs[i]);
            }
            return;
        }
        int finalDof = rawDofToFinal[rawDof];
        finalAccum.merge(finalDof, coef, Double::sum);
    }

    /**
     * Mark the final DOF corresponding to {@code rawDof} as
     * integer-rounded. No-op if {@code rawDof} was pivot-eliminated.
     *
     * @param rawDof a raw-DOF index
     */
    private void markRawDofAsInteger(int rawDof) {
        if (leftoverPivotDofs[rawDof] != null) {
            return;
        }
        dofIsInteger[rawDofToFinal[rawDof]] = true;
    }

    /**
     * Build the cached assembly playback log. Walks every face once,
     * accumulating per-face contributions into temporary HashMaps; then
     * compacts into CSR-style flat arrays for fast replay. Gauge-pin
     * contributions go into the static arrays since they don't vary with
     * face weight. Halve correction (× 0.5) is baked into upper
     * coefficients (matches the current code's post-pass
     * {@code replaceAll((k, v) -> v * 0.5)} which only halves the upper
     * triangle and leaves the diagonal alone).
     */
    @SuppressWarnings("unchecked")
    private void buildAssemblyPlan() {
        double edgeLengthSquared = (double) h * h;

        HashMap<Long, Double>[] perFaceUpper = new HashMap[faceCount];
        HashMap<Integer, Double>[] perFaceDiagonal = new HashMap[faceCount];
        HashMap<Integer, Double>[] perFaceRhs = new HashMap[faceCount];
        HashSet<Long> uniqueUpperKeys = new HashSet<>(dofCount * AVG_NONZEROS_PER_ROW);

        double[] shapeGradX = new double[CORNERS_PER_FACE];
        double[] shapeGradY = new double[CORNERS_PER_FACE];
        int[] cornerChartVertex = new int[CORNERS_PER_FACE];

        for (int f = 0; f < faceCount; f++) {
            HashMap<Long, Double> upperMap = new HashMap<>();
            HashMap<Integer, Double> diagonalMap = new HashMap<>();
            HashMap<Integer, Double> rhsMap = new HashMap<>();
            perFaceUpper[f] = upperMap;
            perFaceDiagonal[f] = diagonalMap;
            perFaceRhs[f] = rhsMap;

            double area = faceArea[f];
            if (area <= 0) {
                continue;
            }
            int faceCornerBase = f * CORNERS_PER_FACE;
            for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
                shapeGradX[corner] = faceShapeB[faceCornerBase + corner];
                shapeGradY[corner] = faceShapeC[faceCornerBase + corner];
                cornerChartVertex[corner] = cutGraph.cornerToChartVertex[faceCornerBase + corner];
            }
            double targetUx = faceUtxLocal[f], targetUy = faceUtyLocal[f];
            double targetVx = faceVtxLocal[f], targetVy = faceVtyLocal[f];

            for (int cornerI = 0; cornerI < CORNERS_PER_FACE; cornerI++) {
                for (int cornerJ = 0; cornerJ < CORNERS_PER_FACE; cornerJ++) {
                    double stiffnessConstant = area * edgeLengthSquared
                            * (shapeGradX[cornerI] * shapeGradX[cornerJ]
                                    + shapeGradY[cornerI] * shapeGradY[cornerJ]);
                    if (stiffnessConstant == 0.0) {
                        continue;
                    }
                    accumulatePerFaceOuterProduct(upperMap, diagonalMap,
                            chartVertexFinalDofs[cornerChartVertex[cornerI]][0],
                            chartVertexFinalCoefs[cornerChartVertex[cornerI]][0],
                            chartVertexFinalDofs[cornerChartVertex[cornerJ]][0],
                            chartVertexFinalCoefs[cornerChartVertex[cornerJ]][0],
                            stiffnessConstant);
                    accumulatePerFaceOuterProduct(upperMap, diagonalMap,
                            chartVertexFinalDofs[cornerChartVertex[cornerI]][1],
                            chartVertexFinalCoefs[cornerChartVertex[cornerI]][1],
                            chartVertexFinalDofs[cornerChartVertex[cornerJ]][1],
                            chartVertexFinalCoefs[cornerChartVertex[cornerJ]][1],
                            stiffnessConstant);
                }
            }

            for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
                double uRhsConstant = area * h
                        * (shapeGradX[corner] * targetUx + shapeGradY[corner] * targetUy);
                double vRhsConstant = area * h
                        * (shapeGradX[corner] * targetVx + shapeGradY[corner] * targetVy);
                int[] uExpDofs = chartVertexFinalDofs[cornerChartVertex[corner]][0];
                double[] uExpCoefs = chartVertexFinalCoefs[cornerChartVertex[corner]][0];
                for (int i = 0; i < uExpDofs.length; i++) {
                    rhsMap.merge(uExpDofs[i], uRhsConstant * uExpCoefs[i], Double::sum);
                }
                int[] vExpDofs = chartVertexFinalDofs[cornerChartVertex[corner]][1];
                double[] vExpCoefs = chartVertexFinalCoefs[cornerChartVertex[corner]][1];
                for (int i = 0; i < vExpDofs.length; i++) {
                    rhsMap.merge(vExpDofs[i], vRhsConstant * vExpCoefs[i], Double::sum);
                }
            }

            uniqueUpperKeys.addAll(upperMap.keySet());
        }

        cachedGaugePinChartVertices = computeGaugePinChartVertices();
        HashMap<Long, Double> gaugeUpper = new HashMap<>();
        HashMap<Integer, Double> gaugeDiagonal = new HashMap<>();
        for (int chartVertex : cachedGaugePinChartVertices) {
            addGaugePinContribution(gaugeDiagonal, gaugeUpper,
                    chartVertexFinalDofs[chartVertex][0],
                    chartVertexFinalCoefs[chartVertex][0]);
            addGaugePinContribution(gaugeDiagonal, gaugeUpper,
                    chartVertexFinalDofs[chartVertex][1],
                    chartVertexFinalCoefs[chartVertex][1]);
        }
        uniqueUpperKeys.addAll(gaugeUpper.keySet());

        planUpperKeys = uniqueUpperKeys.stream().mapToLong(Long::longValue).sorted().toArray();
        HashMap<Long, Integer> keyToSlot = new HashMap<>(planUpperKeys.length * 2);
        for (int s = 0; s < planUpperKeys.length; s++) {
            keyToSlot.put(planUpperKeys[s], s);
        }

        planStaticDiagonal = new double[dofCount];
        planStaticUpperValues = new double[planUpperKeys.length];
        for (Map.Entry<Integer, Double> e : gaugeDiagonal.entrySet()) {
            planStaticDiagonal[e.getKey()] = e.getValue();
        }
        for (Map.Entry<Long, Double> e : gaugeUpper.entrySet()) {
            planStaticUpperValues[keyToSlot.get(e.getKey())] = e.getValue();
        }

        int totalUpper = 0;
        int totalDiagonal = 0;
        int totalRhs = 0;
        for (int f = 0; f < faceCount; f++) {
            totalUpper += perFaceUpper[f].size();
            totalDiagonal += perFaceDiagonal[f].size();
            totalRhs += perFaceRhs[f].size();
        }
        planPerFaceUpperStart = new int[faceCount + 1];
        planPerFaceUpperSlot = new int[totalUpper];
        planPerFaceUpperCoef = new double[totalUpper];
        planPerFaceDiagonalStart = new int[faceCount + 1];
        planPerFaceDiagonalDof = new int[totalDiagonal];
        planPerFaceDiagonalCoef = new double[totalDiagonal];
        planPerFaceRhsStart = new int[faceCount + 1];
        planPerFaceRhsDof = new int[totalRhs];
        planPerFaceRhsCoef = new double[totalRhs];

        int uCursor = 0, dCursor = 0, rCursor = 0;
        for (int f = 0; f < faceCount; f++) {
            planPerFaceUpperStart[f] = uCursor;
            for (Map.Entry<Long, Double> e : perFaceUpper[f].entrySet()) {
                planPerFaceUpperSlot[uCursor] = keyToSlot.get(e.getKey());
                planPerFaceUpperCoef[uCursor] = e.getValue() * UPPER_HALVE_FACTOR;
                uCursor++;
            }
            planPerFaceDiagonalStart[f] = dCursor;
            for (Map.Entry<Integer, Double> e : perFaceDiagonal[f].entrySet()) {
                planPerFaceDiagonalDof[dCursor] = e.getKey();
                planPerFaceDiagonalCoef[dCursor] = e.getValue();
                dCursor++;
            }
            planPerFaceRhsStart[f] = rCursor;
            for (Map.Entry<Integer, Double> e : perFaceRhs[f].entrySet()) {
                planPerFaceRhsDof[rCursor] = e.getKey();
                planPerFaceRhsCoef[rCursor] = e.getValue();
                rCursor++;
            }
        }
        planPerFaceUpperStart[faceCount] = uCursor;
        planPerFaceDiagonalStart[faceCount] = dCursor;
        planPerFaceRhsStart[faceCount] = rCursor;
    }

    /**
     * Per-face outer-product accumulation into the face's upper/diagonal
     * maps. Matches the original {@code accumulateOuterProduct} →
     * {@code accumulate} call shape: full 3×3 iteration with (min, max)
     * keying for upper and immediate write for diagonal. The (i, j) +
     * (j, i) double-count is intentional — corrected by the × 0.5
     * baked into upper coefficients at compaction time.
     *
     * @param upperMap    per-face upper accumulator
     * @param diagonalMap per-face diagonal accumulator
     * @param dofsA       expansion DOFs for the first chart vertex
     * @param coefsA      coefficients matching {@code dofsA}
     * @param dofsB       expansion DOFs for the second chart vertex
     * @param coefsB      coefficients matching {@code dofsB}
     * @param scale       outer scaling (area × h² × shape-grad product)
     */
    private static void accumulatePerFaceOuterProduct(
            HashMap<Long, Double> upperMap, HashMap<Integer, Double> diagonalMap,
            int[] dofsA, double[] coefsA, int[] dofsB, double[] coefsB, double scale) {
        for (int a = 0; a < dofsA.length; a++) {
            for (int b = 0; b < dofsB.length; b++) {
                double value = scale * coefsA[a] * coefsB[b];
                if (value == 0.0) {
                    continue;
                }
                int rowA = dofsA[a];
                int colB = dofsB[b];
                if (rowA == colB) {
                    diagonalMap.merge(rowA, value, Double::sum);
                } else {
                    int r = Math.min(rowA, colB);
                    int c = Math.max(rowA, colB);
                    long key = ((long) r << KEY_ROW_SHIFT) | (c & KEY_COL_MASK);
                    upperMap.merge(key, value, Double::sum);
                }
            }
        }
    }

    /**
     * Add {@code gaugePinWeight · v · vᵀ} into the gauge-pin accumulators.
     * Matches the original {@code addOuterSparse}: upper-half iteration
     * ({@code j ≥ i}) so each pair counted once, no halve needed.
     *
     * @param diagonalMap gauge-pin diagonal accumulator
     * @param upperMap    gauge-pin upper accumulator
     * @param cols        expansion DOFs of the chart vertex
     * @param vals        coefficients matching {@code cols}
     */
    private void addGaugePinContribution(
            HashMap<Integer, Double> diagonalMap, HashMap<Long, Double> upperMap,
            int[] cols, double[] vals) {
        for (int i = 0; i < cols.length; i++) {
            for (int j = i; j < cols.length; j++) {
                double value = gaugePinWeight * vals[i] * vals[j];
                if (value == 0.0) {
                    continue;
                }
                int rowA = cols[i];
                int colB = cols[j];
                if (rowA == colB) {
                    diagonalMap.merge(rowA, value, Double::sum);
                } else {
                    int r = Math.min(rowA, colB);
                    int c = Math.max(rowA, colB);
                    long key = ((long) r << KEY_ROW_SHIFT) | (c & KEY_COL_MASK);
                    upperMap.merge(key, value, Double::sum);
                }
            }
        }
    }

    /**
     * BFS over the cut graph to pick one chart vertex per connected
     * component (across non-cut edges) for the gauge-pin. The picks set
     * is invariant for the whole build — cached so subsequent rebuilds
     * skip the BFS.
     *
     * @return one chart-vertex per connected component
     */
    private int[] computeGaugePinChartVertices() {
        boolean[] visitedFace = new boolean[faceCount];
        ArrayList<Integer> picks = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int seed = 0; seed < faceCount; seed++) {
            if (visitedFace[seed]) {
                continue;
            }
            visitedFace[seed] = true;
            picks.add(cutGraph.cornerToChartVertex[seed * CORNERS_PER_FACE]);
            queue.add(seed);
            while (!queue.isEmpty()) {
                int af = queue.poll();
                int fId = mesh.faceIdAt(af);
                for (int c = 0; c < CORNERS_PER_FACE; c++) {
                    int eId = mesh.faceEdgeAt(fId, c);
                    int ae = crossField.edgeIdToActive.get(eId);
                    if (cutGraph.isCutEdge[ae]) {
                        continue;
                    }
                    int afA = edgeFaceA[ae];
                    int afB = edgeFaceB[ae];
                    if (afA < 0 || afB < 0) {
                        continue;
                    }
                    int afOther = (afA == af) ? afB : afA;
                    if (visitedFace[afOther]) {
                        continue;
                    }
                    visitedFace[afOther] = true;
                    queue.add(afOther);
                }
            }
        }
        int[] result = new int[picks.size()];
        for (int i = 0; i < picks.size(); i++) {
            result[i] = picks.get(i);
        }
        return result;
    }
}
