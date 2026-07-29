package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.ActiveIdSet;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ArcRerouter;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedMeshTopology;

/**
 * A re-route must take the fewest gates, not the shortest line. Refinement is not a cost to be
 * traded against distance: LCBK19 §6.1 splits only where the claims leave no edge path at all, so a
 * detour across the whole surface still beats one avoidable split.
 *
 * <p>Two chord walls span this grid. The near wall must be crossed by splitting one of its rungs
 * wherever it is met. The far wall stops short of the last columns, leaving a free doorway, so
 * exactly one split suffices — at the price of walking out to that doorway and back. Crossing both
 * walls beside the start is far shorter and costs two splits.
 *
 * <p>A search that prices a split as a fixed detour allowance takes the near crossing, mints a
 * midpoint it never needed, and grows the working mesh for good. The splits a contraction leaves
 * behind are permanent, so a route that pays one to save a hundred edges of walking is the defect
 * this pins.
 */
class LongDetourBeatsExtraGateTest {

    private static final int COLUMNS = 60;
    private static final int ROWS = 5;

    /** Row the route starts on, below both walls. */
    private static final int START_ROW = 0;

    /** Row of the wall spanning every column: crossing it always costs one split. */
    private static final int NEAR_WALL_ROW = 1;

    /** Row of the wall that stops short, leaving a free doorway. */
    private static final int FAR_WALL_ROW = 3;

    /** Row the route ends on, above both walls. */
    private static final int TARGET_ROW = 4;

    /** First column at which the far wall's vertices are unclaimed — the free doorway. */
    private static final int DOORWAY_COLUMN = 54;

    /** The fewest splits any route from start to target can use: one, at the near wall. */
    private static final int FEWEST_GATES = 1;

    private static final int NEAR_WALL_ARC = 7;
    private static final int FAR_WALL_ARC = 8;
    private static final int ROUTED_ARC = 9;

    @Test
    void reRouteWalksToTheFreeDoorwayRatherThanSplitASecondRung() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);
        for (int column = 0; column < COLUMNS; column++) {
            topology.ownerArcByCopyVertex[vertex(topology, column, NEAR_WALL_ROW)] = NEAR_WALL_ARC;
        }
        for (int column = 0; column < DOORWAY_COLUMN; column++) {
            topology.ownerArcByCopyVertex[vertex(topology, column, FAR_WALL_ROW)] = FAR_WALL_ARC;
        }

        int originalVertexCount = COLUMNS * ROWS;
        int startVertex = vertex(topology, 0, START_ROW);
        int targetVertex = vertex(topology, 0, TARGET_ROW);

        assertEquals(COLUMNS, claimedRunLength(topology, NEAR_WALL_ROW),
                "the near wall must span every column, or the route could round its end without"
                        + " splitting anything and the test would pin nothing");
        assertEquals(EmbeddedMeshTopology.UNCLAIMED,
                topology.ownerArcByCopyVertex[vertex(topology, DOORWAY_COLUMN, FAR_WALL_ROW)],
                "the far wall must leave a free doorway, or one split would not be enough and the"
                        + " near crossing would be the honest answer");

        ActiveIdSet corridor = unclaimedVertices(topology);
        corridor.add(startVertex);
        corridor.add(targetVertex);
        List<Integer> routed = new ArrayList<>();
        ArcRerouter rerouter = new ArcRerouter(topology);
        boolean reached = rerouter.tryRoute(ROUTED_ARC, routed, startVertex, targetVertex, corridor,
                EmbeddedMeshTopology.UNCLAIMED);

        assertTrue(reached, "both walls are passable, so the route must be found");
        int mintedOnRoute = 0;
        for (int routedVertex : routed) {
            if (routedVertex >= originalVertexCount) {
                mintedOnRoute++;
            }
        }
        assertEquals(FEWEST_GATES, mintedOnRoute,
                "the near wall costs one split and the far wall has a free doorway, so one minted"
                        + " midpoint carries the whole route; standing on a second one means the"
                        + " search bought distance with a permanent refinement");
        assertEquals(FEWEST_GATES, rerouter.refinedEdgeSplitCount,
                "a split the shortest-path answer wanted is still a split the layout did not need");
    }

    /**
     * The number of consecutive claimed vertices a wall row covers from column zero.
     *
     * @param topology working copy carrying the claim arrays
     * @param row      grid row of the wall
     * @return how many leading columns of that row are claimed
     */
    private int claimedRunLength(EmbeddedMeshTopology topology, int row) {
        int claimed = 0;
        while (claimed < COLUMNS && topology.ownerArcByCopyVertex[vertex(topology, claimed, row)]
                != EmbeddedMeshTopology.UNCLAIMED) {
            claimed++;
        }
        return claimed;
    }

    /**
     * Every copy vertex owned by neither a node nor an arc — the corridor a re-route is allowed.
     *
     * @param topology working copy carrying the claim arrays
     * @return the unclaimed copy vertices
     */
    private ActiveIdSet unclaimedVertices(EmbeddedMeshTopology topology) {
        ActiveIdSet unclaimed = new ActiveIdSet(topology.ownerArcByCopyVertex.length);
        for (int vertex = 0; vertex < topology.ownerArcByCopyVertex.length; vertex++) {
            if (topology.ownerArcByCopyVertex[vertex] == EmbeddedMeshTopology.UNCLAIMED
                    && topology.ownerNodeByCopyVertex[vertex] == EmbeddedMeshTopology.UNCLAIMED) {
                unclaimed.add(vertex);
            }
        }
        return unclaimed;
    }

    /**
     * Builds a {@link #COLUMNS}×{@link #ROWS} triangulated grid on the z = 0 plane.
     *
     * @return the grid as a half-edge mesh
     */
    private HalfEdgeMesh buildGrid() {
        float[] positions = new float[COLUMNS * ROWS * 3];
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                int base = (row * COLUMNS + column) * 3;
                positions[base] = column;
                positions[base + 1] = row;
                positions[base + 2] = 0f;
            }
        }
        int[] faces = new int[(COLUMNS - 1) * (ROWS - 1) * 2 * 3];
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
