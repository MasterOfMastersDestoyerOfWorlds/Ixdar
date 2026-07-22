package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Drives the three re-embedding operators to a fixed point, leaving an embedded T-mesh with no
 * zero arcs and no zero patches.
 *
 * <p>Operators apply in the order they depend on: zero-arc collapse, non-simple zero-patch
 * split, simple zero-patch collapse. The termination measure must strictly decrease per step.
 *
 * <p>See also: LCBK19 Appendix A.3
 */
public final class EmbeddedContraction {

    /**
     * Weight on the non-simple-excess term of the termination measure. Must exceed two, since an
     * operator (2) split removes one unit of excess while raising the low-order terms by two.
     */
    public static final long NON_SIMPLE_WEIGHT = 1000L;

    /** Diagnostic prefix naming an arc in the torn-layout report. */
    private static final String ARC_TAG = " a";

    /** Closing bracket of a bracketed diagnostic group. */
    private static final String CLOSE_GROUP = "]";

    /** How many surviving zero-patches the fixed-point report names before truncating. */
    private static final int SURVIVOR_REPORT_CAP = 12;

    /**
     * System property enabling the per-step region check.
     *
     * <p>Region correspondence holds only at the fixed point, so intermediate states report tears
     * that are not tears. To judge a layout, build {@link PatchRegions} after the contraction has
     * finished.
     */
    private static final String CHECK_REGIONS_PROPERTY = "embeddedTMesh.checkRegions";

    /** Separator between fields of a diagnostic line. */
    private static final String FIELD_SEPARATOR = " | ";

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
            if (Boolean.getBoolean(CHECK_REGIONS_PROPERTY)) {
                try {
                    new PatchRegions(tmesh).build();
                } catch (IllegalStateException torn) {
                    StringBuilder patchArcs = new StringBuilder();
                    for (EmbeddedPatch patch : tmesh.patches) {
                        if (!patch.alive) {
                            continue;
                        }
                        Set<Integer> boundary = new TreeSet<>();
                        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                            boundary.addAll(patch.sideArcIds.get(side));
                        }
                        patchArcs.append(" P").append(patch.patchId).append(boundary);
                    }
                    StringBuilder arcPaths = new StringBuilder();
                    for (EmbeddedArc arc : tmesh.arcs) {
                        if (!arc.alive) {
                            continue;
                        }
                        List<Integer> path = arc.path.copyVertexPath;
                        int startVertex = tmesh.nodes.get(arc.startNodeId).copyVertex;
                        int endVertex = tmesh.nodes.get(arc.endNodeId).copyVertex;
                        int head = path.get(0);
                        int tail = path.get(path.size() - 1);
                        boolean anchored = head == startVertex && tail == endVertex
                                || head == endVertex && tail == startVertex;
                        if (!anchored) {
                            arcPaths.append(ARC_TAG).append(arc.arcId).append(" DANGLES path[")
                                    .append(head).append("..").append(tail).append("] nodes[")
                                    .append(startVertex).append(",").append(endVertex).append(CLOSE_GROUP);
                        }
                        if (new TreeSet<>(path).size() != path.size()) {
                            arcPaths.append(ARC_TAG).append(arc.arcId).append(" NOT-SIMPLE").append(path);
                        }
                        for (int step = 1; step < path.size(); step++) {
                            int edgeId = tmesh.topology.edgeBetween(path.get(step - 1), path.get(step));
                            if (edgeId == EmbeddedMeshTopology.UNCLAIMED
                                    || tmesh.topology.ownerArcByCopyEdge[edgeId] != arc.arcId) {
                                arcPaths.append(ARC_TAG).append(arc.arcId).append(" UNCLAIMED-EDGE@")
                                        .append(step).append("(owner=")
                                        .append(edgeId == EmbeddedMeshTopology.UNCLAIMED ? "noEdge"
                                                : tmesh.topology.ownerArcByCopyEdge[edgeId])
                                        .append(")");
                            }
                        }
                    }
                    StringBuilder degrees = new StringBuilder();
                    for (EmbeddedNode node : tmesh.nodes) {
                        if (node.alive && tmesh.degree(node.nodeId) < 3) {
                            degrees.append(" n").append(node.nodeId).append("deg=")
                                    .append(tmesh.degree(node.nodeId))
                                    .append(tmesh.arcEndsByNode.get(node.nodeId));
                        }
                    }
                    arcPaths.append(" | lowDegreeNodes:").append(degrees);
                    throw new IllegalStateException("regions torn by " + lastStep + FIELD_SEPARATOR
                            + torn.getMessage() + FIELD_SEPARATOR + "live patches:" + patchArcs
                            + FIELD_SEPARATOR + "live arcs:" + arcPaths, torn);
                }
            }
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
        int claimedVertices = 0;
        int gates = 0;
        for (int vertex = 0; vertex < tmesh.topology.ownerArcByCopyVertex.length; vertex++) {
            if (tmesh.topology.ownerArcByCopyVertex[vertex] != EmbeddedMeshTopology.UNCLAIMED
                    || tmesh.topology.ownerNodeByCopyVertex[vertex] != EmbeddedMeshTopology.UNCLAIMED) {
                claimedVertices++;
            }
        }
        for (int index = 0; index < tmesh.topology.copy.edgeCount(); index++) {
            int edgeId = tmesh.topology.copy.edgeIdAt(index);
            if (tmesh.topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            int halfEdge = tmesh.topology.copy.edgeHalfEdge(edgeId);
            int endA = tmesh.topology.copy.halfEdgeVertex(halfEdge);
            int endB = tmesh.topology.copy.halfEdgeEndVertex(halfEdge);
            boolean claimedA = tmesh.topology.ownerArcByCopyVertex[endA] != EmbeddedMeshTopology.UNCLAIMED
                    || tmesh.topology.ownerNodeByCopyVertex[endA] != EmbeddedMeshTopology.UNCLAIMED;
            boolean claimedB = tmesh.topology.ownerArcByCopyVertex[endB] != EmbeddedMeshTopology.UNCLAIMED
                    || tmesh.topology.ownerNodeByCopyVertex[endB] != EmbeddedMeshTopology.UNCLAIMED;
            if (claimedA && claimedB) {
                gates++;
            }
        }
        System.out.println("[start] claimedV=" + claimedVertices + " ofV="
                + tmesh.topology.copy.vertexCount() + " gates=" + gates
                + " ofE=" + tmesh.topology.copy.edgeCount());
        while (true) {
            String before = liveCounts();
            if (Boolean.getBoolean("embeddedTMesh.traceSteps")) {
                System.out.println("[contract] step " + (arcCollapseCount + patchSplitCount
                        + patchCollapseCount) + " counts=" + before + " copyV="
                        + tmesh.topology.copy.vertexCount());
            }
            try {
                if (!applyOneOperator()) {
                    tmesh.validateArcPaths();
                    int leaked = 0;
                    int onLivePath = 0;
                    int liveArcs = 0;
                    int pathTotal = 0;
                    Set<Integer> pathVertices = new HashSet<>();
                    for (EmbeddedArc arc : tmesh.arcs) {
                        if (arc.alive) {
                            liveArcs++;
                            pathTotal += arc.path.copyVertexPath.size();
                            pathVertices.addAll(arc.path.copyVertexPath);
                        }
                    }
                    for (int v = 0; v < tmesh.topology.ownerArcByCopyVertex.length; v++) {
                        int owner = tmesh.topology.ownerArcByCopyVertex[v];
                        if (owner == EmbeddedMeshTopology.UNCLAIMED) {
                            continue;
                        }
                        if (!tmesh.arcs.get(owner).alive || !pathVertices.contains(v)) {
                            leaked++;
                        } else {
                            onLivePath++;
                        }
                    }
                    System.out.println("[contract] claims | liveArcs=" + liveArcs
                            + " pathVertexTotal=" + pathTotal
                            + " avgPath=" + (liveArcs == 0 ? 0 : pathTotal / liveArcs)
                            + " claimedOnLivePath=" + onLivePath + " leaked=" + leaked);
                    System.out.println("[contract] refinement | copy V="
                            + tmesh.topology.copy.vertexCount()
                            + " E=" + tmesh.topology.copy.edgeCount()
                            + " F=" + tmesh.topology.copy.faceCount()
                            + refinementShare(collapseArc.rerouter, "collapse")
                            + refinementShare(splitPatch.rerouter, "split"));
                    System.out.println("[contract] fixed point | " + survivingZeroPatchReport());
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
     * One operator's refinement tally: edge splits broken down by the mechanism that made them,
     * alongside the route attempts and refine rounds that drove them.
     *
     * @param rerouter operator's re-router
     * @param label    operator name to prefix the tally with
     * @return the tally as a compact string
     */
    private String refinementShare(ArcRerouter rerouter, String label) {
        return FIELD_SEPARATOR + label + " splits=" + rerouter.refinedEdgeSplitCount
                + " (gate=" + rerouter.gateSplitCount
                + " blocked=" + rerouter.blockedSplitCount
                + " spoke=" + rerouter.spokeSplitCount
                + ") attempts=" + rerouter.routeAttemptCount
                + " rounds=" + rerouter.refineRoundCount
                + " growths=" + rerouter.corridorGrowthCount;
    }

    /**
     * The live node, arc and patch counts as a compact {@code V/E/F} string, for reporting which
     * operator broke the cell decomposition and by how much.
     *
     * @return the live counts
     */
    private String liveCounts() {
        return String.format("%d/%d/%d",
                tmesh.nodes.stream().filter(node -> node.alive).count(),
                tmesh.arcs.stream().filter(arc -> arc.alive).count(),
                tmesh.patches.stream().filter(patch -> patch.alive).count());
    }

    /**
     * Applies the highest-priority applicable operator, and reports whether one applied.
     *
     * @return true when an operator was applied, false when none can be
     */
    private boolean applyOneOperator() {
        int arc = collapseArc.nextCollapsibleArc();
        if (arc != EmbeddedTMesh.NONE) {
            lastStep = "zero-arc collapse of arc " + arc + describeArcShape(arc);
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
     * The zero-patches still alive when no operator applies any more.
     *
     * <p>There should be none: a survivor is a patch no operator claimed, and it stays in the final
     * layout as a cell with no area.
     *
     * <p>See also: LCBK19 Proposition 6.1, Corollary 6.3
     *
     * @return a summary naming the survivors and how many non-zero arcs each carries
     */
    private String survivingZeroPatchReport() {
        List<String> survivors = new ArrayList<>();
        for (EmbeddedPatch patch : tmesh.patches) {
            if (patch.alive && tmesh.isZeroPatch(patch.patchId)) {
                survivors.add("P" + patch.patchId + "(nonZero="
                        + tmesh.nonZeroArcCount(patch.patchId) + " arcs="
                        + describePatchSides(patch.patchId));
            }
        }
        return "zeroPatchesLeft=" + survivors.size() + " "
                + survivors.subList(0, Math.min(survivors.size(), SURVIVOR_REPORT_CAP));
    }

    /**
     * The shape of an arc about to be collapsed, for the report when the collapse breaks the cell
     * decomposition. An arc that is already a loop has no node to merge, so it must pay for itself
     * with a face instead.
     *
     * @param arcId arc about to be collapsed
     * @return a bracketed description, or the empty string for an ordinary two-node arc
     */
    private String describeArcShape(int arcId) {
        EmbeddedArc arc = tmesh.arcs.get(arcId);
        if (!arc.isLoop()) {
            return "";
        }
        return " [already a loop at node " + arc.startNodeId + " leftPatch=" + arc.leftPatchId
                + " rightPatch=" + arc.rightPatchId
                + (arc.leftPatchId == arc.rightPatchId ? " SAME-PATCH-BOTH-SIDES" : "")
                + " leftSides=" + describePatchSides(arc.leftPatchId)
                + " rightSides=" + describePatchSides(arc.rightPatchId) + CLOSE_GROUP;
    }

    /**
     * The per-side arc counts of a patch, so the report shows whether retiring it would have been
     * the emptied-patch case the collapse already handles.
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
