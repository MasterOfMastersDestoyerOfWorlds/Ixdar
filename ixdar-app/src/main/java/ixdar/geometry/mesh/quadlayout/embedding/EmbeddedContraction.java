package ixdar.geometry.mesh.quadlayout.embedding;

/**
 * Drives LCBK19 §6.1's three re-embedding operators to a fixed point: it applies the zero-arc
 * collapse, the non-simple zero-patch split, and the simple zero-patch collapse
 * <em>"until none can be applied anymore"</em>, and the result — by LCBK19 Proposition 6.1 — is
 * an embedded T-mesh with no zero arcs and no zero patches, ready for a per-patch map.
 *
 * <p>Priority is operator (1) over (2) over (3), which is not a heuristic but the order the
 * operators depend on: operator (2) says its inserted zero-arcs are <em>"then collapsed by
 * operator (1)"</em>, and operator (3) runs <em>"after its zero-arcs have been collapsed"</em>.
 * So the driver always collapses an available zero arc first, splits a non-simple zero-patch
 * only when no zero arc remains to collapse, and collapses a simple zero-patch last.
 *
 * <p>Termination is not assumed, it is checked. Appendix A.3 proves the process terminates by a
 * strictly decreasing measure, and this computes that measure — a weighted count of the excess
 * arcs on non-simple zero-patches, plus the number of zero arcs and zero patches — after every
 * operator and throws if it did not fall. Since the measure is a non-negative integer, a
 * strict decrease per step bounds the number of steps, so a non-terminating bug surfaces
 * immediately rather than as a hang.
 */
public final class EmbeddedContraction {

    /**
     * Weight on the non-simple-excess term of the termination measure. An operator (2) split
     * adds one zero arc and one zero patch (the low-order terms rise by two) while removing one
     * unit of non-simple excess, so the weight must exceed two for the measure to still fall on
     * a split; any value above two works, and this leaves ample margin.
     */
    public static final long NON_SIMPLE_WEIGHT = 1000L;

    public final EmbeddedTMesh tmesh;
    public final int expectedEulerCharacteristic;
    public final ZeroArcCollapseOperator collapseArc;
    public final ZeroPatchSplitOperator splitPatch;
    public final ZeroPatchCollapseOperator collapsePatch;

    public int arcCollapseCount;
    public int patchSplitCount;
    public int patchCollapseCount;

    /** The reroute failure that stopped {@link #contractToFailure}, or null if none occurred. */
    public ArcRerouteFailure failure;

    /** A description of the operator applied most recently, for reporting which step broke. */
    public String lastStep;

    /**
     * Builds the driver and its three operators over a T-mesh.
     *
     * @param tmesh                       embedded T-mesh to contract
     * @param expectedEulerCharacteristic the surface's characteristic, checked after each step
     */
    public EmbeddedContraction(EmbeddedTMesh tmesh, int expectedEulerCharacteristic) {
        this.tmesh = tmesh;
        this.expectedEulerCharacteristic = expectedEulerCharacteristic;
        this.collapseArc = new ZeroArcCollapseOperator(tmesh);
        this.splitPatch = new ZeroPatchSplitOperator(tmesh);
        this.collapsePatch = new ZeroPatchCollapseOperator(tmesh);
    }

    /**
     * Contracts the T-mesh until no operator applies, validating the decomposition and the
     * termination measure after every step.
     *
     * @return this, contracted
     * @throws IllegalStateException when the T-mesh stops being a cell decomposition or the
     *                               termination measure fails to strictly decrease
     */
    public EmbeddedContraction contract() {
        long measure = terminationMeasure();
        while (applyOneOperator()) {
            tmesh.validate(expectedEulerCharacteristic);
            long next = terminationMeasure();
            if (next >= measure) {
                throw new IllegalStateException("contraction did not make progress: the"
                        + " termination measure went from " + measure + " to " + next);
            }
            measure = next;
        }
        return this;
    }

    /**
     * Contracts the T-mesh until either it is fully re-embedded or an operator hits a reroute
     * wall, keeping the (partially mutated) T-mesh in place for inspection. Unlike {@link
     * #contract}, a reroute failure is caught and returned rather than propagated, so a caller can
     * render the wall it carries.
     *
     * @return the reroute failure that stopped the contraction, or null when it ran to completion
     * @throws IllegalStateException when the T-mesh stops being a cell decomposition, which is a
     *                               different, non-recoverable fault than a reroute wall
     */
    public ArcRerouteFailure contractToFailure() {
        while (true) {
            String before = liveCounts();
            try {
                if (!applyOneOperator()) {
                    return null;
                }
            } catch (ArcRerouteFailure caught) {
                this.failure = caught;
                return caught;
            }
            try {
                tmesh.validate(expectedEulerCharacteristic);
            } catch (IllegalStateException broken) {
                throw new IllegalStateException(broken.getMessage() + " | broken by " + lastStep
                        + " | counts before " + before + " after " + liveCounts(), broken);
            }
        }
    }

    /**
     * The live node, arc and patch counts as a compact {@code V/E/F} string, for reporting which
     * operator broke the cell decomposition and by how much.
     *
     * @return the live counts
     */
    private String liveCounts() {
        long liveNodes = tmesh.nodes.stream().filter(node -> node.alive).count();
        long liveArcs = tmesh.arcs.stream().filter(arc -> arc.alive).count();
        long livePatches = tmesh.patches.stream().filter(patch -> patch.alive).count();
        return liveNodes + "/" + liveArcs + "/" + livePatches;
    }

    /**
     * Applies the highest-priority applicable operator, and reports whether one applied.
     *
     * @return true when an operator was applied, false when none can be
     */
    private boolean applyOneOperator() {
        int arc = collapseArc.nextCollapsibleArc();
        if (arc != EmbeddedTMesh.NONE) {
            lastStep = "zero-arc collapse of arc " + arc;
            collapseArc.collapse(arc);
            arcCollapseCount++;
            return true;
        }
        int nonSimple = splitPatch.nextNonSimpleZeroPatch();
        if (nonSimple != EmbeddedTMesh.NONE) {
            lastStep = "non-simple zero-patch split of patch " + nonSimple;
            splitPatch.split(nonSimple);
            patchSplitCount++;
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
     * The Appendix A.3 termination measure: {@code w · Σ max(0, nonZeroArcs(P) − 2)} over live
     * zero-patches, plus the number of live zero arcs and the number of live zero patches.
     *
     * @return the measure, a non-negative integer that every operator strictly decreases
     */
    public long terminationMeasure() {
        long excess = 0;
        long zeroPatches = 0;
        for (EmbeddedPatch patch : tmesh.patches) {
            if (patch.alive && tmesh.isZeroPatch(patch.patchId)) {
                zeroPatches++;
                excess += Math.max(0, tmesh.nonZeroArcCount(patch.patchId) - 2);
            }
        }
        long zeroArcs = 0;
        for (EmbeddedArc arc : tmesh.arcs) {
            if (arc.alive && arc.quantizedLength == 0) {
                zeroArcs++;
            }
        }
        return NON_SIMPLE_WEIGHT * excess + zeroArcs + zeroPatches;
    }
}
