package ixdar.geometry.mesh.quadlayout.quantization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.MetOtherTraceEntry;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedNode;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.Trace;
import ixdar.platform.Platforms;

/**
 * Constrained T-mesh quantization: assigns a non-negative integer to every arc
 * by solving an integer linear program with one variable per arc equivalence
 * class, consistency, validity and layout separation constraints, and an
 * objective that drives the layout as coarse as those allow.
 *
 * <p>
 * See also: Lyon 2021 Sections 4 and 5
 */
@MeshNodeAnnotation(id = "arc_quantization", desktopOnly = true)
public class QuantizedMeshGrid implements MeshNode {

    public static final InputPort GRAPH = new InputPort("graph", PortType.ARC_NETWORK, null);
    public static final InputPort ALPHA_DEGREES = new InputPort("alpha_degrees", PortType.FLOAT,
            MotorcycleGraph.DEFAULT_ALPHA_DEGREES);
    public static final OutputPort SKELETON = new OutputPort("skeleton", PortType.ARC_NETWORK);
    public static final OutputPort SEPARATION_CUTS = new OutputPort("separation_cuts", PortType.INT);
    public static final OutputPort SEPARATION_VIOLATED = new OutputPort("separation_violated",
            PortType.BOOLEAN);
    public static final OutputPort VARIABLES = new OutputPort("variables", PortType.INT);

    /** Cap on solve→collapse→cut rounds of the CBK15-style separation loop. */
    private static final int MAX_SEPARATION_ROUNDS = 50;

    /** Cap on packed cut paths generated per separation round. */
    private static final int MAX_CUTS_PER_ROUND = 500;

    public final ArcNetwork network;
    public final float alphaRadians;

    /** Solved non-negative integer per arc id; filled by {@link #build()}. */
    public int[] quantizedLengthByArc;

    /** Diagnostics and render products of the collapse half, off the port flow. */
    public LayoutExtraction layout;

    /** Variable-class index per arc id after the §5.2 merge. */
    public int[] variableClassByArc;

    public int variableCount;
    public int consistencyConstraintCount;
    public int validityConstraintCount;
    public int layoutConstraintCount;

    /** Singularity traces whose validity constraint fell back to the full chain. */
    public int fallbackValidityConstraintCount;

    /** Fallback cause: no meeting inside the trace's π/2-sector at all. */
    public int validitySkipNoSectorMeetingCount;

    /** Fallback cause: sector meeting exists but never got an intersection node. */
    public int validitySkipNoNodeCount;

    /** Prefix lookups that missed the meeting node and used the length cutoff. */
    public int prefixFallbackCount;

    /** True when the solved quantization merges two distinct singularities. */
    public boolean singularitySeparationViolated;

    /** Total CBK15-style separation cuts added across all solve rounds. */
    public int separationCutCount;

    public double objectiveValue;
    public boolean optimal;

    /** Per-trace constraint logging, on only for the first separation round. */
    private boolean constraintLoggingEnabled = true;

    /** Inert node-registry instance; evaluation builds a fresh quantization. */
    public QuantizedMeshGrid() {
        this.network = null;
        this.alphaRadians = 0f;
    }

    /**
     * Stores inputs for a Lyon §4–§5 quantization solve.
     *
     * @param network built T-mesh arrangement with subdivided arcs and patch sides
     * @param alphaRadians    Lyon's maximum separatrix deviation α in radians
     */
    public QuantizedMeshGrid(ArcNetwork network, float alphaRadians) {
        this.network = network;
        this.alphaRadians = alphaRadians;
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(GRAPH, ALPHA_DEGREES);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(SKELETON, SEPARATION_CUTS, SEPARATION_VIOLATED, VARIABLES);
    }

    @Override
    public String description() {
        return "Solves the quantization ILP over an arc network (one integer length per arc) and"
                + " collapses zero-quantized arcs into the layout's separatrix skeleton.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GRAPH.name, "Arc network to quantize, from a motorcycle_graph node.",
                ALPHA_DEGREES.name, "Maximum separatrix deviation in degrees, bounding the ILP.",
                SKELETON.name, "The quantized skeleton: collapse clusters plus positive arcs.",
                SEPARATION_CUTS.name, "Separation cuts added beyond Lemma 1 (0 expected).",
                SEPARATION_VIOLATED.name, "Whether the solve merged two distinct singularities.",
                VARIABLES.name, "Variables in the quantization ILP after the class merge."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        ArcNetwork graph = (ArcNetwork) ctx.getInput(GRAPH.name, Object.class);
        float alphaDegrees = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, ALPHA_DEGREES.name, ALPHA_DEGREES.defaultValue),
                MotorcycleGraph.DEFAULT_ALPHA_DEGREES);
        QuantizedMeshGrid quantization =
                new QuantizedMeshGrid(graph, (float) Math.toRadians(alphaDegrees)).build();
        quantization.layout = new LayoutExtraction(quantization).build();
        ctx.setOutput(SKELETON.name, graph);
        ctx.setOutput(SEPARATION_CUTS.name, quantization.separationCutCount);
        ctx.setOutput(SEPARATION_VIOLATED.name, quantization.singularitySeparationViolated);
        ctx.setOutput(VARIABLES.name, quantization.variableCount);
    }

    /**
     * Assemble and solve the quantization ILP, then verify the solution against the
     * constraint families and log a summary.
     *
     * @throws IllegalStateException when the solver reports an infeasible or failed
     *                               state (a consistent T-mesh always admits the
     *                               all-ones solution, so this indicates corrupt
     *                               patch structure)
     * @return this, with {@link #quantizedLengthByArc} populated
     */
    public QuantizedMeshGrid build() {
        List<EmbeddedArc> arcs = network.arcs;
        int arcCount = arcs.size();

        int[] parent = new int[arcCount];
        for (int arcId = 0; arcId < arcCount; arcId++) {
            parent[arcId] = arcId;
        }
        for (EmbeddedPatch patch : network.patches) {
            if (!patch.validRectangle) {
                continue;
            }
            for (int sideIndex = 0; sideIndex < 2; sideIndex++) {
                List<Integer> side = patch.sideArcIds.get(sideIndex);
                List<Integer> oppositeSide = patch.sideArcIds.get(sideIndex + 2);
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

        List<List<Integer>> separationCutPaths = new ArrayList<>();
        ZeroArcCollapse collapse = null;
        for (int round = 0; round <= MAX_SEPARATION_ROUNDS; round++) {
            // ojAlgo models do not support re-minimising after post-solve
            // mutation, so every separation round rebuilds the model from
            // scratch with the accumulated cuts.
            consistencyConstraintCount = 0;
            validityConstraintCount = 0;
            layoutConstraintCount = 0;
            fallbackValidityConstraintCount = 0;
            validitySkipNoSectorMeetingCount = 0;
            validitySkipNoNodeCount = 0;
            prefixFallbackCount = 0;
            constraintLoggingEnabled = round == 0;
            IntegerProgram model = Platforms.get().newIntegerProgram();
            for (int classIndex = 0; classIndex < classCount; classIndex++) {
                model.addVariable("q" + classIndex, classWeight[classIndex]);
            }
            addConsistencyConstraints(model);
            addValidityConstraints(model);
            addLayoutConstraints(model);
            for (int cutIndex = 0; cutIndex < separationCutPaths.size(); cutIndex++) {
                IntegerProgramExpression expression = model.newExpression("separation_" + cutIndex);
                setPrefixCoefficients(expression, separationCutPaths.get(cutIndex));
                expression.lower(1);
            }
            if (round == 0) {
                Platforms.log(
                        "[quantize] arcs=%d classes=%d consistency=%d validity=%d"
                                + " (fallback=%d) layout=%d prefixFallbacks=%d%n",
                        arcCount, classCount, consistencyConstraintCount,
                        validityConstraintCount, fallbackValidityConstraintCount,
                        layoutConstraintCount, prefixFallbackCount);
            }

            IntegerProgramSolution result = model.minimise();
            if (!result.feasible) {
                throw new IllegalStateException("quantization ILP " + result.state
                        + " — a consistent T-mesh always admits the all-ones quantization");
            }
            optimal = result.optimal;
            objectiveValue = result.objectiveValue;

            quantizedLengthByArc = new int[arcCount];
            int zeroArcs = 0;
            for (int arcId = 0; arcId < arcCount; arcId++) {
                quantizedLengthByArc[arcId] = (int) Math.round(
                        result.variableValues[variableClassByArc[arcId]]);
                if (quantizedLengthByArc[arcId] == 0) {
                    zeroArcs++;
                }
            }
            int violations = verifySolution();
            Platforms.log(
                    "[quantize] state=%s objective=%.3f zeroArcs=%d/%d violations=%d round=%d%n",
                    result.state, objectiveValue, zeroArcs, arcCount, violations, round);

            collapse = new ZeroArcCollapse(network, quantizedLengthByArc).build();
            if (collapse.mergedSingularityVertexIdsByCluster.isEmpty()) {
                break;
            }
            if (round == MAX_SEPARATION_ROUNDS) {
                break;
            }
            int added = collectSeparationCuts(collapse, separationCutPaths);
            Platforms.log("[quantize] separation round=%d mergedClusters=%d cuts=%d%n",
                    round, collapse.mergedSingularityVertexIdsByCluster.size(), added);
            if (added == 0) {
                break;
            }
        }

        singularitySeparationViolated = !collapse.mergedSingularityVertexIdsByCluster.isEmpty();
        for (List<Integer> merged : collapse.mergedSingularityVertexIdsByCluster) {
            Platforms.log("[quantize] VALIDITY VIOLATION merged singularity vertices=%s%n", merged);
        }
        for (EmbeddedArc arc : network.arcs) {
            arc.quantizedLength = quantizedLengthByArc[arc.arcId];
        }
        return this;
    }

    /**
     * Explicit validity cuts: for every collapse cluster that merged two or more
     * singularities, finds one all-zero arc path between two of them and requires
     * its quantized sum to be at least one.
     *
     * <p>
     * See also: CBK15 Section 4
     *
     * @param collapse zero-arc collapse of the current solution
     * @param cutPaths accumulated cut paths to append to
     * @return number of new cut paths collected
     */
    private int collectSeparationCuts(ZeroArcCollapse collapse, List<List<Integer>> cutPaths) {
        List<List<EmbeddedArc>> zeroArcsByNode = new ArrayList<>(network.nodes.size());
        for (int nodeId = 0; nodeId < network.nodes.size(); nodeId++) {
            zeroArcsByNode.add(new ArrayList<>());
        }
        for (EmbeddedArc arc : network.arcs) {
            if (quantizedLengthByArc[arc.arcId] == 0) {
                zeroArcsByNode.get(arc.startNodeId).add(arc);
                zeroArcsByNode.get(arc.endNodeId).add(arc);
            }
        }
        int added = 0;
        for (List<Integer> mergedVertexIds : collapse.mergedSingularityVertexIdsByCluster) {
            // Path packing: keep extracting connecting zero paths and blocking
            // their arcs until the cluster's singularities disconnect, so one
            // round covers the full braid width of the corridor instead of one
            // strand per solve.
            boolean[] blockedArc = new boolean[network.arcs.size()];
            for (int startVertexIndex = 0; startVertexIndex < mergedVertexIds.size(); startVertexIndex++) {
                while (added < MAX_CUTS_PER_ROUND) {
                    List<Integer> pathArcIds = zeroPathBetweenSingularities(
                            mergedVertexIds.get(startVertexIndex), mergedVertexIds,
                            zeroArcsByNode, blockedArc);
                    if (pathArcIds == null || pathArcIds.isEmpty()) {
                        break;
                    }
                    for (int arcId : pathArcIds) {
                        blockedArc[arcId] = true;
                    }
                    cutPaths.add(pathArcIds);
                    separationCutCount++;
                    added++;
                }
            }
        }
        return added;
    }

    /**
     * BFS over zero-quantized arcs from one merged singularity to the nearest node
     * of any other singularity vertex in the same cluster, returning the connecting
     * arc path.
     *
     * @param startVertexId   singularity vertex to start from
     * @param mergedVertexIds singularity vertex ids sharing one collapse cluster
     * @param zeroArcsByNode  zero-arc adjacency per node id
     * @param blockedArc      arcs already claimed by a packed path this round
     * @return arc ids of one connecting path, or {@code null} if none found
     */
    private List<Integer> zeroPathBetweenSingularities(int startVertexId,
            List<Integer> mergedVertexIds, List<List<EmbeddedArc>> zeroArcsByNode,
            boolean[] blockedArc) {
        int startNodeId = -1;
        Set<Integer> goalNodeIds = new HashSet<>();
        for (EmbeddedNode node : network.nodes) {
            if (!node.critical || node.vertexId < 0
                    || !mergedVertexIds.contains(node.vertexId)) {
                continue;
            }
            if (node.vertexId == startVertexId) {
                startNodeId = node.nodeId;
            } else {
                goalNodeIds.add(node.nodeId);
            }
        }
        if (startNodeId < 0 || goalNodeIds.isEmpty()) {
            return null;
        }
        int[] arcIntoNode = new int[network.nodes.size()];
        int[] cameFromNode = new int[network.nodes.size()];
        Arrays.fill(arcIntoNode, -1);
        Arrays.fill(cameFromNode, -1);
        List<Integer> frontier = new ArrayList<>();
        frontier.add(startNodeId);
        boolean[] visited = new boolean[network.nodes.size()];
        visited[startNodeId] = true;
        int head = 0;
        while (head < frontier.size()) {
            int nodeId = frontier.get(head++);
            if (goalNodeIds.contains(nodeId)) {
                List<Integer> pathArcIds = new ArrayList<>();
                int walk = nodeId;
                while (walk != startNodeId) {
                    pathArcIds.add(arcIntoNode[walk]);
                    walk = cameFromNode[walk];
                }
                return pathArcIds;
            }
            for (EmbeddedArc arc : zeroArcsByNode.get(nodeId)) {
                if (blockedArc[arc.arcId]) {
                    continue;
                }
                int neighbor = arc.startNodeId == nodeId ? arc.endNodeId : arc.startNodeId;
                if (visited[neighbor]) {
                    continue;
                }
                visited[neighbor] = true;
                arcIntoNode[neighbor] = arc.arcId;
                cameFromNode[neighbor] = nodeId;
                frontier.add(neighbor);
            }
        }
        return null;
    }

    /**
     * Objective weights: each valid patch contributes half its perpendicular extent
     * to every arc on the side pair it measures across, accumulated per class. Arcs
     * bounded only by invalid cycles keep weight zero.
     */
    private void accumulatePerpendicularWeights(List<EmbeddedArc> arcs, double[] classWeight) {
        for (EmbeddedPatch patch : network.patches) {
            if (!patch.validRectangle) {
                continue;
            }
            double[] sideLengths = new double[4];
            for (int sideIndex = 0; sideIndex < 4; sideIndex++) {
                double total = 0.0;
                for (int arcId : patch.sideArcIds.get(sideIndex)) {
                    total += arcs.get(arcId).parametricLength;
                }
                sideLengths[sideIndex] = total;
            }
            for (int sideIndex = 0; sideIndex < 4; sideIndex++) {
                double perpendicularExtent = 0.5 * (sideLengths[(sideIndex + 1) % 4]
                        + sideLengths[(sideIndex + 3) % 4]);
                for (int arcId : patch.sideArcIds.get(sideIndex)) {
                    classWeight[variableClassByArc[arcId]] += 0.5 * perpendicularExtent;
                }
            }
        }
    }

    /**
     * Eq. (2): per valid patch and side pair, the class-coefficient sums of the two
     * sides must be equal; expressions that cancel completely after the §5.2 merge
     * are skipped.
     */
    private void addConsistencyConstraints(IntegerProgram model) {
        int patchIndex = 0;
        for (EmbeddedPatch patch : network.patches) {
            patchIndex++;
            if (!patch.validRectangle) {
                continue;
            }
            for (int sideIndex = 0; sideIndex < 2; sideIndex++) {
                double[] coefficientByClass = new double[variableCount];
                for (int arcId : patch.sideArcIds.get(sideIndex)) {
                    coefficientByClass[variableClassByArc[arcId]] += 1.0;
                }
                for (int arcId : patch.sideArcIds.get(sideIndex + 2)) {
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
                IntegerProgramExpression expression = model.newExpression(
                        "consistency_" + patchIndex + "_" + sideIndex);
                for (int classIndex = 0; classIndex < coefficientByClass.length; classIndex++) {
                    if (coefficientByClass[classIndex] != 0.0) {
                        expression.set(classIndex, coefficientByClass[classIndex]);
                    }
                }
                expression.level(0);
                consistencyConstraintCount++;
            }
        }
    }

    /**
     * Validity constraints: for every singularity trace with a same-sector first
     * meeting, the prefix arcs up to it sum to at least one. A trace with no usable
     * first meeting falls back to {@code Σ chain ≥ 1}.
     *
     * <p>
     * See also: Lyon 2021 Section 4
     */
    private void addValidityConstraints(IntegerProgram model) {
        for (Trace trace : network.traces) {
            if (trace.chainArcIds.isEmpty()) {
                continue;
            }
            MetOtherTraceEntry best = null;
            for (MetOtherTraceEntry entry : trace.metOtherTraces) {
                if (entry.theirParametricLength >= entry.ourParametricLength) {
                    continue;
                }
                if (best == null || entry.ourParametricLength < best.ourParametricLength) {
                    best = entry;
                }
            }
            MetOtherTraceEntry firstSector = best;
            if (trace.featureTrace) {
                firstSector = null;
            }
            List<Integer> prefix;
            if (firstSector == null || firstSector.intersectionNodeId < 0) {
                if (firstSector == null) {
                    validitySkipNoSectorMeetingCount++;
                } else {
                    validitySkipNoNodeCount++;
                }
                if (constraintLoggingEnabled) {
                    int terminalNodeId = trace.arcNodeIds.get(trace.arcNodeIds.size() - 1);
                    Platforms.log(
                            "[quantize] validity fallback trace=%d reason=%s terminalCritical=%b"
                                    + " terminalBorder=%b chainArcs=%d meetings=%d%n",
                            trace.traceId,
                            firstSector == null ? "noSectorMeeting" : "sectorMeetingWithoutNode",
                            network.nodes.get(terminalNodeId).critical,
                            network.nodes.get(terminalNodeId).border,
                            trace.chainArcIds.size(), trace.metOtherTraces.size());
                }
                prefix = trace.chainArcIds;
                fallbackValidityConstraintCount++;
            } else {
                prefix = prefixArcs(trace, firstSector.intersectionNodeId,
                        firstSector.ourParametricLength);
            }
            if (prefix.isEmpty()) {
                continue;
            }
            IntegerProgramExpression expression = model.newExpression("validity_" + trace.traceId);
            setPrefixCoefficients(expression, prefix);
            expression.lower(1);
            validityConstraintCount++;
        }
    }

    /**
     * Layout constraints: for every recorded meeting whose angle ratio exceeds tan
     * α, or unconditionally when the recording trace is a feature chain, the other
     * trace's prefix up to the meeting node sums to at least one. Symmetric
     * recordings are deduplicated by (other trace, node).
     *
     * <p>
     * See also: Lyon 2021 Section 4.4
     */
    private void addLayoutConstraints(IntegerProgram model) {
        double tanAlpha = Math.tan(alphaRadians);
        Set<Long> emitted = new HashSet<>();
        for (Trace trace : network.traces) {
            for (MetOtherTraceEntry meeting : trace.metOtherTraces) {
                if (meeting.intersectionNodeId < 0) {
                    continue;
                }
                // The l_ji/l_ij > tan α ratio is the perpendicular right-triangle
                // form of |α_ij| > α; a collinear (head-on crash) meeting has
                // deviation exactly zero and must not trigger it.
                if (meeting.ourAxis == meeting.otherAxis) {
                    continue;
                }
                // Section 4.3 orients each crossing pair as l_ij >= l_ji and adds one
                // constraint, on the shorter trace's prefix S_ji. Read from the shorter
                // side the ratio test is vacuously true, so emitting both directions
                // would separate every crossing unconditionally.
                boolean orientedPair = meeting.ourParametricLength >= meeting.theirParametricLength;
                boolean triggered = trace.featureTrace || (orientedPair
                        && meeting.theirParametricLength > tanAlpha * meeting.ourParametricLength);
                if (!triggered) {
                    continue;
                }
                Trace other = network.traces.get(meeting.otherTraceId);
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
                IntegerProgramExpression expression = model.newExpression("layout_" + key);
                setPrefixCoefficients(expression, prefix);
                expression.lower(1);
                layoutConstraintCount++;
            }
        }
    }

    /**
     * Arcs along {@code trace} from its origin up to {@code nodeId}; falls back to
     * a parametric-length cutoff when the node is not on the rebuilt chain
     * (co-located meetings can share a node id that the chain skipped).
     */
    private List<Integer> prefixArcs(Trace trace, int nodeId, double parametricLength) {
        List<Integer> prefix = new ArrayList<>();
        for (int position = 0; position < trace.chainArcIds.size(); position++) {
            prefix.add(trace.chainArcIds.get(position));
            if (trace.arcNodeIds.get(position + 1) == nodeId) {
                return prefix;
            }
        }
        prefixFallbackCount++;
        prefix.clear();
        for (int position = 0; position < trace.chainArcIds.size(); position++) {
            prefix.add(trace.chainArcIds.get(position));
            if (trace.chainNodeLengths.get(position + 1) >= parametricLength - 1e-9) {
                return prefix;
            }
        }
        return prefix;
    }

    /**
     * Accumulate +1 per prefix arc onto its class variable in the expression
     * (duplicate classes in one prefix sum their coefficients).
     */
    private void setPrefixCoefficients(IntegerProgramExpression expression,
            List<Integer> prefixArcIds) {
        double[] coefficientByClass = new double[variableCount];
        for (int arcId : prefixArcIds) {
            coefficientByClass[variableClassByArc[arcId]] += 1.0;
        }
        for (int classIndex = 0; classIndex < coefficientByClass.length; classIndex++) {
            if (coefficientByClass[classIndex] != 0.0) {
                expression.set(classIndex, coefficientByClass[classIndex]);
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
        for (EmbeddedPatch patch : network.patches) {
            if (!patch.validRectangle) {
                continue;
            }
            for (int sideIndex = 0; sideIndex < 2; sideIndex++) {
                int sideSum = 0;
                for (int arcId : patch.sideArcIds.get(sideIndex)) {
                    sideSum += quantizedLengthByArc[arcId];
                }
                int oppositeSum = 0;
                for (int arcId : patch.sideArcIds.get(sideIndex + 2)) {
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
