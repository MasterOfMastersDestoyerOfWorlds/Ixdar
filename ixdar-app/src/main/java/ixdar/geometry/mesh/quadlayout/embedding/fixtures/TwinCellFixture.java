package ixdar.geometry.mesh.quadlayout.embedding.fixtures;

import java.util.List;

import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.DiskMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * Botijo's operator-830 shape: an all-zero web where a fan arc's far node is already the
 * survivor, so the collapse degenerates it to a point and its twin flanks must merge.
 * See also: LCKB19 Section 6.1
 */
public final class TwinCellFixture implements LayoutFixture {

    /** Angular subdivisions of each ring of the disk. */
    private static final int ANGULAR_STEPS = 12;

    /** Concentric rings of the disk. */
    private static final int RING_COUNT = 4;

    public HalfEdgeMesh disk;
    public EmbeddedMeshTopology topology;
    public ArcNetwork tmesh;

    /** Critical node on the central vertex; the collapse merges the moved node onto it. */
    public int survivorNodeId;

    /** Node the zero channel hangs from, two hops out at 12 o'clock; it moves onto the survivor. */
    public int movedNodeId;

    /** The island's node, one spoke out of the survivor inside the west cell. */
    public int islandNodeId;

    /** The east fan arc's far node, one ring-2 hop east of the moved node. */
    public int eastFanNodeId;

    /** The outer east fan arc's far node, one diagonal hop east-out of the moved node. */
    public int outerFanNodeId;

    /** The collapsing zero arc from the moved node straight down to the survivor. */
    public int channelArcId;

    /** The wall between the twin cells: a fan arc whose far node is already the survivor. */
    public int parallelArcId;

    /** The island bigon's inner arc, the direct spoke from the survivor to the island node. */
    public int inArcId;

    /** The island bigon's outer arc, returning from the island node to the survivor. */
    public int tornArcId;

    /** First east fan arc, keeping the parallel wall off the absorbing slot. */
    public int eastFanArcId;

    /** Arc sealing the inner east patch from the east fan node to the survivor. */
    public int eastSealArcId;

    /** Second east fan arc; last in oscillating order, dragged with moved-vertex transit. */
    public int outerFanArcId;

    /** Arc sealing the outer east patch from the outer fan node to the survivor. */
    public int outerSealArcId;

    /** The wall's west flank, holding the island; it merges with the south cell on pointing. */
    public int westCellPatchId;

    /** The one-face island bigon between the in arc and the torn arc. */
    public int islandPatchId;

    /** The channel's east flank between channel, east fan and east seal. */
    public int eastInnerPatchId;

    /** The patch between the two east fan arcs and their seals. */
    public int eastOuterPatchId;

    /** The wall's south flank, the west cell's twin across the pointed wall. */
    public int southCellPatchId;

    /**
     * Builds the disk, the working copy over it, and the hand-authored T-mesh.
     */
    public TwinCellFixture() {
        build();
    }

    @Override
    public String displayName() {
        return "Twin cell";
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
     * Lays out the trap: the parallel wall runs from the moved node to the survivor, the twin
     * cells flank it on both sides, and the island bigon keeps a live arc claiming the west
     * cell after the wall is gone.
     */
    private void layOutTMesh() {
        survivorNodeId = tmesh.addNode(ArcNetwork.NONE, copyVertex(0, 0), true, false);
        movedNodeId = tmesh.addNode(ArcNetwork.NONE, copyVertex(2, 0), false, false);
        islandNodeId = tmesh.addNode(ArcNetwork.NONE, copyVertex(1, 10), false, false);
        eastFanNodeId = tmesh.addNode(ArcNetwork.NONE, copyVertex(2, 1), false, false);
        outerFanNodeId = tmesh.addNode(ArcNetwork.NONE, copyVertex(3, 1), false, false);

        channelArcId = tmesh.addArc(ArcNetwork.NONE, movedNodeId, survivorNodeId, 0, false,
                List.of(copyVertex(2, 0), copyVertex(1, 0), copyVertex(0, 0)));
        parallelArcId = tmesh.addArc(ArcNetwork.NONE, movedNodeId, survivorNodeId, 0, false,
                List.of(copyVertex(2, 0), copyVertex(2, 11), copyVertex(2, 10), copyVertex(2, 9),
                        copyVertex(1, 9), copyVertex(0, 0)));
        inArcId = tmesh.addArc(ArcNetwork.NONE, survivorNodeId, islandNodeId, 0, false,
                List.of(copyVertex(0, 0), copyVertex(1, 10)));
        tornArcId = tmesh.addArc(ArcNetwork.NONE, islandNodeId, survivorNodeId, 0, false,
                List.of(copyVertex(1, 10), copyVertex(1, 11), copyVertex(0, 0)));
        eastFanArcId = tmesh.addArc(ArcNetwork.NONE, movedNodeId, eastFanNodeId, 0, false,
                List.of(copyVertex(2, 0), copyVertex(2, 1)));
        eastSealArcId = tmesh.addArc(ArcNetwork.NONE, eastFanNodeId, survivorNodeId, 0,
                false, List.of(copyVertex(2, 1), copyVertex(1, 1), copyVertex(0, 0)));
        outerFanArcId = tmesh.addArc(ArcNetwork.NONE, movedNodeId, outerFanNodeId, 0, false,
                List.of(copyVertex(2, 0), copyVertex(3, 1)));
        outerSealArcId = tmesh.addArc(ArcNetwork.NONE, outerFanNodeId, survivorNodeId, 0,
                false, List.of(copyVertex(3, 1), copyVertex(3, 2), copyVertex(2, 2),
                        copyVertex(1, 2), copyVertex(0, 0)));

        westCellPatchId = tmesh.addPatch(ArcNetwork.NONE, List.of(
                List.of(parallelArcId),
                List.of(inArcId),
                List.of(tornArcId),
                List.of(channelArcId)), movedNodeId);
        islandPatchId = tmesh.addPatch(ArcNetwork.NONE, List.of(
                List.of(tornArcId),
                List.of(inArcId),
                List.of(),
                List.of()), survivorNodeId);
        eastInnerPatchId = tmesh.addPatch(ArcNetwork.NONE, List.of(
                List.of(channelArcId),
                List.of(eastSealArcId),
                List.of(eastFanArcId),
                List.of()), movedNodeId);
        eastOuterPatchId = tmesh.addPatch(ArcNetwork.NONE, List.of(
                List.of(eastFanArcId),
                List.of(eastSealArcId),
                List.of(outerSealArcId),
                List.of(outerFanArcId)), movedNodeId);
        southCellPatchId = tmesh.addPatch(ArcNetwork.NONE, List.of(
                List.of(outerFanArcId),
                List.of(outerSealArcId),
                List.of(parallelArcId),
                List.of()), movedNodeId);
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
