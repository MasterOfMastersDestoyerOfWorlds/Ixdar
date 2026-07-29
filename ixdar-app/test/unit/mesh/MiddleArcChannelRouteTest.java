package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ArcRerouter;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedMeshTopology;

/**
 * Three arcs share two end nodes; the outer two are already carved as claimed
 * lanes on adjacent grid rows, leaving no free vertex between them. Carving the
 * middle arc must still succeed: its search space is both end nodes plus every
 * vertex and edge between the bounding lanes, which refinement must open.
 *
 * <p>
 * See also: LCBK19 Section 6.1
 */
class MiddleArcChannelRouteTest {

    private static final int COLUMNS = 6;
    private static final int ROWS = 4;

    /** Grid row the lower bounding lane runs along. */
    private static final int LOWER_LANE_ROW = 1;

    /** Grid row the upper bounding lane runs along. */
    private static final int UPPER_LANE_ROW = 2;

    private static final int UPPER_ARC = 1;
    private static final int MIDDLE_ARC = 2;
    private static final int LOWER_ARC = 3;

    private static final int START_NODE = 1;
    private static final int END_NODE = 2;

    /** Node the sealing transversal lane ends at, on the restriction boundary. */
    private static final int SEAL_NODE = 3;

    /** Column of the sealed variant's end node, past the sealing lane. */
    private static final int SEALED_END_COLUMN = 3;

    /** Corners (and edges) of a triangle. */
    private static final int CORNERS = 3;

    @Test
    void middleArcRoutesThroughChannelBetweenItsBoundingLanes() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);

        int startVertex = vertex(topology, 0, LOWER_LANE_ROW);
        int endVertex = vertex(topology, COLUMNS - 1, UPPER_LANE_ROW);
        topology.ownerNodeByCopyVertex[startVertex] = START_NODE;
        topology.ownerNodeByCopyVertex[endVertex] = END_NODE;

        List<Integer> upperLane = new ArrayList<>();
        List<Integer> lowerLane = new ArrayList<>();
        upperLane.add(startVertex);
        lowerLane.add(startVertex);
        for (int column = 1; column < COLUMNS - 1; column++) {
            upperLane.add(vertex(topology, column, UPPER_LANE_ROW));
            lowerLane.add(vertex(topology, column, LOWER_LANE_ROW));
        }
        upperLane.add(endVertex);
        lowerLane.add(endVertex);
        claimLane(topology, upperLane, UPPER_ARC);
        claimLane(topology, lowerLane, LOWER_ARC);

        ArcRerouter rerouter = new ArcRerouter(topology);
        restrictToChannel(rerouter, grid);

        List<Integer> routed = new ArrayList<>();
        boolean reached = rerouter.tryRoute(MIDDLE_ARC, routed, startVertex, endVertex,
                rerouter.freshCorridor(), EmbeddedMeshTopology.UNCLAIMED);

        assertTrue(reached, "the middle arc must carve between its two bounding lanes;"
                + " the gate edges connecting them are its routing fabric");
        assertEquals(endVertex, routed.get(routed.size() - 1),
                "the routed path must end at the shared end node");
        Vector3f position = new Vector3f();
        for (int index = 1; index < routed.size() - 1; index++) {
            topology.copy.vertexPosition(routed.get(index), position);
            assertTrue(position.y >= LOWER_LANE_ROW && position.y <= UPPER_LANE_ROW,
                    "routed vertex " + routed.get(index) + " at y=" + position.y
                            + " left the channel between the bounding lanes");
        }
    }

    /**
     * A foreign lane ending at a node on the restriction boundary severs the
     * channel: neither its claimed edge nor its boundary-pinned endpoints can be
     * passed or rounded without leaving the restricted band, so the route
     * correctly reports failure instead of looping.
     */
    @Test
    void transversalLaneEndingOnRestrictionBoundarySealsTheChannel() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);

        int startVertex = vertex(topology, 0, LOWER_LANE_ROW);
        int endVertex = vertex(topology, SEALED_END_COLUMN, LOWER_LANE_ROW);
        int sealNodeVertex = vertex(topology, 2, LOWER_LANE_ROW);
        int sealLaneVertex = vertex(topology, 2, UPPER_LANE_ROW);
        topology.ownerNodeByCopyVertex[startVertex] = START_NODE;
        topology.ownerNodeByCopyVertex[endVertex] = END_NODE;
        topology.ownerNodeByCopyVertex[sealNodeVertex] = SEAL_NODE;
        topology.ownerArcByCopyVertex[sealLaneVertex] = UPPER_ARC;
        topology.claimEdgeBetween(sealNodeVertex, sealLaneVertex, UPPER_ARC);

        ArcRerouter rerouter = new ArcRerouter(topology);
        restrictToChannel(rerouter, grid);

        List<Integer> routed = new ArrayList<>();
        boolean reached = rerouter.tryRoute(MIDDLE_ARC, routed, startVertex, endVertex,
                rerouter.freshCorridor(), EmbeddedMeshTopology.UNCLAIMED);

        assertTrue(!reached, "a transversal lane from the restriction boundary across the"
                + " channel severs it; no amount of refinement may claim to route through");
    }

    /**
     * Claim a lane's edges and interior vertices for an arc, leaving the shared
     * end nodes owned by their nodes only.
     *
     * @param topology working copy holding the claims
     * @param lane     lane vertices in order, nodes at both ends
     * @param arcId    arc the lane belongs to
     */
    private void claimLane(EmbeddedMeshTopology topology, List<Integer> lane, int arcId) {
        for (int index = 1; index < lane.size(); index++) {
            topology.claimEdgeBetween(lane.get(index - 1), lane.get(index), arcId);
            if (topology.ownerNodeByCopyVertex[lane.get(index)] == EmbeddedMeshTopology.UNCLAIMED) {
                topology.ownerArcByCopyVertex[lane.get(index)] = arcId;
            }
        }
    }

    /**
     * Restrict the router to the band of faces between the two lane rows — the
     * channel the middle arc was traced through.
     *
     * @param rerouter router whose face restriction is stamped
     * @param grid     source mesh whose face centroids pick the band
     */
    private void restrictToChannel(ArcRerouter rerouter, HalfEdgeMesh grid) {
        rerouter.sourceFaceStampBySourceFace = new int[grid.faceCount()];
        rerouter.sourceFaceStamp = 1;
        Vector3f cornerPosition = new Vector3f();
        for (int activeFace = 0; activeFace < grid.faceCount(); activeFace++) {
            int faceId = grid.faceIdAt(activeFace);
            float centroidY = 0f;
            for (int corner = 0; corner < CORNERS; corner++) {
                grid.vertexPosition(grid.faceVertexAt(faceId, corner), cornerPosition);
                centroidY += cornerPosition.y;
            }
            centroidY /= CORNERS;
            if (centroidY > LOWER_LANE_ROW && centroidY < UPPER_LANE_ROW) {
                rerouter.sourceFaceStampBySourceFace[activeFace] = rerouter.sourceFaceStamp;
            }
        }
    }

    /**
     * Builds a {@link #COLUMNS}×{@link #ROWS} triangulated grid on the z = 0
     * plane, each cell split along its lower-left to upper-right diagonal.
     *
     * @return the grid as a half-edge mesh
     */
    private HalfEdgeMesh buildGrid() {
        float[] positions = new float[COLUMNS * ROWS * CORNERS];
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                int base = (row * COLUMNS + column) * CORNERS;
                positions[base] = column;
                positions[base + 1] = row;
                positions[base + 2] = 0f;
            }
        }
        int[] faces = new int[(COLUMNS - 1) * (ROWS - 1) * 2 * CORNERS];
        int cursor = 0;
        for (int row = 0; row < ROWS - 1; row++) {
            for (int column = 0; column < COLUMNS - 1; column++) {
                int lowerLeft = row * COLUMNS + column;
                int lowerRight = lowerLeft + 1;
                int upperLeft = lowerLeft + COLUMNS;
                int upperRight = upperLeft + 1;
                faces[cursor++] = lowerLeft;
                faces[cursor++] = lowerRight;
                faces[cursor++] = upperRight;
                faces[cursor++] = lowerLeft;
                faces[cursor++] = upperRight;
                faces[cursor++] = upperLeft;
            }
        }
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faces);
    }

    /**
     * The copy vertex at a grid position.
     *
     * @param topology working copy over the grid
     * @param column   grid column
     * @param row      grid row
     * @return the copy vertex there
     */
    private int vertex(EmbeddedMeshTopology topology, int column, int row) {
        return topology.copyVertexForSourceVertexId(row * COLUMNS + column);
    }
}
