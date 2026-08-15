package ixdar.geometry.mesh.quadlayout.embedding.fixtures;

import java.util.ArrayList;
import java.util.List;

import ixdar.annotations.meshnode.MapNodeContext;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.DiskMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * Twelve box nodes spoked to a center whose zero spoke to the critical 12-o'clock node drags
 * an eleven-arc fan when collapsed. Triangle sectors fail the rectangle rule by construction —
 * drive the collapse operator directly, never {@code contract()} or {@code validate()}. See
 * also: LCKB19 Figure 9 e-f
 */
public final class FanCollapseFixture implements LayoutFixture {

    /** Clock positions on the box, one node each; hour zero is 12 o'clock. */
    public static final int CLOCK_POSITIONS = 12;

    /** Angular subdivisions of each ring of the disk. */
    private static final int ANGULAR_STEPS = 24;

    /** Concentric rings of the disk; the outermost keeps the box arcs interior. */
    private static final int RING_COUNT = 5;

    /** Ring the box arcs and their clock nodes sit on. */
    private static final int BOX_RING = 4;

    /** Angular steps between neighbouring clock hours. */
    private static final int STEPS_PER_HOUR = ANGULAR_STEPS / CLOCK_POSITIONS;

    public HalfEdgeMesh disk;
    public EmbeddedMeshTopology topology;
    public EmbeddedTMesh tmesh;

    /** Node on the central vertex, the moving end of the collapse. */
    public int centerNodeId;

    /** Box node per clock hour; hour zero is critical, so the center moves onto it. */
    public int[] boxNodeIdByHour = new int[0];

    /** Spoke arc per clock hour, from the center to that hour's box node. */
    public int[] spokeArcIdByHour = new int[0];

    /** Box arc per clock hour, from that hour's node to the next hour's. */
    public int[] boxArcIdByHour = new int[0];

    /** The zero-length spoke, {@code spokeArcIdByHour[0]}. */
    public int zeroSpokeArcId;

    /**
     * Builds the disk, the working copy over it, and the hand-authored T-mesh.
     */
    public FanCollapseFixture() {
        build();
    }

    @Override
    public String displayName() {
        return "Fan collapse";
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
     * Lays the nodes, arcs and patches onto the disk: twelve triangle sectors walked
     * counter-clockwise from the center, and the outer annulus as a thirteenth patch so every
     * face carries a label and no re-route can escape around the box.
     */
    private void layOutTMesh() {
        centerNodeId = tmesh.addNode(EmbeddedTMesh.NONE, copyVertex(0, 0), false, false);
        boxNodeIdByHour = new int[CLOCK_POSITIONS];
        for (int hour = 0; hour < CLOCK_POSITIONS; hour++) {
            boxNodeIdByHour[hour] = tmesh.addNode(EmbeddedTMesh.NONE,
                    copyVertex(BOX_RING, hour * STEPS_PER_HOUR), hour == 0, false);
        }
        spokeArcIdByHour = new int[CLOCK_POSITIONS];
        for (int hour = 0; hour < CLOCK_POSITIONS; hour++) {
            List<Integer> path = new ArrayList<>();
            path.add(copyVertex(0, 0));
            for (int ring = 1; ring <= BOX_RING; ring++) {
                path.add(copyVertex(ring, hour * STEPS_PER_HOUR));
            }
            spokeArcIdByHour[hour] = tmesh.addArc(EmbeddedTMesh.NONE, centerNodeId,
                    boxNodeIdByHour[hour], hour == 0 ? 0 : 1, false, path);
        }
        zeroSpokeArcId = spokeArcIdByHour[0];
        boxArcIdByHour = new int[CLOCK_POSITIONS];
        for (int hour = 0; hour < CLOCK_POSITIONS; hour++) {
            int startAngular = hour * STEPS_PER_HOUR;
            List<Integer> path = List.of(copyVertex(BOX_RING, startAngular),
                    copyVertex(BOX_RING, startAngular + 1),
                    copyVertex(BOX_RING, startAngular + STEPS_PER_HOUR));
            boxArcIdByHour[hour] = tmesh.addArc(EmbeddedTMesh.NONE, boxNodeIdByHour[hour],
                    boxNodeIdByHour[(hour + 1) % CLOCK_POSITIONS], 1, false, path);
        }
        for (int hour = 0; hour < CLOCK_POSITIONS; hour++) {
            int nextHour = (hour + 1) % CLOCK_POSITIONS;
            tmesh.addPatch(EmbeddedTMesh.NONE, List.of(
                    List.of(spokeArcIdByHour[nextHour]),
                    List.of(boxArcIdByHour[hour]),
                    List.of(spokeArcIdByHour[hour]),
                    List.of()), centerNodeId);
        }
        List<Integer> boxLoop = new ArrayList<>();
        for (int hour = 0; hour < CLOCK_POSITIONS; hour++) {
            boxLoop.add(boxArcIdByHour[hour]);
        }
        tmesh.addPatch(EmbeddedTMesh.NONE,
                List.of(boxLoop, List.of(), List.of(), List.of()), boxNodeIdByHour[0]);
    }

    /**
     * The working copy's vertex at a polar position of the disk.
     *
     * @param ring    ring index, zero being the central vertex
     * @param angular angular index, taken modulo the ring subdivisions
     * @return the copy vertex there
     */
    private int copyVertex(int ring, int angular) {
        return topology.copyVertexForSourceVertexId(
                ring == 0 ? 0 : sourceVertex(ring, angular));
    }

    /**
     * The source vertex id at a polar position, per {@link DiskMeshNode}'s documented ordering
     * guarantee: center id 0, then ring-major/angular-minor.
     *
     * @param ring    ring index, one or more
     * @param angular angular index, taken modulo the ring subdivisions
     * @return the source vertex id there
     */
    private static int sourceVertex(int ring, int angular) {
        return 1 + (ring - 1) * ANGULAR_STEPS + (angular % ANGULAR_STEPS);
    }
}
