package ixdar.geometry.mesh.quadlayout.seamless;

import ixdar.geometry.mesh.quadlayout.solver.system.DofSystem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.exact.ExactArithmetic;
import ixdar.geometry.mesh.quadlayout.solver.NormalMatrix;

/**
 * DOF state plus cached SPD assembly plan for one seamless build.
 *
 * <p>
 * The SPD non-zero pattern is invariant within one build, so
 * {@link #assemble(double[])} records a playback log on its first call and
 * refills values in place afterwards. One instance per
 * {@link SeamlessParameterization#build()}; only pin state is mutable.
 */
public final class SeamlessDofSystem {

    /** Corners per triangular face. */
    private static final int CORNERS_PER_FACE = 3;
    /** Components per chart vertex (0 = u, 1 = v). */
    private static final int COMPONENTS_PER_CHART_VERTEX = 2;
    /** Sentinel for "this edge is not an alignment edge". */
    private static final int NOT_ALIGNMENT = -1;
    /**
     * {@link #alignmentEdgeIsoAxis} value when u_T is along the edge and v is the
     * iso-coordinate to pin.
     */
    private static final int ALIGN_AXIS_V = 1;
    /**
     * {@link #alignmentEdgeIsoAxis} value when v_T is along the edge and u is the
     * iso-coordinate to pin.
     */
    private static final int ALIGN_AXIS_U = 0;

    /** Pre-leftover-elimination DOF count. */
    public final int rawDofCount;
    /** Final-DOF count after leftover-row elimination. */
    public final int dofCount;
    /**
     * Per-active-edge: raw-DOF index of the s translation; -1 if not an interior
     * cut edge.
     */
    public final int[] cutEdgeSDof;
    /** Sibling of {@link #cutEdgeSDof} for the t translation. */
    public final int[] cutEdgeTDof;
    /**
     * Per raw DOF: dense final-DOF index, or -1 if pivoted out by leftover
     * elimination.
     */
    public final int[] rawDofToFinal;
    /**
     * Per pivot raw DOF: list of non-pivot raw DOFs it expands into; non-pivots are
     * null.
     */
    public final int[][] leftoverPivotDofs;
    /** Coefficients matching {@link #leftoverPivotDofs}. */
    public final double[][] leftoverPivotCoefs;
    /**
     * Per chart vertex, per component: final-DOF indices the chart vertex's value
     * expands to.
     */
    public final int[][][] chartVertexFinalDofs;
    /** Coefficients matching {@link #chartVertexFinalDofs}. */
    public final double[][][] chartVertexFinalCoefs;
    /**
     * True if final-DOF i must round to an integer. All entries are false: the
     * downstream stage requires a non-quantized seamless parametrization, whose
     * transitions are 90°k rotations plus real translations, with every integer
     * assigned later by the T-mesh quantization ILP.
     *
     * <p>
     * See also: LCK21a Section 3
     */
    public final boolean[] dofIsInteger;

    /**
     * Alignment iso-axis per active edge: {@link #ALIGN_AXIS_V} if the cross-field
     * u-axis runs along this edge and v is the iso-coordinate to pin,
     * {@link #ALIGN_AXIS_U} for the reverse, {@link #NOT_ALIGNMENT} if the edge is
     * not in {@link CrossField#alignmentEdgeIds} or is an interior cut edge.
     *
     * <p>
     * See also: BZK09 Section 5.2
     */
    public final int[] alignmentEdgeIsoAxis;

    /**
     * The canonical solve state; its solution array is the one the whole
     * seamless build reads and writes.
     */
    public final DofSystem system;

    /** True after greedy rounding has snapped final-DOF i. */
    public final boolean[] dofPinned;
    /**
     * Integer value pinned at final-DOF i; only valid when {@link #dofPinned}[i] is
     * true.
     */
    public final double[] dofPinnedValue;

    private SeamlessParameterization seamless;
    private CutGraph cutGraph;
    private HalfEdgeMesh mesh;
    private CrossField crossField;
    private AssemblyPlanBuilder assemblyPlan;

    /**
     * Build the DOF system snapshot for one seamless build.
     *
     * @param seamless seamless parametrization whose mesh / cross-field / per-face
     *                 geometry to snapshot
     * @param cutGraph cut graph whose seam edges drive the DOF layout
     */
    public SeamlessDofSystem(SeamlessParameterization seamless, CutGraph cutGraph) {
        this.seamless = seamless;
        this.cutGraph = cutGraph;
        this.cutEdgeSDof = new int[seamless.edgeCount];
        this.cutEdgeTDof = new int[seamless.edgeCount];
        this.mesh = seamless.mesh;
        this.crossField = seamless.crossField;
        Arrays.fill(cutEdgeSDof, -1);
        Arrays.fill(cutEdgeTDof, -1);
        int sBase = 2 * cutGraph.primaryChartCount;
        int tBase = sBase + cutGraph.interiorCutEdgeCount;
        for (int activeEdge = 0; activeEdge < seamless.edgeCount; activeEdge++) {
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
        this.system = new DofSystem(dofCount);
        this.system.assembler = x -> {
            NormalMatrix assembled = assembleWeighted(this.seamless.faceWeight);
            applyIntegerPinPenalty(assembled);
            return assembled;
        };
    }

    /**
     * Assemble the SPD matrix for the given per-face IRLS weights. First call
     * builds the playback plan (and caches it); subsequent calls replay the plan
     * into fresh value arrays.
     *
     * @param faceWeight per-face IRLS weight, length {@link #faceCount}
     * @return the assembled SPD matrix (without integer-pin diagonal penalty —
     *         apply with {@link #applyIntegerPinPenalty})
     */
    public NormalMatrix assembleWeighted(double[] faceWeight) {
        if (assemblyPlan == null) {
            this.assemblyPlan = new AssemblyPlanBuilder(seamless.faceCount);
            this.assemblyPlan.build(seamless, chartVertexFinalDofs, chartVertexFinalCoefs, cutGraph, dofCount);
        }
        double[] diag = assemblyPlan.planStaticDiagonal.clone();
        double[] upper = assemblyPlan.planStaticUpperValues.clone();
        double[] rhs = new double[dofCount];
        for (int f = 0; f < seamless.faceCount; f++) {
            double w = faceWeight[f];
            if (w == 0.0) {
                continue;
            }
            int uEnd = assemblyPlan.perFaceUpperStart[f + 1];
            for (int i = assemblyPlan.perFaceUpperStart[f]; i < uEnd; i++) {
                upper[assemblyPlan.perFaceUpperSlot[i]] += w * assemblyPlan.perFaceUpperCoef[i];
            }
            int dEnd = assemblyPlan.perFaceDiagonalStart[f + 1];
            for (int i = assemblyPlan.perFaceDiagonalStart[f]; i < dEnd; i++) {
                diag[assemblyPlan.perFaceDiagonalDof[i]] += w * assemblyPlan.perFaceDiagonalCoef[i];
            }
            int rEnd = assemblyPlan.perFaceRhsStart[f + 1];
            for (int i = assemblyPlan.perFaceRhsStart[f]; i < rEnd; i++) {
                rhs[assemblyPlan.perFaceRhsDof[i]] += w * assemblyPlan.perFaceRhsCoef[i];
            }
        }
        return new NormalMatrix(diag, assemblyPlan.planUpperKeys, upper, rhs);
    }

    /**
     * Bump the diagonal and RHS of {@code matrix} for every pinned DOF — the
     * soft-penalty form of integer constraints. Mutates the matrix in place. No-op
     * if no DOFs are pinned yet.
     *
     * @param matrix matrix to mutate; expected to be an output of
     *               {@link #assemble(double[])}
     */
    public void applyIntegerPinPenalty(NormalMatrix matrix) {
        for (int dofIdx = 0; dofIdx < dofCount; dofIdx++) {
            if (!dofPinned[dofIdx]) {
                continue;
            }
            matrix.diagonal[dofIdx] += seamless.integerPinWeight;
            matrix.rightHandSide[dofIdx] += seamless.integerPinWeight * dofPinnedValue[dofIdx];
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
     * Evaluate one component of a chart vertex from a solution vector by summing
     * its final-DOF expansion.
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
     * Evaluate the value of a raw DOF in {@code solution}: a non-pivot raw DOF
     * reads its dense final entry; a pivot raw DOF evaluates recursively through
     * {@link #leftoverPivotDofs} / {@link #leftoverPivotCoefs}.
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
     * Decide, for every edge in {@link CrossField#alignmentEdgeIds}, whether the
     * cross field's u-axis or v-axis runs along it; the orthogonal coordinate is
     * the iso to pin. Interior cut edges are marked {@link #NOT_ALIGNMENT}, since
     * {@code v_p = v_q} cannot hold on both sides of a rotated cut.
     *
     * <p>
     * See also: BZK09 Section 5.2
     *
     * @return per active edge: {@link #ALIGN_AXIS_U}, {@link #ALIGN_AXIS_V}, or
     *         {@link #NOT_ALIGNMENT}
     */
    private int[] computeAlignmentEdgeIsoAxes() {
        int[] axis = new int[seamless.edgeCount];
        Arrays.fill(axis, NOT_ALIGNMENT);
        Vector3f startPos = new Vector3f();
        Vector3f endPos = new Vector3f();
        Vector3f edgeDir = new Vector3f();
        for (int activeEdge = 0; activeEdge < seamless.edgeCount; activeEdge++) {
            int edgeId = seamless.mesh.edgeIdAt(activeEdge);
            if (!seamless.crossField.alignmentEdgeIds.contains(edgeId)) {
                continue;
            }
            int faceA = seamless.edgeFaceA[activeEdge];
            int faceB = seamless.edgeFaceB[activeEdge];
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
            axis[activeEdge] = Math.abs(edgeX * uTx + edgeY * uTy) >= Math.abs(edgeX * -uTy + edgeY * uTx)
                    ? ALIGN_AXIS_V
                    : ALIGN_AXIS_U;
        }
        return axis;
    }

    /**
     * Reduce the leftover-constraint system {@code L · x = 0} to a set of
     * substitution rules, one per pivoted raw DOF, via
     * {@link LeftoverConstraintEliminator}'s sparse Gauss-Jordan elimination.
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

        LeftoverConstraintEliminator eliminator = new LeftoverConstraintEliminator(rows, rawDofCount);
        int nextFinal = 0;
        for (int rawDof = 0; rawDof < rawDofCount; rawDof++) {
            leftoverPivotDofs[rawDof] = eliminator.pivotDofs[rawDof];
            leftoverPivotCoefs[rawDof] = eliminator.pivotCoefs[rawDof];
            if (leftoverPivotDofs[rawDof] != null) {
                rawDofToFinal[rawDof] = -1;
            } else {
                rawDofToFinal[rawDof] = nextFinal++;
            }
        }
        return nextFinal;
    }

    /**
     * For every alignment edge with a decided iso-axis, add one equality row
     * {@code u_p − u_q = 0} (or v) to {@code rows}. The endpoint chart vertices
     * come from face A's corners at the canonical half-edge's start and end
     * vertices.
     *
     * <p>
     * See also: BZK09 Section 5.2
     *
     * @param rows the accumulator already holding the cut-rotation rows; this
     *             method appends to it in place
     */
    private void addAlignmentEqualityRows(ArrayList<HashMap<Integer, Double>> rows) {
        for (int activeEdge = 0; activeEdge < seamless.edgeCount; activeEdge++) {
            int axis = alignmentEdgeIsoAxis[activeEdge];
            if (axis == NOT_ALIGNMENT) {
                continue;
            }
            int faceA = seamless.edgeFaceA[activeEdge];
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
     * Bake the per-cut-edge substitution and leftover-row elimination into a
     * per-chart-vertex final-DOF expansion. After this the solver works in the
     * final DOF space directly: each chart vertex's u or v is
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
     * Pre-leftover-elimination raw-DOF expansion of a chart vertex's component.
     * Primary chart vertices expand to a single raw DOF; secondary chart vertices
     * expand to {@code (partnerU, partnerV,
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
     * Fold one raw-DOF contribution into a final-DOF accumulator: a non-pivot raw
     * DOF maps directly to its dense final index; a pivot raw DOF expands
     * recursively through {@link #leftoverPivotDofs} / {@link #leftoverPivotCoefs}.
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

}
