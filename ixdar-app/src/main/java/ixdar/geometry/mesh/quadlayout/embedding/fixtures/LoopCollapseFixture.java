package ixdar.geometry.mesh.quadlayout.embedding.fixtures;

import java.util.List;

import ixdar.annotations.meshnode.MapNodeContext;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.DiskMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * Botijo's operator-756 shape: a zero loop bounding an empty one-sided cell, with live fan
 * arcs outside. The collapse must contract the loop in place — no fan arc moves or takes
 * the closed channel, and the inside cell's cover resolves into the outside flank.
 * See also: LCKB19 Section 6.1
 */
public final class LoopCollapseFixture implements LayoutFixture {

    /** Angular subdivisions of each ring of the disk. */
    private static final int ANGULAR_STEPS = 12;

    /** Concentric rings of the disk; ring four keeps every arc off the border. */
    private static final int RING_COUNT = 4;

    public HalfEdgeMesh disk;
    public EmbeddedMeshTopology topology;
    public EmbeddedTMesh tmesh;

    /** Movable node on ring two at twelve o'clock; the loop leaves and returns here. */
    public int loopNodeId;

    /** The east fan arc's far node, out on ring three. */
    public int eastFarNodeId;

    /** The west fan arc's far node, out on ring three. */
    public int westFarNodeId;

    /** The collapsing zero loop, hanging inward from the loop node round one triangle. */
    public int loopArcId;

    /** Fan arc leaving the loop node eastward to ring three. */
    public int eastArcId;

    /** Fan arc leaving the loop node westward to ring three. */
    public int westArcId;

    /** Zero arc sealing the north cell between the two far nodes along ring three. */
    public int outerSealArcId;

    /** The loop's inside flank: one triangle bounded by the loop alone. */
    public int insidePatchId;

    /** The outer rim between the fan arcs, the seal and the disk border, away from the loop. */
    public int rimPatchId;

    /** The loop's outside flank: the central cell, pinched at the loop node. */
    public int outsidePatchId;

    /**
     * Builds the disk, the working copy over it, and the hand-authored T-mesh.
     */
    public LoopCollapseFixture() {
        build();
    }

    @Override
    public String displayName() {
        return "Loop collapse";
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
     * Lays out the loop and its fan: the loop encloses exactly one triangle with no arcs
     * inside, and the outside flank keeps non-zero boundary so it absorbs, never splices.
     */
    private void layOutTMesh() {
        loopNodeId = tmesh.addNode(EmbeddedTMesh.NONE, copyVertex(2, 0), false, false);
        eastFarNodeId = tmesh.addNode(EmbeddedTMesh.NONE, copyVertex(3, 3), true, false);
        westFarNodeId = tmesh.addNode(EmbeddedTMesh.NONE, copyVertex(3, 9), true, false);

        loopArcId = tmesh.addArc(EmbeddedTMesh.NONE, loopNodeId, loopNodeId, 0, false,
                List.of(copyVertex(2, 0), copyVertex(2, 1), copyVertex(1, 0), copyVertex(2, 0)));
        eastArcId = tmesh.addArc(EmbeddedTMesh.NONE, loopNodeId, eastFarNodeId, 1, false,
                List.of(copyVertex(2, 0), copyVertex(3, 1), copyVertex(3, 2), copyVertex(3, 3)));
        westArcId = tmesh.addArc(EmbeddedTMesh.NONE, loopNodeId, westFarNodeId, 1, false,
                List.of(copyVertex(2, 0), copyVertex(3, 0), copyVertex(3, 11), copyVertex(3, 10),
                        copyVertex(3, 9)));
        outerSealArcId = tmesh.addArc(EmbeddedTMesh.NONE, eastFarNodeId, westFarNodeId, 0, false,
                List.of(copyVertex(3, 3), copyVertex(3, 4), copyVertex(3, 5), copyVertex(3, 6),
                        copyVertex(3, 7), copyVertex(3, 8), copyVertex(3, 9)));

        insidePatchId = tmesh.addPatch(EmbeddedTMesh.NONE, List.of(
                List.of(loopArcId),
                List.of(),
                List.of(),
                List.of()), loopNodeId);
        rimPatchId = tmesh.addPatch(EmbeddedTMesh.NONE, List.of(
                List.of(eastArcId),
                List.of(outerSealArcId),
                List.of(westArcId),
                List.of()), loopNodeId);
        outsidePatchId = tmesh.addPatch(EmbeddedTMesh.NONE, List.of(
                List.of(loopArcId),
                List.of(westArcId),
                List.of(outerSealArcId),
                List.of(eastArcId)), loopNodeId);
        // addPatch cannot orient a loop arc from its endpoints — both flank walks see the
        // same node — so the flanks are set by hand along the stored path direction.
        tmesh.arcs.get(loopArcId).leftPatchId = outsidePatchId;
        tmesh.arcs.get(loopArcId).rightPatchId = insidePatchId;
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
