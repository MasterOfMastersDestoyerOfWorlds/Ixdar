package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.platform.Platforms;

/**
 * Contracts an embedded T-mesh to a fixed point with the three zero-element
 * operators (LCBK19 Section 6.1), then extends surviving T-junctions so the
 * layout conforms (LCK21a Section 6). The network is mutated in place; the
 * operators and their counters are this stage's per-run scratch.
 *
 * <p>See also: LCBK19 Appendix A.3
 */
@MeshNodeAnnotation(id = "tmesh_contract", desktopOnly = true)
public final class NetworkContraction implements MeshNode {

    public static final InputPort TMESH = new InputPort("tmesh", PortType.ARC_NETWORK, null);
    public static final InputPort CONFORM = new InputPort("conform", PortType.BOOLEAN, Boolean.TRUE);
    public static final InputPort RECARVE = new InputPort("recarve", PortType.BOOLEAN, Boolean.TRUE);
    public static final OutputPort TMESH_OUT = new OutputPort(TMESH.name, PortType.ARC_NETWORK);

    /**
     * Debug switch: when true, {@link #contract} runs the full validate sweep
     * after every collapse and enables the operators' scan cross-checks. Flip by
     * hand when localizing a contraction bug; the sweep is O(elements) per
     * collapse and dominates contraction time.
     */
    public static final boolean VALIDATE_EVERY_COLLAPSE = false;

    /**
     * Debug switch: when true, every collapse checks the arcs still cut the copy
     * into exactly the live patches, so a torn arrangement names the collapse that
     * tore it instead of surfacing in the re-carve.
     */
    public static final boolean VALIDATE_PARTITION_EVERY_COLLAPSE = false;

    /** Collapses between [contract] progress log lines. */
    private static final int CONTRACT_PROGRESS_INTERVAL = 500;

    /**
     * Longest quiet stretch between [contract] lines, so a grinding collapse stays
     * visible.
     */
    private static final long CONTRACT_PROGRESS_NANOS = 2_000_000_000L;

    /**
     * Divisor turning elapsed nanoseconds into the seconds the log lines report.
     */
    private static final double NANOS_PER_SECOND = 1.0e9;

    /** The network being contracted; null on the inert registry instance. */
    public ArcNetwork network;

    public ZeroPatchSplitOperator splitPatch;

    public ZeroArcCollapseOperator collapseArc;

    public ZeroPatchCollapseOperator collapsePatch;

    public TJunctionExtension extendTJunction;

    public int arcCollapseCount;
    public int patchSplitCount;
    public int patchCollapseCount;

    /**
     * Timestamp of the last [contract] progress line, for the time-based fallback.
     */
    public long lastContractProgressNanos;

    /** The last operator {@link #applyCollapse} applied, naming it in stepped diagnostics. */
    public String lastOperatorDescription = "";

    /** Inert node-registry instance; evaluation binds a fresh contraction. */
    public NetworkContraction() {
    }

    /**
     * Binds the operators to a network so it can be contracted.
     *
     * @param network embedded T-mesh to contract in place
     */
    public NetworkContraction(ArcNetwork network) {
        this.network = network;
        this.splitPatch = new ZeroPatchSplitOperator(network);
        this.collapseArc = new ZeroArcCollapseOperator(network);
        this.collapsePatch = new ZeroPatchCollapseOperator(network);
        this.extendTJunction = new TJunctionExtension(network);
    }

    @Override
    public List<InputPort> inputs() {
        return List.of(TMESH, CONFORM, RECARVE);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(TMESH_OUT);
    }

    @Override
    public String description() {
        return "Contracts an embedded T-mesh until no zero arc or patch remains, then optionally"
                + " extends surviving T-junctions so the layout conforms.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                TMESH.name, "Embedded T-mesh in (from layout_embedding, mutated in place) and the"
                        + " contracted, by default conforming, T-mesh out.",
                CONFORM.name, "Whether to extend T-junctions after contraction so every patch conforms.",
                RECARVE.name, "Whether to conform and replay the contracted layout onto a fresh working"
                        + " copy; off, the rounds run in place and surviving T-junctions remain."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        ArcNetwork input = (ArcNetwork) ctx.getInput(TMESH.name, Object.class);
        Boolean conformInput = ctx.getInput(CONFORM.name, Boolean.class);
        Boolean recarveInput = ctx.getInput(RECARVE.name, Boolean.class);
        boolean conformRequested = conformInput == null || conformInput;
        boolean recarveRequested = recarveInput == null || recarveInput;
        NetworkContraction contraction = new NetworkContraction(input);
        ArcNetwork result = recarveRequested ? contraction.contract() : contraction.contractRounds();
        if (conformRequested) {
            result = new NetworkContraction(result).conform();
        }
        ctx.setOutput(TMESH_OUT.name, result);
    }

    /**
     * Contracts the T-mesh to its fixed point, conforms it, and re-carves it onto
     * a fresh working copy: the pipeline's composition of the three stages.
     *
     * @return the contracted layout, re-carved onto a fresh working copy
     */
    public ArcNetwork contract() {
        contractRounds();
        conform();
        if (VALIDATE_PARTITION_EVERY_COLLAPSE) {
            network.requireArrangementMatchesPatches(progress(CONFORM.name));
        }
        return recarve(network.topology.sourceMesh);
    }

    /**
     * Runs the contraction rounds to their fixed point in place; the result may
     * still carry T-junctions, which {@link #conform()} extends. A round (LCBK19
     * Appendix A.3) is one operator (2) split, then operators (1) and (3) to
     * exhaustion.
     *
     * @return the network, contracted in place
     */
    private ArcNetwork contractRounds() {
        network.labelPatchCovers();
        while (true) {
            while (applyCollapse()) {
                if (VALIDATE_EVERY_COLLAPSE) {
                    network.validate();
                }
            }
            network.validate();
            network.requireArcFlanksMatchCovers(progress("collapse round"));
            int nonSimple = splitPatch.nextNonSimpleZeroPatch();
            if (nonSimple == ArcNetwork.NONE) {
                break;
            }
            splitPatch.split(nonSimple);
            patchSplitCount++;
            network.validate();
            network.requireArcFlanksMatchCovers(progress("patch split " + nonSimple));
            if (VALIDATE_PARTITION_EVERY_COLLAPSE) {
                network.requireArrangementMatchesPatches(progress("patch split " + nonSimple));
            }
        }
        Platforms.log("[contract] relabel floods=%d faces=%d | %.3fs%n",
                network.relabelCallCount, network.relabelFacesFlooded,
                network.relabelNanos / NANOS_PER_SECOND);
        return network;
    }

    /**
     * Extends every surviving T-junction across its patch, leaving a conforming
     * layout, and validates the result.
     *
     * <p>
     * Run on a contracted T-mesh: an extension arc carries the patch's orthogonal
     * extent, which is only meaningful once no patch has a zero side.
     *
     * <p>
     * See also: LCK21a Section 6
     *
     * @return the network, conforming
     */
    public ArcNetwork conform() {
        extendTJunction.extendAll();
        network.validate();
        requireNoTJunction();
        return network;
    }

    /**
     * Replays the network's live arrangement on a clean copy of the original
     * surface mesh, preserving patch combinatorics and surface curves while
     * discarding contraction triangulation debris.
     *
     * @param originalMesh source triangle mesh to rebuild the working copy from
     * @return a fresh T-mesh with the same live layout over a less dense working
     *         copy
     */
    public ArcNetwork recarve(HalfEdgeMesh originalMesh) {
        return new ArcNetworkRecarve(network, originalMesh).build();
    }

    /**
     * The live, floodable patches the last {@link #contractStep} touched, in first-touch order:
     * every {@code markPatchChanged} call it made, plus the whole touched set of an arc collapse.
     *
     * @param arcCollapsesBefore the arc-collapse count before the step, which says whether the
     *                           step was an arc collapse
     * @return the resolved patch ids, deduplicated
     */
    public List<Integer> stepUpdatedPatches(int arcCollapsesBefore) {
        Set<Integer> resolved = new LinkedHashSet<>();
        for (int index = 0; index < network.stepPatchLog.size(); index++) {
            resolved.add(network.topology.resolvePatch(network.stepPatchLog.get(index)));
        }
        if (arcCollapseCount > arcCollapsesBefore) {
            for (int index = 0; index < collapseArc.touchedPatchCount; index++) {
                resolved.add(network.topology.resolvePatch(collapseArc.touchedPatches[index]));
            }
        }
        List<Integer> updated = new ArrayList<>();
        for (int patchId : resolved) {
            if (patchId != ArcNetwork.NONE && network.patches.get(patchId).alive
                    && network.corridor.hasSeedableBoundary(patchId)) {
                updated.add(patchId);
            }
        }
        return updated;
    }

    /**
     * Applies exactly one operator and stops, for stepping the contraction by hand.
     * Prefers the two measure-lowering operators and falls back to a patch split.
     *
     * @return a one-line description of what applied, or {@code null} at the fixed
     *         point
     */
    public String contractStep() {
        network.stepPatchLog.clear();
        int verticesBefore = network.topology.copy.vertexCount();
        int splitsBefore = collapseArc.rerouter.refinedEdgeSplitCount
                + splitPatch.rerouter.refinedEdgeSplitCount;
        String operator;
        if (applyCollapse()) {
            operator = lastOperatorDescription;
        } else {
            int nonSimple = splitPatch.nextNonSimpleZeroPatch();
            if (nonSimple == ArcNetwork.NONE) {
                return null;
            }
            splitPatch.split(nonSimple);
            patchSplitCount++;
            operator = "patchSplit " + nonSimple;
        }
        network.validate();
        // Stepping is the debug path: unlike contract()'s per-round check, a stepped tear
        // names the exact operator that tore the covers.
        network.requireArcFlanksMatchCovers(progress(operator));
        return String.format("%s collapses=%d patchSplits=%d edgeSplits=+%d V=%d(+%d) F=%d",
                operator, arcCollapseCount, patchSplitCount,
                collapseArc.rerouter.refinedEdgeSplitCount
                        + splitPatch.rerouter.refinedEdgeSplitCount - splitsBefore,
                network.topology.copy.vertexCount(),
                network.topology.copy.vertexCount() - verticesBefore,
                network.topology.copy.faceCount());
    }

    /**
     * Checks no live patch still carries a T-junction, the post-condition
     * {@link TJunctionExtension} exists to establish.
     *
     * @throws IllegalStateException when an interior side node still carries a
     *                               third arc
     */
    /**
     * Decorates an operator label with the running operator counts, so a thrown
     * diagnostic names how far the contraction had come.
     *
     * @param operator label of the operator just applied
     * @return the label with collapse, patch-collapse, and split counts appended
     */
    private String progress(String operator) {
        return operator + " (collapse " + arcCollapseCount + ", patchCollapse "
                + patchCollapseCount + ", split " + patchSplitCount + ")";
    }

    private void requireNoTJunction() {
        for (EmbeddedPatch patch : network.patches) {
            if (!patch.alive) {
                continue;
            }
            for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                List<Integer> sideNodes = patch.sideNodeIds.get(side);
                for (int index = 1; index < sideNodes.size() - 1; index++) {
                    if (network.degree(sideNodes.get(index)) > 2) {
                        throw new IllegalStateException("patch " + patch.patchId + " side " + side
                                + " still carries a T-junction at node " + sideNodes.get(index));
                    }
                }
            }
        }
    }

    /**
     * Applies one simple zero-patch collapse, or a zero-arc collapse when no patch
     * is ready.
     *
     * <p>
     * Operator (3) goes first because it alone hands mesh back, and a standing
     * bigon is a chord channel every later re-route pays to cross. See also: LCBK19
     * Figure 9g
     *
     * @return true when one of the two applied
     */
    private boolean applyCollapse() {
        int simple = collapsePatch.nextSimpleZeroPatch();
        if (simple != ArcNetwork.NONE) {
            collapsePatch.collapse(simple);
            patchCollapseCount++;
            lastOperatorDescription = "patch collapse " + simple;
            if (VALIDATE_PARTITION_EVERY_COLLAPSE) {
                network.requireArrangementMatchesPatches(progress(lastOperatorDescription));
            }
            return true;
        }
        int arc = collapseArc.mostContendedArc();
        if (arc != ArcNetwork.NONE) {
            collapseArc.collapse(arc);
            arcCollapseCount++;
            lastOperatorDescription = "arc collapse " + arc;
            if (VALIDATE_PARTITION_EVERY_COLLAPSE) {
                network.requireArrangementMatchesPatches(progress(lastOperatorDescription));
            }
            long now = System.nanoTime();
            if (arcCollapseCount % CONTRACT_PROGRESS_INTERVAL == 0
                    || now - lastContractProgressNanos > CONTRACT_PROGRESS_NANOS) {
                lastContractProgressNanos = now;
                Platforms.log(
                        "[contract] collapses=%d exactSigns=%d splits=%d worstRoute=%d"
                                + " V=%d F=%d | routes=%d gates=%d gateExpand=%d(virtual=%d)"
                                + " freeSettle=%d(failed=%d) refinedSettle=%d"
                                + " freeRoutes=%d freeFails=%d blocked=%d"
                                + " relabels=%d(faces=%d %.2fs)\n",
                        arcCollapseCount,
                        ExactBarycentricOrient.exactSignCallCount,
                        collapseArc.rerouter.refinedEdgeSplitCount
                                + splitPatch.rerouter.refinedEdgeSplitCount,
                        Math.max(collapseArc.rerouter.mostSplitsInOneRoute,
                                splitPatch.rerouter.mostSplitsInOneRoute),
                        network.topology.copy.vertexCount(),
                        network.topology.copy.faceCount(),
                        collapseArc.rerouter.routeAttemptCount
                                + splitPatch.rerouter.routeAttemptCount,
                        collapseArc.rerouter.gatePassCount + splitPatch.rerouter.gatePassCount,
                        collapseArc.rerouter.gateExpansionCount
                                + splitPatch.rerouter.gateExpansionCount,
                        collapseArc.rerouter.gateVirtualExpansionCount
                                + splitPatch.rerouter.gateVirtualExpansionCount,
                        collapseArc.rerouter.freeSettleCount + splitPatch.rerouter.freeSettleCount,
                        collapseArc.rerouter.freeSettleOnFailureCount
                                + splitPatch.rerouter.freeSettleOnFailureCount,
                        collapseArc.rerouter.refinedSettleCount
                                + splitPatch.rerouter.refinedSettleCount,
                        collapseArc.rerouter.freePassRouteCount
                                + splitPatch.rerouter.freePassRouteCount,
                        collapseArc.rerouter.freePassFailureCount
                                + splitPatch.rerouter.freePassFailureCount,
                        collapseArc.blockedDragCount,
                        network.relabelCallCount, network.relabelFacesFlooded,
                        network.relabelNanos / NANOS_PER_SECOND);
            }
            return true;
        }
        return false;
    }
}
