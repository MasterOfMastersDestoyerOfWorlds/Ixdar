package ixdar.geometry.mesh.quadlayout.embedding.fixtures;

import java.util.List;

import ixdar.annotations.meshnode.MapNodeContext;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.DiskMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * Botijo's operator-16 shape: a fan drag whose two flanks merge into one cell when its claims
 * release, offering a free-spoke arrival in the far flank's wedge while the correct slot beside
 * the channel needs a minted lane. Triangle patches — drive the collapse operator directly. See
 * also: LCKB19 Section 6.1
 */
public final class MergedCellSlotFixture implements LayoutFixture {

    /** Angular subdivisions of each ring of the disk. */
    private static final int ANGULAR_STEPS = 12;

    /** Concentric rings of the disk; ring three stays free so the merged band is walkable. */
    private static final int RING_COUNT = 5;

    public HalfEdgeMesh disk;
    public EmbeddedMeshTopology topology;
    public EmbeddedTMesh tmesh;

    /** Critical node on the central vertex; the collapse merges the moved node onto it. */
    public int survivorNodeId;

    /** Node the zero channel hangs from, two hops out at 12 o'clock; it moves onto the survivor. */
    public int movedNodeId;

    /** The dragged arc's fixed node at 3 o'clock, a corner of both merging flanks. */
    public int baitFixedNodeId;

    /** The collapsing zero arc from the moved node straight down to the survivor. */
    public int channelArcId;

    /** The dragged arc whose two flanks merge; its drag must land beside the channel. */
    public int baitArcId;

    /** Arc sealing the near flank's wedge at the survivor, adjacent to the channel spoke. */
    public int nearSealArcId;

    /** Arcs walling the buffer sector between the two wedges. */
    public int bufferFarArcId;
    public int bufferSealArcId;

    /** Arc sealing the far flank's free-spoke wedge on its far side. */
    public int farSealArcId;

    /** Far flank's outer boundary along ring four, keeping the merged band walkable inside. */
    public int bandBackArcId;

    /** Second fan arc, dragged last with moved-vertex transit. */
    public int tailArcId;

    /** The near flank of the bait arc, beside the channel — a triangle with no free spoke. */
    public int nearPatchId;

    /** The buffer sector between the two wedges, deliberately outside the touched union. */
    public int bufferPatchId;

    /** The far flank of the bait arc: band plus a survivor wedge holding free spokes. */
    public int farPatchId;

    /** The channel's other flank, closing the disk with the border. */
    public int outerPatchId;

    /**
     * Builds the disk, the working copy over it, and the hand-authored T-mesh.
     */
    public MergedCellSlotFixture() {
        build();
    }

    @Override
    public String displayName() {
        return "Merged-cell slot";
    }

    @Override
    public EmbeddedTMesh build() {
        DiskMeshNode node = new DiskMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(DiskMeshNode.RINGS_2, RING_COUNT);
        context.setInput(DiskMeshNode.ANGULAR_SEGMENTS_2, ANGULAR_STEPS);
        context.setInput(DiskMeshNode.RADIUS_2, (float) RING_COUNT);
        context.setInput(DiskMeshNode.TRIANGULATE_2, true);
        node.evaluate(context);
        this.disk = context.getOutput(DiskMeshNode.MESH_2, HalfEdgeMesh.class);
        this.topology = new EmbeddedMeshTopology(disk);
        this.tmesh = new EmbeddedTMesh(topology);
        layOutTMesh();
        return tmesh;
    }

    /**
     * Lays out the bait: the near wedge beside the channel is a single fan face needing a mint,
     * the far wedge at hours 3-6 holds free spokes, and the band from the bait arc's fixed node
     * to that wedge is free once ring three is left unclaimed.
     */
    private void layOutTMesh() {
        survivorNodeId = tmesh.addNode(EmbeddedTMesh.NONE, copyVertex(0, 0), true, false);
        movedNodeId = tmesh.addNode(EmbeddedTMesh.NONE, copyVertex(2, 0), false, false);
        baitFixedNodeId = tmesh.addNode(EmbeddedTMesh.NONE, copyVertex(2, 3), false, false);
        int bufferFarNodeId = tmesh.addNode(EmbeddedTMesh.NONE, copyVertex(2, 4), false, false);
        int farSealNodeId = tmesh.addNode(EmbeddedTMesh.NONE, copyVertex(2, 6), false, false);
        int tailNodeId = tmesh.addNode(EmbeddedTMesh.NONE, copyVertex(4, 0), false, false);

        channelArcId = tmesh.addArc(EmbeddedTMesh.NONE, movedNodeId, survivorNodeId, 0, false,
                List.of(copyVertex(2, 0), copyVertex(1, 0), copyVertex(0, 0)));
        baitArcId = tmesh.addArc(EmbeddedTMesh.NONE, movedNodeId, baitFixedNodeId, 1, false,
                List.of(copyVertex(2, 0), copyVertex(2, 1), copyVertex(2, 2), copyVertex(2, 3)));
        nearSealArcId = tmesh.addArc(EmbeddedTMesh.NONE, baitFixedNodeId, survivorNodeId, 1,
                false, List.of(copyVertex(2, 3), copyVertex(1, 2), copyVertex(1, 1),
                        copyVertex(0, 0)));
        bufferFarArcId = tmesh.addArc(EmbeddedTMesh.NONE, baitFixedNodeId, bufferFarNodeId, 1,
                false, List.of(copyVertex(2, 3), copyVertex(2, 4)));
        bufferSealArcId = tmesh.addArc(EmbeddedTMesh.NONE, bufferFarNodeId, survivorNodeId, 1,
                false, List.of(copyVertex(2, 4), copyVertex(1, 3), copyVertex(0, 0)));
        farSealArcId = tmesh.addArc(EmbeddedTMesh.NONE, farSealNodeId, survivorNodeId, 1, false,
                List.of(copyVertex(2, 6), copyVertex(1, 6), copyVertex(0, 0)));
        bandBackArcId = tmesh.addArc(EmbeddedTMesh.NONE, farSealNodeId, tailNodeId, 1, false,
                List.of(copyVertex(2, 6), copyVertex(3, 6), copyVertex(4, 6), copyVertex(4, 5),
                        copyVertex(4, 4), copyVertex(4, 3), copyVertex(4, 2), copyVertex(4, 1),
                        copyVertex(4, 0)));
        tailArcId = tmesh.addArc(EmbeddedTMesh.NONE, movedNodeId, tailNodeId, 1, false,
                List.of(copyVertex(2, 0), copyVertex(3, 0), copyVertex(4, 0)));

        nearPatchId = tmesh.addPatch(EmbeddedTMesh.NONE, List.of(
                List.of(channelArcId),
                List.of(nearSealArcId),
                List.of(baitArcId),
                List.of()), movedNodeId);
        bufferPatchId = tmesh.addPatch(EmbeddedTMesh.NONE, List.of(
                List.of(bufferSealArcId),
                List.of(bufferFarArcId),
                List.of(nearSealArcId),
                List.of()), survivorNodeId);
        farPatchId = tmesh.addPatch(EmbeddedTMesh.NONE, List.of(
                List.of(baitArcId),
                List.of(bufferFarArcId, bufferSealArcId),
                List.of(farSealArcId),
                List.of(bandBackArcId, tailArcId)), movedNodeId);
        outerPatchId = tmesh.addPatch(EmbeddedTMesh.NONE, List.of(
                List.of(channelArcId),
                List.of(tailArcId),
                List.of(bandBackArcId),
                List.of(farSealArcId)), survivorNodeId);
    }

    /**
     * The working copy's vertex at a polar position of the disk.
     *
     * @param ring    ring index, zero being the central vertex
     * @param angular angular index, taken modulo the ring subdivisions
     * @return the copy vertex there
     */
    private int copyVertex(int ring, int angular) {
        return topology.copyVertexForSourceVertexId(ring == 0 ? 0
                : 1 + (ring - 1) * ANGULAR_STEPS + (angular % ANGULAR_STEPS));
    }
}
