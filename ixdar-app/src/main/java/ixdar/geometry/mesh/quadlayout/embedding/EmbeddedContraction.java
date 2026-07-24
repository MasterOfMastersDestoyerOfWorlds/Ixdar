package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the three re-embedding operators to a fixed point, leaving no zero
 * arcs or zero patches.
 *
 * <p>
 * The collapsing operators go first — zero-arc, then simple zero-patch — and
 * the non-simple zero-patch split only when neither applies. The measure
 * decreases per round.
 *
 * <p>
 * See also: LCBK19 Appendix A.3
 */
public final class EmbeddedContraction {

    public final EmbeddedTMesh tmesh;
    public final int expectedEulerCharacteristic;
    public final ZeroArcCollapseOperator collapseArc;
    public final ZeroPatchSplitOperator splitPatch;
    public final ZeroPatchCollapseOperator collapsePatch;

    public int arcCollapseCount;
    public int patchSplitCount;
    public int patchCollapseCount;

    /**
     * The reroute failure that stopped {@link #contractToFailure}, or null if none
     * occurred.
     */
    public ArcRerouteFailure failure;

    /**
     * A description of the operator applied most recently, for reporting which step
     * broke.
     */
    public String lastStep;

    /**
     * Builds the driver and its three operators over a T-mesh.
     *
     * @param tmesh                       embedded T-mesh to contract
     * @param expectedEulerCharacteristic the surface's characteristic, checked
     *                                    after each step
     */
    public EmbeddedContraction(EmbeddedTMesh tmesh, int expectedEulerCharacteristic) {
        this.tmesh = tmesh;
        this.expectedEulerCharacteristic = expectedEulerCharacteristic;
        this.collapseArc = new ZeroArcCollapseOperator(tmesh);
        this.splitPatch = new ZeroPatchSplitOperator(tmesh);
        this.collapsePatch = new ZeroPatchCollapseOperator(tmesh);
    }

    /**
     * Contracts the T-mesh, validating the decomposition every step and the measure
     * every round.
     *
     * <p>
     * A round is what LCBK19 Appendix A.3 measures: one operator (2) split, then
     * operators (1) and (3) to exhaustion, against the state before the split.
     * Operator (2) raises the measure by design; the other two lower it.
     *
     * @throws IllegalStateException when the T-mesh stops being a cell
     *                               decomposition or a round fails to strictly
     *                               decrease the termination measure
     * @return this, contracted
     */
    public EmbeddedContraction contract() {
        while (true) {
            while (applyCollapse()) {
                tmesh.validate(expectedEulerCharacteristic);
                terminationMeasure();
            }
            int nonSimple = splitPatch.nextNonSimpleZeroPatch();
            if (nonSimple == EmbeddedTMesh.NONE) {
                return this;
            }
            lastStep = "non-simple zero-patch split of patch " + nonSimple;
            splitPatch.split(nonSimple);
            patchSplitCount++;
            tmesh.validate(expectedEulerCharacteristic);
        }
    }

    /**
     * Applies one zero-arc collapse, or one simple zero-patch collapse when no arc
     * is collapsible. Both lower the termination measure on their own.
     *
     * @return true when one of the two applied
     */
    private boolean applyCollapse() {
        int arc = collapseArc.nextCollapsibleArc();
        if (arc != EmbeddedTMesh.NONE) {
            EmbeddedArc embeddedArc = tmesh.arcs.get(arc);
            String arcDesc = "";
            if (embeddedArc.isLoop()) {
                arcDesc = " [already a loop at node " + embeddedArc.startNodeId + " leftPatch="
                        + embeddedArc.leftPatchId
                        + " rightPatch=" + embeddedArc.rightPatchId
                        + (embeddedArc.leftPatchId == embeddedArc.rightPatchId ? " SAME-PATCH-BOTH-SIDES" : "")
                        + " leftSides=" + describePatchSides(embeddedArc.leftPatchId)
                        + " rightSides=" + describePatchSides(embeddedArc.rightPatchId) + "]";
            }
            lastStep = "zero-arc collapse of arc " + arc + arcDesc;
            collapseArc.collapse(arc);
            arcCollapseCount++;
            return true;
        }
        int simple = collapsePatch.nextSimpleZeroPatch();
        if (simple != EmbeddedTMesh.NONE) {
            lastStep = "simple zero-patch collapse of patch " + simple;
            collapsePatch.collapse(simple);
            patchCollapseCount++;
            return true;
        }
        return false;
    }

    /**
     * The per-side arc counts of a patch, so the report shows whether retiring it
     * would have been the emptied-patch case the collapse already handles.
     *
     * @param patchId patch to describe, or {@link EmbeddedTMesh#NONE}
     * @return the four side sizes, or {@code none}
     */
    private String describePatchSides(int patchId) {
        if (patchId == EmbeddedTMesh.NONE || !tmesh.patches.get(patchId).alive) {
            return "none";
        }
        List<String> sideSizes = new ArrayList<>(EmbeddedPatch.SIDES);
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            List<Integer> sideArcs = tmesh.patches.get(patchId).sideArcIds.get(side);
            StringBuilder described = new StringBuilder();
            for (int sideArcId : sideArcs) {
                described.append(described.length() == 0 ? "" : "+").append(sideArcId)
                        .append(tmesh.arcs.get(sideArcId).isLoop() ? "L" : "")
                        .append(tmesh.arcs.get(sideArcId).quantizedLength == 0 ? "z" : "");
            }
            sideSizes.add(sideArcs.isEmpty() ? "-" : described.toString());
        }
        return String.join("/", sideSizes);
    }

    /**
     * The Appendix A.3 termination measure: <em>"the total number of
     * yet-to-be-collapsed zero-arcs and zero-patches"</em>.
     *
     * <p>
     * Operators (1) and (3) each lower it on their own. Operator (2) raises it
     * deliberately, and only the round that follows it — see {@link #contract} —
     * must bring it back down.
     *
     * @return the count of live zero arcs plus live zero patches
     */
    public long terminationMeasure() {
        long zeroPatches = 0;
        for (EmbeddedPatch patch : tmesh.patches) {
            if (patch.alive && tmesh.isZeroPatch(patch.patchId)) {
                zeroPatches++;
            }
        }
        long zeroArcs = 0;
        for (EmbeddedArc arc : tmesh.arcs) {
            if (arc.alive && arc.quantizedLength == 0) {
                zeroArcs++;
            }
        }
        return zeroArcs + zeroPatches;
    }
}
