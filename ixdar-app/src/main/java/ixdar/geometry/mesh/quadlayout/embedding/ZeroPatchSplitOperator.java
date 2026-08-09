package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import ixdar.geometry.mesh.data.representation.ActiveIdSet;
import ixdar.geometry.mesh.data.representation.IntIdList;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.embedding.records.PatchCorridor;

/**
 * Operator (2), the non-simple zero-patch split: extends one T-joint across the patch as a new
 * zero-arc. A half still carrying a T-joint is split again later.
 *
 * <p>The new arc is quantized to zero but routed as an edge path inside the patch.
 *
 * <p>See also: LCBK19 Section 6.1
 */
public final class ZeroPatchSplitOperator {

    public final EmbeddedTMesh tmesh;
    public final ArcRerouter rerouter;
    public final PatchCorridor corridor;

    public int splitCount;

    /**
     * Stores the T-mesh and builds the router over its working copy.
     *
     * @param tmesh embedded T-mesh whose non-simple zero-patches are split
     */
    public ZeroPatchSplitOperator(EmbeddedTMesh tmesh) {
        this.tmesh = tmesh;
        this.rerouter = new ArcRerouter(tmesh.topology);
        this.corridor = new PatchCorridor(tmesh);
    }

    /**
     * The id of a live non-simple zero-patch, or {@link EmbeddedTMesh#NONE} when none remains
     * — the driver's "is operator (2) applicable" test.
     *
     * @return a non-simple zero-patch id, or {@link EmbeddedTMesh#NONE}
     */
    public int nextNonSimpleZeroPatch() {
        for (EmbeddedPatch patch : tmesh.patches) {
            if (patch.alive && tmesh.isZeroPatch(patch.patchId)
                    && tmesh.nonZeroArcCount(patch.patchId) > 2) {
                return patch.patchId;
            }
        }
        return EmbeddedTMesh.NONE;
    }

    /**
     * Splits one non-simple zero-patch into simple ones, extending every T-joint it carries.
     *
     * <p>LCBK19 §6.1: <em>"this operation splits a non-simple zero-patch into several simple
     * zero-patches"</em>. Only this patch's descendants are pursued; a neighbour made non-simple by
     * splitting a shared boundary arc belongs to a later application.
     *
     * @param patchId non-simple zero-patch to split
     * @throws IllegalStateException when a half has no extendable T-joint
     */
    public void split(int patchId) {
        Deque<Integer> pending = new ArrayDeque<>();
        pending.push(patchId);
        int extensionBudget = tmesh.arcs.size();
        while (!pending.isEmpty()) {
            int half = pending.pop();
            if (!tmesh.patches.get(half).alive || !tmesh.isZeroPatch(half)
                    || tmesh.nonZeroArcCount(half) <= 2) {
                continue;
            }
            if (extensionBudget-- <= 0) {
                throw new IllegalStateException("zero-patch split of patch " + patchId
                        + " exceeded " + tmesh.arcs.size() + " T-joint extensions; a half keeps"
                        + " re-qualifying (currently patch " + half + " with "
                        + tmesh.nonZeroArcCount(half) + " non-zero arcs)");
            }
            for (int descendant : extendOneTJoint(half)) {
                pending.push(descendant);
            }
        }
    }

    /**
     * Extends one T-joint across a non-simple zero-patch, inserting a zero-arc and cutting the
     * patch in two. The T-mesh gains one node, two arcs and one patch, leaving its Euler
     * characteristic unchanged.
     *
     * @param patchId non-simple zero-patch to cut
     * @throws IllegalStateException when the patch has no extendable T-joint
     * @return the ids of the two halves
     */
    private int[] extendOneTJoint(int patchId) {
        int[] tjoint = findTJoint(patchId);
        int side = tjoint[0];
        int tjointNodeId = tjoint[1];
        int offset = tjoint[2];

        int oppositeSide = (side + 2) % EmbeddedPatch.SIDES;
        int oppositeOffset = tmesh.oppositeOffset(patchId, side, offset);
        int oppositeNodeId = tmesh.nodeAtOffsetOrSplit(patchId, oppositeSide, oppositeOffset);

        ActiveIdSet searchCorridor = corridor.corridorVertices(patchId, rerouter);
        int startVertex = tmesh.nodes.get(tjointNodeId).copyVertex;
        int endVertex = tmesh.nodes.get(oppositeNodeId).copyVertex;

        List<Integer> routed = new ArrayList<>();
        if (!rerouter.tryRoute(EmbeddedTMesh.NONE, routed, startVertex, endVertex, searchCorridor,
                EmbeddedMeshTopology.UNCLAIMED)) {
            throw new IllegalStateException("could not route a zero-arc across patch " + patchId
                    + " from node " + tjointNodeId + " to node " + oppositeNodeId);
        }
        int newArc = tmesh.addArc(EmbeddedTMesh.NONE, tjointNodeId, oppositeNodeId, 0, false, routed);
        int[] halves = tmesh.splitPatchByArc(patchId, newArc);
        splitCount++;
        return halves;
    }

    /**
     * The first T-joint of a non-simple zero-patch: an interior node of one of its non-zero
     * sides, with the node's quantized offset from that side's start.
     *
     * @param patchId patch to search
     * @throws IllegalStateException when the patch has no interior node on a non-zero side
     * @return {@code {side, nodeId, offset}}
     */
    private int[] findTJoint(int patchId) {
        EmbeddedPatch patch = tmesh.patches.get(patchId);
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            if (tmesh.sideQuantizedLength(patchId, side) == 0) {
                continue;
            }
            List<Integer> sideArcs = patch.sideArcIds.get(side);
            List<Integer> sideNodes = patch.sideNodeIds.get(side);
            int offset = 0;
            for (int index = 0; index < sideArcs.size(); index++) {
                offset += tmesh.arcs.get(sideArcs.get(index)).quantizedLength;
                if (index < sideArcs.size() - 1) {
                    return new int[] { side, sideNodes.get(index + 1), offset };
                }
            }
        }
        throw new IllegalStateException("patch " + patchId + " has no extendable T-joint");
    }

    /**
     * The copy faces one patch covers.
     *
     * @param patchId patch whose faces are wanted
     * @throws IllegalStateException when no side of any boundary arc floods an interior
     * @return the copy faces it covers
     */
    public IntIdList patchFaces(int patchId) {
        return corridor.patchFaces(patchId);
    }
}
