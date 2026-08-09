package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import ixdar.geometry.mesh.data.representation.ActiveIdSet;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.embedding.records.PatchCorridor;

/**
 * Extends every surviving T-junction across its patch to the matching offset on the opposite
 * side, leaving a conforming layout.
 *
 * <p>See also: LCK21a Section 6
 */
public final class TJunctionExtension {

    public final EmbeddedTMesh tmesh;
    public final ArcRerouter rerouter;
    public final PatchCorridor corridor;

    /** Arcs inserted across a patch, one per extension. */
    public int extensionCount;

    /** Extensions that had to split the opposite arc because no node sat at the matching offset. */
    public int oppositeSplitCount;

    /**
     * Stores the T-mesh and builds the router over its working copy.
     *
     * @param tmesh embedded T-mesh whose T-junctions are extended
     */
    public TJunctionExtension(EmbeddedTMesh tmesh) {
        this.tmesh = tmesh;
        this.rerouter = new ArcRerouter(tmesh.topology);
        this.corridor = new PatchCorridor(tmesh);
    }

    /**
     * Extends T-junctions until none is left.
     *
     * <p>Splitting preserves the layout's quantized area and every patch covers at least one
     * quad, so that area bounds how many extensions can ever run.
     *
     * @throws IllegalStateException when more extensions run than the layout's quantized area allows
     * @return the number of arcs inserted
     */
    public int extendAll() {
        long budget = quantizedArea() - livePatchCount();
        Deque<Integer> pending = new ArrayDeque<>();
        for (EmbeddedPatch patch : tmesh.patches) {
            if (patch.alive) {
                pending.add(patch.patchId);
            }
        }
        while (!pending.isEmpty()) {
            int patchId = pending.poll();
            if (!tmesh.patches.get(patchId).alive) {
                continue;
            }
            int[] tjunction = findTJunction(patchId);
            if (tjunction == null) {
                continue;
            }
            if (budget-- <= 0) {
                throw new IllegalStateException("T-junction extension ran past the layout's"
                        + " quantized area of " + quantizedArea() + " patches, still finding one on"
                        + " side " + tjunction[0] + " of patch " + patchId + "; an extension is"
                        + " re-creating the T-junction it consumed");
            }
            for (int touched : extendOne(patchId, tjunction[0], tjunction[1], tjunction[2])) {
                pending.add(touched);
            }
        }
        return extensionCount;
    }

    /**
     * The first T-junction of a patch: an interior node of one of its sides that carries a third
     * arc, so a separatrix runs into the side and must be continued across the patch. An interior
     * node of degree two only subdivides the side and is left alone.
     *
     * @param patchId patch to search
     * @return {@code {side, nodeId, offset}}, or {@code null} when the patch has none
     */
    private int[] findTJunction(int patchId) {
        EmbeddedPatch patch = tmesh.patches.get(patchId);
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            List<Integer> sideArcs = patch.sideArcIds.get(side);
            List<Integer> sideNodes = patch.sideNodeIds.get(side);
            int offset = 0;
            for (int index = 0; index < sideArcs.size() - 1; index++) {
                offset += tmesh.arcs.get(sideArcs.get(index)).quantizedLength;
                int interiorNodeId = sideNodes.get(index + 1);
                if (tmesh.degree(interiorNodeId) > 2) {
                    return new int[] { side, interiorNodeId, offset };
                }
            }
        }
        return null;
    }

    /**
     * Extends one T-junction across its patch, inserting an arc quantized to the patch's
     * orthogonal extent and cutting the patch in two.
     *
     * <p>Splitting the opposite arc mints a T-junction beyond it, so successive extensions walk a
     * quad strip until one lands on an existing node.
     *
     * @param patchId      patch to cut
     * @param side         side the T-junction sits on
     * @param tjunctionNodeId node to extend from
     * @param offset       the node's quantized offset from the side's start
     * @throws IllegalStateException when the arc cannot be routed inside the patch
     * @return the patches to re-examine: the two halves and the neighbours of the far node
     */
    private int[] extendOne(int patchId, int side, int tjunctionNodeId, int offset) {
        int crossingLength = tmesh.sideQuantizedLength(patchId, (side + 1) % EmbeddedPatch.SIDES);
        if (crossingLength == 0) {
            throw new IllegalStateException("patch " + patchId + " still has zero extent across"
                    + " side " + side + "; the contraction should have collapsed it");
        }
        int oppositeSide = (side + 2) % EmbeddedPatch.SIDES;
        int oppositeOffset = tmesh.oppositeOffset(patchId, side, offset);
        int nodesBefore = tmesh.nodes.size();
        int oppositeNodeId = tmesh.nodeAtOffsetOrSplit(patchId, oppositeSide, oppositeOffset);
        if (tmesh.nodes.size() > nodesBefore) {
            oppositeSplitCount++;
        }

        ActiveIdSet searchCorridor = corridor.corridorVertices(patchId, rerouter);
        int startVertex = tmesh.nodes.get(tjunctionNodeId).copyVertex;
        int endVertex = tmesh.nodes.get(oppositeNodeId).copyVertex;
        List<Integer> routed = new ArrayList<>();
        if (!rerouter.tryRoute(EmbeddedTMesh.NONE, routed, startVertex, endVertex, searchCorridor,
                EmbeddedMeshTopology.UNCLAIMED)) {
            throw new IllegalStateException("could not route a T-junction extension across patch "
                    + patchId + " from node " + tjunctionNodeId + " to node " + oppositeNodeId);
        }
        int newArc = tmesh.addArc(EmbeddedTMesh.NONE, tjunctionNodeId, oppositeNodeId,
                crossingLength, false, routed);
        int[] halves = tmesh.splitPatchByArc(patchId, newArc);
        extensionCount++;

        List<Integer> touched = new ArrayList<>();
        touched.add(halves[0]);
        touched.add(halves[1]);
        for (int arcId : tmesh.arcEndsByNode.get(oppositeNodeId)) {
            EmbeddedArc arc = tmesh.arcs.get(arcId);
            addLivePatch(touched, arc.leftPatchId);
            addLivePatch(touched, arc.rightPatchId);
        }
        int[] result = new int[touched.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = touched.get(index);
        }
        return result;
    }

    /**
     * Appends a patch id to a work list when it names a live patch.
     *
     * @param touched work list to extend
     * @param patchId candidate patch id, possibly {@link EmbeddedTMesh#NONE}
     */
    private void addLivePatch(List<Integer> touched, int patchId) {
        if (patchId != EmbeddedTMesh.NONE && tmesh.patches.get(patchId).alive) {
            touched.add(patchId);
        }
    }

    /**
     * The layout's total quantized area, the sum over live patches of the product of their two
     * side lengths. Splitting a patch preserves it.
     *
     * @return the total number of quads the quantization prescribes
     */
    private long quantizedArea() {
        long area = 0;
        for (EmbeddedPatch patch : tmesh.patches) {
            if (patch.alive) {
                area += (long) tmesh.sideQuantizedLength(patch.patchId, 0)
                        * tmesh.sideQuantizedLength(patch.patchId, 1);
            }
        }
        return area;
    }

    /**
     * The number of patches still in the T-mesh.
     *
     * @return the live patch count
     */
    private long livePatchCount() {
        long live = 0;
        for (EmbeddedPatch patch : tmesh.patches) {
            if (patch.alive) {
                live++;
            }
        }
        return live;
    }
}
