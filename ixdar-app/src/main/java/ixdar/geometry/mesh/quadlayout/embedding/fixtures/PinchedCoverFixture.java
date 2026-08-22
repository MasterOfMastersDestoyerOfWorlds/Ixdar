package ixdar.geometry.mesh.quadlayout.embedding.fixtures;

import java.util.List;

import ixdar.annotations.meshnode.MapNodeContext;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.DiskMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * Botijo's operator-755 shape: a bait drag walled in so tightly that only a lane minted
 * across its own fixed node's edges avoids the moved vertex; the absorber then re-claims
 * the channel, so a moved-vertex transit would pinch the far flank's sliver off there.
 * See also: LCKB19 Section 6.1
 */
public final class PinchedCoverFixture implements LayoutFixture {

    /** Angular subdivisions of each ring of the disk. */
    private static final int ANGULAR_STEPS = 12;

    /** Concentric rings of the disk; ring four keeps every arc off the border. */
    private static final int RING_COUNT = 4;

    public HalfEdgeMesh disk;
    public EmbeddedMeshTopology topology;
    public EmbeddedTMesh tmesh;

    /** Critical node on the central vertex; the collapse merges the moved node onto it. */
    public int survivorNodeId;

    /** Node the zero channel hangs from, two hops out at 12 o'clock; it moves onto the survivor. */
    public int movedNodeId;

    /** The bait arc's fixed node, one diagonal hop west of the moved node on ring one. */
    public int baitFixedNodeId;

    /** The absorber's far node, west of the bait's fixed node on ring one. */
    public int absorberFarNodeId;

    /** The fan filler's far node, one diagonal hop east-out of the moved node. */
    public int fanFillerFarNodeId;

    /** The collapsing zero arc from the moved node straight down to the survivor. */
    public int channelArcId;

    /** The dragged single-hop arc whose route must ride its old hop through the moved vertex. */
    public int baitArcId;

    /** The triangle's third side, the direct spoke from the bait's fixed node to the survivor. */
    public int baseArcId;

    /** Arc sealing the far patch between the bait's fixed node and the absorber's far node. */
    public int farSealArcId;

    /** The fan's last live arc, the only drag allowed to transit the moved vertex. */
    public int absorberArcId;

    /** Third fan arc keeping the absorber last in oscillating drag order. */
    public int fanFillerArcId;

    /** Arc sealing the east outer patch from the absorber's far node round to the filler's. */
    public int fillerSealArcId;

    /** Arc sealing the east inner patch from the filler's far node to the survivor. */
    public int eastSealArcId;

    /** The bait's west flank: the two-face triangle of channel, bait and base. */
    public int trianglePatchId;

    /** The bait's far flank: two faces cornered on the fixed node, every vertex claimed. */
    public int farPatchId;

    /** The channel's east flank between channel, east seal and fan filler. */
    public int eastInnerPatchId;

    /** The absorber's east flank between filler, filler seal and absorber. */
    public int eastOuterPatchId;

    /** Everything south of the seals, closing the disk; never admitted by the collapse. */
    public int southPatchId;

    /**
     * Builds the disk, the working copy over it, and the hand-authored T-mesh.
     */
    public PinchedCoverFixture() {
        build();
    }

    @Override
    public String displayName() {
        return "Pinched cover";
    }

    @Override
    public EmbeddedTMesh build() {
        DiskMeshNode node = new DiskMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(DiskMeshNode.RINGS.name, RING_COUNT);
        context.setInput(DiskMeshNode.ANGULAR_SEGMENTS.name, ANGULAR_STEPS);
        context.setInput(DiskMeshNode.RADIUS.name, (float) RING_COUNT);
        context.setInput(DiskMeshNode.TRIANGULATE.name, true);
        node.evaluate(context);
        this.disk = context.getOutput(DiskMeshNode.MESH.name, HalfEdgeMesh.class);
        this.topology = new EmbeddedMeshTopology(disk);
        this.tmesh = new EmbeddedTMesh(topology);
        layOutTMesh();
        return tmesh;
    }

    /**
     * Lays out the trap: the triangle holds two faces, the far patch's two faces both corner
     * the bait's fixed node with every surrounding vertex claimed, and the absorber ends the
     * fan so the vacated channel is re-claimed under the bait's minted lane.
     */
    private void layOutTMesh() {
        survivorNodeId = tmesh.addNode(EmbeddedTMesh.NONE, copyVertex(0, 0), true, false);
        movedNodeId = tmesh.addNode(EmbeddedTMesh.NONE, copyVertex(2, 0), false, false);
        baitFixedNodeId = tmesh.addNode(EmbeddedTMesh.NONE, copyVertex(1, 11), false, false);
        absorberFarNodeId = tmesh.addNode(EmbeddedTMesh.NONE, copyVertex(1, 10), false, false);
        fanFillerFarNodeId = tmesh.addNode(EmbeddedTMesh.NONE, copyVertex(3, 1), false, false);

        channelArcId = tmesh.addArc(EmbeddedTMesh.NONE, movedNodeId, survivorNodeId, 0, false,
                List.of(copyVertex(2, 0), copyVertex(1, 0), copyVertex(0, 0)));
        // Authored fixed-node-first like botijo's arc 1048, so the dragged path stays stored
        // fixed-end-first and the far patch's cover reseed lands in its main chamber.
        baitArcId = tmesh.addArc(EmbeddedTMesh.NONE, baitFixedNodeId, movedNodeId, 1, false,
                List.of(copyVertex(1, 11), copyVertex(2, 0)));
        baseArcId = tmesh.addArc(EmbeddedTMesh.NONE, baitFixedNodeId, survivorNodeId, 1, false,
                List.of(copyVertex(1, 11), copyVertex(0, 0)));
        farSealArcId = tmesh.addArc(EmbeddedTMesh.NONE, baitFixedNodeId, absorberFarNodeId, 1,
                false, List.of(copyVertex(1, 11), copyVertex(1, 10)));
        absorberArcId = tmesh.addArc(EmbeddedTMesh.NONE, absorberFarNodeId, movedNodeId, 1,
                false, List.of(copyVertex(1, 10), copyVertex(2, 11), copyVertex(2, 0)));
        fanFillerArcId = tmesh.addArc(EmbeddedTMesh.NONE, movedNodeId, fanFillerFarNodeId, 1,
                false, List.of(copyVertex(2, 0), copyVertex(3, 1)));
        fillerSealArcId = tmesh.addArc(EmbeddedTMesh.NONE, absorberFarNodeId, fanFillerFarNodeId,
                1, false, List.of(copyVertex(1, 10), copyVertex(2, 10), copyVertex(3, 11),
                        copyVertex(3, 0), copyVertex(3, 1)));
        eastSealArcId = tmesh.addArc(EmbeddedTMesh.NONE, fanFillerFarNodeId, survivorNodeId, 1,
                false, List.of(copyVertex(3, 1), copyVertex(2, 1), copyVertex(1, 1),
                        copyVertex(0, 0)));

        trianglePatchId = tmesh.addPatch(EmbeddedTMesh.NONE, List.of(
                List.of(baitArcId),
                List.of(baseArcId),
                List.of(channelArcId),
                List.of()), movedNodeId);
        farPatchId = tmesh.addPatch(EmbeddedTMesh.NONE, List.of(
                List.of(baitArcId),
                List.of(absorberArcId),
                List.of(farSealArcId),
                List.of()), baitFixedNodeId);
        eastInnerPatchId = tmesh.addPatch(EmbeddedTMesh.NONE, List.of(
                List.of(channelArcId),
                List.of(eastSealArcId),
                List.of(fanFillerArcId),
                List.of()), movedNodeId);
        eastOuterPatchId = tmesh.addPatch(EmbeddedTMesh.NONE, List.of(
                List.of(fanFillerArcId),
                List.of(fillerSealArcId),
                List.of(absorberArcId),
                List.of()), movedNodeId);
        southPatchId = tmesh.addPatch(EmbeddedTMesh.NONE, List.of(
                List.of(baseArcId),
                List.of(farSealArcId),
                List.of(fillerSealArcId),
                List.of(eastSealArcId)), survivorNodeId);
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
