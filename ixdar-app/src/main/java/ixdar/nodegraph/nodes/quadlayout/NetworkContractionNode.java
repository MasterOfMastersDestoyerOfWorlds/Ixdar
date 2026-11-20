package ixdar.nodegraph.nodes.quadlayout;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.ArrangementDiagnosticException;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.nodegraph.api.SteppableNode;

/**
 * The {@code tmesh_contract} node: contracts an embedded T-mesh to its fixed point with
 * {@link NetworkContraction}, optionally conforming and re-carving it. Stepped, level 0 applies
 * one operator and level 1 one drag of a zero-arc collapse (LCBK19 Figure 9 e-f).
 */
@MeshNodeAnnotation(id = "tmesh_contract", desktopOnly = true, category = NodeCategory.QUAD_LAYOUT)
public final class NetworkContractionNode implements SteppableNode {

    public static final InputPort TMESH = new InputPort("tmesh", PortType.ARC_NETWORK, null);
    public static final InputPort CONFORM = new InputPort("conform", PortType.BOOLEAN, Boolean.TRUE);
    public static final InputPort RECARVE = new InputPort("recarve", PortType.BOOLEAN, Boolean.TRUE);
    public static final OutputPort TMESH_OUT = new OutputPort(TMESH.name, PortType.ARC_NETWORK);

    public static final int OPERATOR_LEVEL = 0;

    public static final int DRAG_LEVEL = 1;

    /**
     * The patches the last stepped operator touched, drawn as their covers.
     *
     * @param patchIds live patch ids, in first-touch order
     */
    public record UpdatedPatches(List<Integer> patchIds) {
    }

    /** The contraction a stepped run applies to the input network in place, or {@code null}. */
    public NetworkContraction stepping;

    /** What the last step changed, or {@code null}. */
    private Object overlay;

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

    /**
     * A boolean input, true when unwired as its default is.
     *
     * @param ctx  the node's context
     * @param port the boolean input
     * @return the input's value
     */
    private static boolean flag(NodeContext ctx, InputPort port) {
        Boolean value = ctx.getInput(port.name, Boolean.class);
        return value == null || value;
    }

    @Override
    public void evaluate(NodeContext ctx) {
        ArcNetwork input = (ArcNetwork) ctx.getInput(TMESH.name, Object.class);
        NetworkContraction contraction = new NetworkContraction(input);
        ArcNetwork result = flag(ctx, RECARVE) ? contraction.contract() : contraction.contractRounds();
        if (flag(ctx, CONFORM)) {
            result = new NetworkContraction(result).conform();
        }
        ctx.setOutput(TMESH_OUT.name, result);
    }

    @Override
    public List<String> stepLevels() {
        return List.of("operator", "drag");
    }

    /**
     * Starts a stepped contraction of the bound input, its patch covers labelled as
     * {@code contractRounds} labels them before its first operator.
     *
     * @param ctx the node's context, the input network bound
     */
    @Override
    public void beginSteps(NodeContext ctx) {
        stepping = new NetworkContraction((ArcNetwork) ctx.getInput(TMESH.name, Object.class));
        stepping.network.labelPatchCovers();
        overlay = null;
    }

    /**
     * One operator or one drag. An operator step with a collapse mid-drag drags it to the end,
     * and that collapse is the operator.
     *
     * @param ctx   the node's context
     * @param level {@link #OPERATOR_LEVEL} or {@link #DRAG_LEVEL}
     * @throws ArrangementDiagnosticException when the step tears the flanks or the covers
     * @return what the step did, or {@code null} at the fixed point (operator) or with no
     *         collapsible zero arc left (drag)
     */
    @Override
    public String step(NodeContext ctx, int level) {
        ZeroArcCollapseOperator collapse = stepping.collapseArc;
        if (level == DRAG_LEVEL) {
            if (collapse.collapsingArcId == ArcNetwork.NONE) {
                int arcId = collapse.shortestZeroArc();
                if (arcId == ArcNetwork.NONE) {
                    return null;
                }
                collapse.beginCollapse(arcId);
                overlay = collapse.stepDiagnostic();
                return "began collapse of arc " + arcId + "; fan of " + collapse.fan.size()
                        + " arcs waits on node " + collapse.movedNodeId;
            }
            if (collapse.dragNextArc()) {
                overlay = collapse.stepDiagnostic();
                return "dragged arc " + collapse.lastDraggedArcId + " onto vertex "
                        + collapse.targetVertex;
            }
            return finishCollapse();
        }
        if (collapse.collapsingArcId != ArcNetwork.NONE) {
            while (collapse.dragNextArc()) {
                // Each drag moves one fan arc; the merge waits until the fan is empty.
            }
            return finishCollapse();
        }
        int arcCollapsesBefore = stepping.arcCollapseCount;
        String applied = stepping.contractStep();
        overlay = applied == null ? null
                : new UpdatedPatches(stepping.stepUpdatedPatches(arcCollapsesBefore));
        return applied;
    }

    /**
     * Merges the nodes of a collapse whose fan is fully dragged, then runs the tear check
     * {@code contractStep} runs, so a stepped collapse cannot sail past the tear a whole run
     * fails on.
     *
     * @throws ArrangementDiagnosticException when the collapse tore the flanks
     * @return what the collapse did
     */
    private String finishCollapse() {
        int collapsedArcId = stepping.collapseArc.collapsingArcId;
        stepping.collapseArc.finishCollapse();
        stepping.arcCollapseCount++;
        overlay = null;
        ArrangementDiagnosticException tear = stepping.network.flankTearFailure(
                "arc collapse " + collapsedArcId);
        if (tear != null) {
            throw tear;
        }
        return "collapse of arc " + collapsedArcId + " finished; " + stepping.arcCollapseCount
                + " arc collapses so far";
    }

    /**
     * Steps every remaining operator, then conforms and re-carves as the node's flags ask, the
     * same composition {@link #evaluate} runs.
     *
     * @param ctx the node's context
     */
    @Override
    public void finishSteps(NodeContext ctx) {
        while (step(ctx, OPERATOR_LEVEL) != null) {
            // Every operator validates as it applies; the fixed point ends the loop.
        }
        ArcNetwork result = flag(ctx, RECARVE) ? stepping.contract() : stepping.network;
        if (flag(ctx, CONFORM)) {
            result = new NetworkContraction(result).conform();
        }
        overlay = null;
        ctx.setOutput(TMESH_OUT.name, result);
    }

    @Override
    public Object stepShown() {
        return stepping.network;
    }

    @Override
    public Object stepOverlay() {
        return overlay;
    }
}
