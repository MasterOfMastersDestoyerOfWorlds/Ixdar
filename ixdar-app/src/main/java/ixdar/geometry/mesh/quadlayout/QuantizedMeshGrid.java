package ixdar.geometry.mesh.quadlayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.ojalgo.optimisation.Expression;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;

import ixdar.geometry.mesh.quadlayout.motorcycle.MetOtherTraceEntry;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.TMeshPatch;
import ixdar.geometry.mesh.quadlayout.motorcycle.Trace;
import ixdar.geometry.mesh.quadlayout.motorcycle.TraceArc;

/**
 * Lyon 2021 §4–§5 constrained T-mesh quantization: assigns a non-negative
 * integer to every T-mesh arc by solving an integer linear program.
 *
 * <ul>
 * <li><b>Variables</b> — one integer per arc equivalence class. Classes come
 * from Lyon's §5.2 strip reduction: whenever a valid patch has single-arc
 * opposite sides, the two arcs must quantize identically (eq. 2 with one term
 * per side), so they share a variable via union-find.</li>
 * <li><b>Consistency (eq. 2)</b> — for every valid rectangular patch, the
 * quantized sums of opposite sides are equal; constraints whose terms fully
 * cancel after class merging are dropped.</li>
 * <li><b>Validity (eq. 3)</b> — for every singularity trace, the arcs from
 * its origin to its first same-sector intersection {@code n_i*} sum to at
 * least one, which separates all singularities (Lyon Lemma 1).</li>
 * <li><b>Layout (eq. 4)</b> — for every intersection whose meeting angle
 * exceeds α (ratio test {@code l_ji / l_ij > tan α}), the other trace's
 * prefix sums to at least one; meetings on feature chains use α = 0 (Lyon
 * §4.4), so every trace crossing or terminating on a feature/boundary curve is
 * separated from it.</li>
 * <li><b>Objective (eq. 5)</b> — minimize Σ l⊥·q, where an arc's l⊥
 * accumulates half the perpendicular extent of each valid patch it bounds,
 * driving the layout as coarse as the constraints allow.</li>
 * </ul>
 */
public class QuantizedMeshGrid {

    public final MotorcycleGraph motorcycleGraph;
    public final float alphaRadians;

    /** Solved non-negative integer per arc id; filled by {@link #build()}. */
    public int[] quantizedLengthByArc;

    /** Variable-class index per arc id after the §5.2 merge. */
    public int[] variableClassByArc;

    public int variableCount;
    public int consistencyConstraintCount;
    public int validityConstraintCount;
    public int layoutConstraintCount;
    public double objectiveValue;
    public boolean optimal;

    /**
     * Stores inputs for a Lyon §4–§5 quantization solve.
     *
     * @param motorcycleGraph built T-mesh with subdivided arcs and patch sides
     * @param alphaRadians    Lyon's maximum separatrix deviation α in radians
     */
    public QuantizedMeshGrid(MotorcycleGraph motorcycleGraph, float alphaRadians) {
        this.motorcycleGraph = motorcycleGraph;
        this.alphaRadians = alphaRadians;
    }

    /**
     * Assemble and solve the quantization ILP, then verify the solution
     * against the constraint families and log a summary.
     *
     * @return this, with {@link #quantizedLengthByArc} populated
     * @throws IllegalStateException when the solver reports an infeasible or
     *                               failed state (a consistent T-mesh always
     *                               admits the all-ones solution, so this
     *                               indicates corrupt patch structure)
     */
    public QuantizedMeshGrid build() {
        List<TraceArc> arcs = motorcycleGraph.arcs;
        int arcCount = arcs.size();

        int[] parent = new int[arcCount];
        for (int arcId = 0; arcId < arcCount; arcId++) {
            parent[arcId] = arcId;
        }
        for (TMeshPatch patch : motorcycleGraph.patches) {
            if (!patch.validRectangle) {
                continue;
            }
            for (int sideIndex = 0; sideIndex < 2; sideIndex++) {
                List<Integer> side = patch.sides.get(sideIndex);
                List<Integer> oppositeSide = patch.sides.get(sideIndex + 2);
                if (side.size() == 1 && oppositeSide.size() == 1) {
                    union(parent, side.get(0), oppositeSide.get(0));
                }
            }
        }
        variableClassByArc = new int[arcCount];
        int classCount = 0;
        int[] classOfRoot = new int[arcCount];
        Arrays.fill(classOfRoot, -1);
        for (int arcId = 0; arcId < arcCount; arcId++) {
            int root = find(parent, arcId);
            if (classOfRoot[root] < 0) {
                classOfRoot[root] = classCount++;
            }
            variableClassByArc[arcId] = classOfRoot[root];
        }
        variableCount = classCount;

        double[] classWeight = new double[classCount];
        accumulatePerpendicularWeights(arcs, classWeight);

        ExpressionsBasedModel model = new ExpressionsBasedModel();
        Variable[] variableByClass = new Variable[classCount];
        for (int classIndex = 0; classIndex < classCount; classIndex++) {
            variableByClass[classIndex] = model.newVariable("q" + classIndex)
                    .lower(0).integer(true).weight(classWeight[classIndex]);
        }

        addConsistencyConstraints(model, variableByClass);
        addValidityConstraints(model, variableByClass);
        addLayoutConstraints(model, variableByClass);

        System.out.printf(
                "[quantize] arcs=%d classes=%d consistency=%d validity=%d layout=%d%n",
                arcCount, classCount, consistencyConstraintCount,
                validityConstraintCount, layoutConstraintCount);

        Optimisation.Result result = model.minimise();
        if (!result.getState().isFeasible()) {
            throw new IllegalStateException("quantization ILP " + result.getState()
                    + " — a consistent T-mesh always admits the all-ones quantization");
        }
        optimal = result.getState().isOptimal();
        objectiveValue = result.getValue();

        quantizedLengthByArc = new int[arcCount];
        int zeroArcs = 0;
        for (int arcId = 0; arcId < arcCount; arcId++) {
            quantizedLengthByArc[arcId] = (int) Math.round(
                    result.doubleValue(variableClassByArc[arcId]));
            if (quantizedLengthByArc[arcId] == 0) {
                zeroArcs++;
            }
        }
        int violations = verifySolution();
        System.out.printf(
                "[quantize] state=%s objective=%.3f zeroArcs=%d/%d violations=%d%n",
                result.getState(), objectiveValue, zeroArcs, arcCount, violations);
        return this;
    }

    /**
     * Eq. (5) weights: each valid patch contributes half its perpendicular
     * extent (mean parametric length of the two adjacent sides) to every arc
     * on the side pair it measures across; class weights accumulate over
     * members. Arcs only bounded by invalid cycles keep weight zero — the
     * solver then has no coarseness pressure on them, which is harmless since
     * zero stays feasible.
     */
    private void accumulatePerpendicularWeights(List<TraceArc> arcs, double[] classWeight) {
        for (TMeshPatch patch : motorcycleGraph.patches) {
            if (!patch.validRectangle) {
                continue;
            }
            double[] sideLengths = new double[4];
            for (int sideIndex = 0; sideIndex < 4; sideIndex++) {
                double total = 0.0;
                for (int arcId : patch.sides.get(sideIndex)) {
                    total += arcs.get(arcId).parametricLength;
                }
                sideLengths[sideIndex] = total;
            }
            for (int sideIndex = 0; sideIndex < 4; sideIndex++) {
                double perpendicularExtent = 0.5 * (sideLengths[(sideIndex + 1) % 4]
                        + sideLengths[(sideIndex + 3) % 4]);
                for (int arcId : patch.sides.get(sideIndex)) {
                    classWeight[variableClassByArc[arcId]] += 0.5 * perpendicularExtent;
                }
            }
        }
    }

    /**
     * Eq. (2): per valid patch and side pair, the class-coefficient sums of
     * the two sides must be equal; expressions that cancel completely after
     * the §5.2 merge are skipped.
     */
    private void addConsistencyConstraints(ExpressionsBasedModel model, Variable[] variableByClass) {
        int patchIndex = 0;
        for (TMeshPatch patch : motorcycleGraph.patches) {
            patchIndex++;
            if (!patch.validRectangle) {
                continue;
            }
            for (int sideIndex = 0; sideIndex < 2; sideIndex++) {
                double[] coefficientByClass = new double[variableByClass.length];
                for (int arcId : patch.sides.get(sideIndex)) {
                    coefficientByClass[variableClassByArc[arcId]] += 1.0;
                }
                for (int arcId : patch.sides.get(sideIndex + 2)) {
                    coefficientByClass[variableClassByArc[arcId]] -= 1.0;
                }
                boolean nonTrivial = false;
                for (double coefficient : coefficientByClass) {
                    if (coefficient != 0.0) {
                        nonTrivial = true;
                        break;
                    }
                }
                if (!nonTrivial) {
                    continue;
                }
                Expression expression = model.newExpression(
                        "consistency_" + patchIndex + "_" + sideIndex);
                for (int classIndex = 0; classIndex < coefficientByClass.length; classIndex++) {
                    if (coefficientByClass[classIndex] != 0.0) {
                        expression.set(variableByClass[classIndex], coefficientByClass[classIndex]);
                    }
                }
                expression.level(0);
                consistencyConstraintCount++;
            }
        }
    }

    /**
     * Eq. (3): for every singularity trace with a same-sector first meeting
     * {@code n_i*}, the prefix arcs up to it sum to at least one.
     */
    private void addValidityConstraints(ExpressionsBasedModel model, Variable[] variableByClass) {
        for (Trace trace : motorcycleGraph.traces) {
            if (trace.featureTrace || trace.chainArcIds.isEmpty()) {
                continue;
            }
            MetOtherTraceEntry firstSector = trace.firstSectorMeeting();
            if (firstSector == null || firstSector.intersectionNodeId < 0) {
                continue;
            }
            List<Integer> prefix = prefixArcs(trace, firstSector.intersectionNodeId,
                    firstSector.ourParametricLength);
            if (prefix.isEmpty()) {
                continue;
            }
            Expression expression = model.newExpression("validity_" + trace.traceId);
            setPrefixCoefficients(expression, variableByClass, prefix);
            expression.lower(1);
            validityConstraintCount++;
        }
    }

    /**
     * Eq. (4) plus the §4.4 feature rule: for every recorded meeting whose
     * angle ratio exceeds tan α — or unconditionally when the recording trace
     * is a feature chain — the other trace's prefix up to the meeting node
     * sums to at least one. Symmetric recordings are deduplicated by
     * (other trace, node).
     */
    private void addLayoutConstraints(ExpressionsBasedModel model, Variable[] variableByClass) {
        double tanAlpha = Math.tan(alphaRadians);
        Set<Long> emitted = new HashSet<>();
        for (Trace trace : motorcycleGraph.traces) {
            for (MetOtherTraceEntry meeting : trace.metOtherTraces) {
                if (meeting.intersectionNodeId < 0) {
                    continue;
                }
                boolean triggered = trace.featureTrace
                        || meeting.theirParametricLength > tanAlpha * meeting.ourParametricLength;
                if (!triggered) {
                    continue;
                }
                Trace other = motorcycleGraph.traces.get(meeting.otherTraceId);
                if (other.featureTrace || other.chainArcIds.isEmpty()) {
                    continue;
                }
                long key = ((long) meeting.otherTraceId << Integer.SIZE)
                        | (meeting.intersectionNodeId & 0xFFFFFFFFL);
                if (!emitted.add(key)) {
                    continue;
                }
                List<Integer> prefix = prefixArcs(other, meeting.intersectionNodeId,
                        meeting.theirParametricLength);
                if (prefix.isEmpty()) {
                    continue;
                }
                Expression expression = model.newExpression("layout_" + key);
                setPrefixCoefficients(expression, variableByClass, prefix);
                expression.lower(1);
                layoutConstraintCount++;
            }
        }
    }

    /**
     * Arcs along {@code trace} from its origin up to {@code nodeId}; falls
     * back to a parametric-length cutoff when the node is not on the rebuilt
     * chain (co-located meetings can share a node id that the chain skipped).
     */
    private List<Integer> prefixArcs(Trace trace, int nodeId, double parametricLength) {
        List<Integer> prefix = new ArrayList<>();
        for (int position = 0; position < trace.chainArcIds.size(); position++) {
            prefix.add(trace.chainArcIds.get(position));
            if (trace.arcNodeIds.get(position + 1) == nodeId) {
                return prefix;
            }
        }
        prefix.clear();
        for (int position = 0; position < trace.chainArcIds.size(); position++) {
            prefix.add(trace.chainArcIds.get(position));
            if (trace.chainNodeLengths.get(position + 1) >= parametricLength - MotorcycleGraph.PARAMETRIC_EPS) {
                return prefix;
            }
        }
        return prefix;
    }

    /**
     * Accumulate +1 per prefix arc onto its class variable in the expression
     * (duplicate classes in one prefix sum their coefficients).
     */
    private void setPrefixCoefficients(Expression expression, Variable[] variableByClass,
            List<Integer> prefixArcIds) {
        double[] coefficientByClass = new double[variableByClass.length];
        for (int arcId : prefixArcIds) {
            coefficientByClass[variableClassByArc[arcId]] += 1.0;
        }
        for (int classIndex = 0; classIndex < coefficientByClass.length; classIndex++) {
            if (coefficientByClass[classIndex] != 0.0) {
                expression.set(variableByClass[classIndex], coefficientByClass[classIndex]);
            }
        }
    }

    /**
     * Re-check eq. (2) over all valid patches against the rounded solution.
     *
     * @return number of violated consistency equalities (should be zero)
     */
    private int verifySolution() {
        int violations = 0;
        for (TMeshPatch patch : motorcycleGraph.patches) {
            if (!patch.validRectangle) {
                continue;
            }
            for (int sideIndex = 0; sideIndex < 2; sideIndex++) {
                int sideSum = 0;
                for (int arcId : patch.sides.get(sideIndex)) {
                    sideSum += quantizedLengthByArc[arcId];
                }
                int oppositeSum = 0;
                for (int arcId : patch.sides.get(sideIndex + 2)) {
                    oppositeSum += quantizedLengthByArc[arcId];
                }
                if (sideSum != oppositeSum) {
                    violations++;
                }
            }
        }
        return violations;
    }

    private int find(int[] parent, int arcId) {
        int root = arcId;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[arcId] != root) {
            int next = parent[arcId];
            parent[arcId] = root;
            arcId = next;
        }
        return root;
    }

    private void union(int[] parent, int arcA, int arcB) {
        int rootA = find(parent, arcA);
        int rootB = find(parent, arcB);
        if (rootA != rootB) {
            parent[rootB] = rootA;
        }
    }
}
