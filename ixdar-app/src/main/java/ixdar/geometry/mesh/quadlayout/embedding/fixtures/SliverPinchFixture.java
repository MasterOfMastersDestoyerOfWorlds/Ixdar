package ixdar.geometry.mesh.quadlayout.embedding.fixtures;

import java.util.List;

import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.DiskMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * Botijo's arc-collapse-3 shape: a zero triangle whose collapse drags an arc toward a survivor
 * whose direct edge is claimed, with an unadmitted sliver sector offering the only free route.
 * Triangle patches fail the rectangle rule — drive the collapse operator directly. See also:
 * LCKB19 Section 6.1
 */
public final class SliverPinchFixture implements LayoutFixture {

    /** Angular subdivisions of each ring of the disk. */
    private static final int ANGULAR_STEPS = 12;

    /** Concentric rings of the disk; the outermost keeps every arc interior. */
    private static final int RING_COUNT = 4;

    public HalfEdgeMesh disk;
    public EmbeddedMeshTopology topology;
    public ArcNetwork tmesh;

    /** Critical node on the central vertex; the collapse merges the moved node onto it. */
    public int survivorNodeId;

    /** Node one edge from the survivor; that direct edge is claimed by {@link #directArcId}. */
    public int pinchNodeId;

    /** Node the zero channel hangs from, two hops out; it moves onto the survivor. */
    public int movedNodeId;

    /** The collapsing zero arc, from the moved node to the survivor around the pinch. */
    public int channelArcId;

    /** Zero arc from the pinch node to the moved node; its drag must thread the pinch. */
    public int pinchArcId;

    /** Zero arc claiming the direct edge from the survivor to the pinch node. */
    public int directArcId;

    /** Sliver sides: pinch node outward, across, and back to the survivor. */
    public int sliverOutArcId;
    public int sliverFarArcId;
    public int sliverBackArcId;

    /** Far-side arcs closing the moved node's fan: link outward and flank back in. */
    public int farLinkArcId;
    public int farFlankArcId;

    /** Fan tail from the moved node away from the pinch, and the arcs closing that side. */
    public int fanTailArcId;
    public int rimArcId;
    public int rimLinkArcId;
    public int tailLinkArcId;

    /** The zero triangle bounded by channel, pinch and direct arcs. */
    public int zeroTrianglePatchId;

    /** The sliver sector beside the direct arc — not a flank of any fan arc. */
    public int sliverPatchId;

    /** The pinch arc's far flank, away from the survivor. */
    public int farPatchId;

    /** The channel's other flank. */
    public int channelFlankPatchId;

    /** The wedge between fan tail and far flank. */
    public int tailPatchId;

    /** Everything else, so every face carries a label. */
    public int outerPatchId;

    /**
     * Builds the disk, the working copy over it, and the hand-authored T-mesh.
     */
    public SliverPinchFixture() {
        build();
    }

    @Override
    public String displayName() {
        return "Sliver pinch";
    }

    @Override
    public ArcNetwork build() {
        DiskMeshNode node = new DiskMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(DiskMeshNode.RINGS.name, RING_COUNT);
        context.setInput(DiskMeshNode.ANGULAR_SEGMENTS.name, ANGULAR_STEPS);
        context.setInput(DiskMeshNode.RADIUS.name, (float) RING_COUNT);
        context.setInput(DiskMeshNode.TRIANGULATE.name, true);
        node.evaluate(context);
        this.disk = context.getOutput(DiskMeshNode.MESH.name, HalfEdgeMesh.class);
        this.topology = new EmbeddedMeshTopology(disk);
        this.tmesh = new ArcNetwork(topology);
        layOutTMesh();
        return tmesh;
    }

    /**
     * Lays out the pinch: survivor at the center, pinch node at ring-1 hour 0, moved node at
     * ring-2 hour 0, the channel curling around hour 11, the sliver filling hours 0-2, and the
     * fan's far side closed over hours 0-1 outward and 10-11 rimward.
     */
    private void layOutTMesh() {
        survivorNodeId = tmesh.addNode(ArcNetwork.NONE, copyVertex(0, 0), true, false);
        pinchNodeId = tmesh.addNode(ArcNetwork.NONE, copyVertex(1, 0), false, false);
        movedNodeId = tmesh.addNode(ArcNetwork.NONE, copyVertex(2, 0), false, false);
        int farNodeId = tmesh.addNode(ArcNetwork.NONE, copyVertex(2, 1), false, false);
        int sliverFarNodeId = tmesh.addNode(ArcNetwork.NONE, copyVertex(2, 2), false, false);
        int farLinkNodeId = tmesh.addNode(ArcNetwork.NONE, copyVertex(3, 1), false, false);
        int tailNodeId = tmesh.addNode(ArcNetwork.NONE, copyVertex(3, 0), false, false);
        int rimNodeId = tmesh.addNode(ArcNetwork.NONE, copyVertex(2, 10), false, false);

        channelArcId = tmesh.addArc(ArcNetwork.NONE, movedNodeId, survivorNodeId, 0, false,
                List.of(copyVertex(2, 0), copyVertex(1, 11), copyVertex(0, 0)));
        pinchArcId = tmesh.addArc(ArcNetwork.NONE, pinchNodeId, movedNodeId, 0, false,
                List.of(copyVertex(1, 0), copyVertex(2, 0)));
        directArcId = tmesh.addArc(ArcNetwork.NONE, survivorNodeId, pinchNodeId, 0, false,
                List.of(copyVertex(0, 0), copyVertex(1, 0)));
        sliverOutArcId = tmesh.addArc(ArcNetwork.NONE, pinchNodeId, farNodeId, 1, false,
                List.of(copyVertex(1, 0), copyVertex(2, 1)));
        sliverFarArcId = tmesh.addArc(ArcNetwork.NONE, farNodeId, sliverFarNodeId, 0, false,
                List.of(copyVertex(2, 1), copyVertex(2, 2)));
        sliverBackArcId = tmesh.addArc(ArcNetwork.NONE, sliverFarNodeId, survivorNodeId, 1,
                false, List.of(copyVertex(2, 2), copyVertex(1, 2), copyVertex(0, 0)));
        farLinkArcId = tmesh.addArc(ArcNetwork.NONE, farNodeId, farLinkNodeId, 0, false,
                List.of(copyVertex(2, 1), copyVertex(3, 1)));
        farFlankArcId = tmesh.addArc(ArcNetwork.NONE, farLinkNodeId, movedNodeId, 1, false,
                List.of(copyVertex(3, 1), copyVertex(2, 0)));
        fanTailArcId = tmesh.addArc(ArcNetwork.NONE, movedNodeId, tailNodeId, 1, false,
                List.of(copyVertex(2, 0), copyVertex(3, 0)));
        rimArcId = tmesh.addArc(ArcNetwork.NONE, survivorNodeId, rimNodeId, 1, false,
                List.of(copyVertex(0, 0), copyVertex(1, 10), copyVertex(2, 10)));
        rimLinkArcId = tmesh.addArc(ArcNetwork.NONE, tailNodeId, rimNodeId, 1, false,
                List.of(copyVertex(3, 0), copyVertex(2, 11), copyVertex(2, 10)));
        tailLinkArcId = tmesh.addArc(ArcNetwork.NONE, tailNodeId, farLinkNodeId, 1, false,
                List.of(copyVertex(3, 0), copyVertex(3, 1)));

        zeroTrianglePatchId = tmesh.addPatch(ArcNetwork.NONE, List.of(
                List.of(channelArcId),
                List.of(directArcId, pinchArcId),
                List.of(),
                List.of()), movedNodeId);
        sliverPatchId = tmesh.addPatch(ArcNetwork.NONE, List.of(
                List.of(sliverBackArcId),
                List.of(sliverFarArcId),
                List.of(sliverOutArcId),
                List.of(directArcId)), survivorNodeId);
        farPatchId = tmesh.addPatch(ArcNetwork.NONE, List.of(
                List.of(pinchArcId),
                List.of(sliverOutArcId),
                List.of(farLinkArcId),
                List.of(farFlankArcId)), movedNodeId);
        channelFlankPatchId = tmesh.addPatch(ArcNetwork.NONE, List.of(
                List.of(channelArcId),
                List.of(fanTailArcId),
                List.of(rimLinkArcId),
                List.of(rimArcId)), survivorNodeId);
        tailPatchId = tmesh.addPatch(ArcNetwork.NONE, List.of(
                List.of(farFlankArcId),
                List.of(tailLinkArcId),
                List.of(fanTailArcId),
                List.of()), movedNodeId);
        outerPatchId = tmesh.addPatch(ArcNetwork.NONE, List.of(
                List.of(rimArcId),
                List.of(rimLinkArcId),
                List.of(tailLinkArcId, farLinkArcId),
                List.of(sliverFarArcId, sliverBackArcId)), survivorNodeId);
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
