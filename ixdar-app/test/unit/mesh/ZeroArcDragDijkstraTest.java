package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.nodes.primitives.GridMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.ArcRerouter;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * LCBK19 Section 6.1's "Operator Implementation" on hand-placed grid arrangements:
 * a drag stays in its two patches, touches no other arc, and splits an edge to
 * open a walled region.
 */
class ZeroArcDragDijkstraTest {

    private static final int COLUMNS = 7;
    private static final int ROWS = 5;
    private static final int MIDDLE_ROW = 2;
    private static final int TOP_ROW = 4;
    private static final int MOVING_COLUMN = 2;
    private static final int SURVIVING_COLUMN = 4;
    private static final int WALL_COLUMN = 3;
    private static final int FAR_COLUMN = 5;

    /**
     * A zero arc along the middle row with an arc north and one south at each end:
     * each dragged arc stays out of the cell on the zero arc's other side.
     */
    @Test
    void eachDraggedArcStaysInsideTheTwoPatchesItSeparates() {
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(
                GridMeshNode.triangulated(COLUMNS, ROWS));
        ArcNetwork tmesh = new ArcNetwork(topology);
        int moving = tmesh.addNode(ArcNetwork.NONE, vertex(topology, MOVING_COLUMN, MIDDLE_ROW),
                false, false);
        int surviving = tmesh.addNode(ArcNetwork.NONE,
                vertex(topology, SURVIVING_COLUMN, MIDDLE_ROW), false, false);
        int zeroArc = tmesh.addArc(ArcNetwork.NONE, moving, surviving, 0, false,
                row(topology, MIDDLE_ROW, MOVING_COLUMN, SURVIVING_COLUMN));
        addColumnArc(tmesh, topology, surviving, SURVIVING_COLUMN, TOP_ROW);
        addColumnArc(tmesh, topology, surviving, SURVIVING_COLUMN, 0);
        int northArc = addColumnArc(tmesh, topology, moving, MOVING_COLUMN, TOP_ROW);
        int southArc = addColumnArc(tmesh, topology, moving, MOVING_COLUMN, 0);

        ZeroArcCollapseOperator collapse = new NetworkContraction(tmesh).collapseArc;
        collapse.beginCollapse(zeroArc);
        while (collapse.dragNextArc()) {
            continue;
        }

        assertEquals(0, collapse.blockedDragCount, "no drag blocks");
        int survivorVertex = vertex(topology, SURVIVING_COLUMN, MIDDLE_ROW);
        for (int arcId : new int[] { northArc, southArc }) {
            List<Integer> path = tmesh.arcs.get(arcId).path.copyVertexPath;
            assertEquals(survivorVertex, path.get(0), "arc " + arcId + " now leaves the survivor");
            float side = arcId == northArc ? 1f : -1f;
            for (int copyVertex : path) {
                assertFalse(eastOfMovingNode(topology, copyVertex)
                        && side * northOfMiddleRow(topology, copyVertex) < 0f,
                        "arc " + arcId + " crossed into the cell on the zero arc's other side at"
                                + " vertex " + copyVertex + ": " + path);
            }
        }
    }

    /**
     * A route along the middle row would pass a vertex another arc holds; the
     * search goes round the end of that arc instead and uses none of its vertices.
     */
    @Test
    void aRouteDetoursRatherThanTouchAnotherArc() {
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(
                GridMeshNode.triangulated(COLUMNS, ROWS));
        ArcNetwork tmesh = new ArcNetwork(topology);
        int bottom = tmesh.addNode(ArcNetwork.NONE, vertex(topology, WALL_COLUMN, 0), false, false);
        int wallTop = TOP_ROW - 1;
        int top = tmesh.addNode(ArcNetwork.NONE, vertex(topology, WALL_COLUMN, wallTop), false,
                false);
        List<Integer> wall = new ArrayList<>();
        for (int wallRow = 0; wallRow <= wallTop; wallRow++) {
            wall.add(vertex(topology, WALL_COLUMN, wallRow));
        }
        tmesh.addArc(ArcNetwork.NONE, bottom, top, 1, false, wall);
        int start = vertex(topology, 1, MIDDLE_ROW);
        int target = vertex(topology, FAR_COLUMN, MIDDLE_ROW);

        List<Integer> routed = new ArrayList<>();
        ArcRerouter rerouter = new ArcRerouter(topology);
        assertTrue(rerouter.tryRoute(EmbeddedMeshTopology.UNCLAIMED, routed, start, target,
                rerouter.freshCorridor(), EmbeddedMeshTopology.UNCLAIMED), "a route exists");

        assertEquals(0, rerouter.refinedEdgeSplitCount, "going round the wall needs no split");
        for (int wallVertex : wall) {
            assertFalse(routed.contains(wallVertex),
                    "the route touches the wall at " + wallVertex + ": " + routed);
        }
        assertTrue(routed.contains(vertex(topology, WALL_COLUMN, TOP_ROW)),
                "the route rounds the wall's free end: " + routed);
    }

    /**
     * Every vertex of one grid column belongs to a node, so no vertex path crosses
     * it; the search splits an edge between two of them and routes through the
     * midpoint.
     */
    @Test
    void aWalledRegionIsOpenedByAnEdgeSplit() {
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(
                GridMeshNode.triangulated(COLUMNS, ROWS));
        ArcNetwork tmesh = new ArcNetwork(topology);
        for (int wallRow = 0; wallRow < ROWS; wallRow++) {
            tmesh.addNode(ArcNetwork.NONE, vertex(topology, WALL_COLUMN, wallRow), false, false);
        }
        int vertexCountBefore = topology.copy.vertexCount();
        List<Integer> routed = new ArrayList<>();
        ArcRerouter rerouter = new ArcRerouter(topology);

        assertTrue(rerouter.tryRoute(EmbeddedMeshTopology.UNCLAIMED, routed,
                vertex(topology, 1, MIDDLE_ROW), vertex(topology, FAR_COLUMN, MIDDLE_ROW),
                rerouter.freshCorridor(), EmbeddedMeshTopology.UNCLAIMED),
                "the split opens a path");
        assertTrue(rerouter.refinedEdgeSplitCount > 0, "the wall needed a split");
        assertTrue(routed.stream().anyMatch(copyVertex -> copyVertex >= vertexCountBefore),
                "the route runs through a minted midpoint: " + routed);
        for (int index = 1; index < routed.size(); index++) {
            assertTrue(topology.copy.edgeBetween(routed.get(index - 1), routed.get(index))
                    != EmbeddedMeshTopology.UNCLAIMED, "consecutive route vertices share an edge");
        }
    }

    /**
     * Adds an arc from a node straight along its grid column to a new node.
     *
     * @param tmesh     network receiving the arc
     * @param topology  working copy over the grid
     * @param fromNode  node the arc leaves, on the middle row
     * @param column    grid column the arc runs along
     * @param toRow     row of the new far node
     * @return the arc id
     */
    private int addColumnArc(ArcNetwork tmesh, EmbeddedMeshTopology topology, int fromNode,
            int column, int toRow) {
        int farNode = tmesh.addNode(ArcNetwork.NONE, vertex(topology, column, toRow), false, false);
        List<Integer> path = new ArrayList<>();
        int step = toRow > MIDDLE_ROW ? 1 : -1;
        for (int pathRow = MIDDLE_ROW; pathRow != toRow + step; pathRow += step) {
            path.add(vertex(topology, column, pathRow));
        }
        return tmesh.addArc(ArcNetwork.NONE, fromNode, farNode, 1, false, path);
    }

    /**
     * The copy vertices of one grid row between two columns, inclusive.
     *
     * @param topology   working copy over the grid
     * @param gridRow    the row
     * @param fromColumn first column
     * @param toColumn   last column
     * @return the vertices in column order
     */
    private List<Integer> row(EmbeddedMeshTopology topology, int gridRow, int fromColumn,
            int toColumn) {
        List<Integer> vertices = new ArrayList<>();
        for (int column = fromColumn; column <= toColumn; column++) {
            vertices.add(vertex(topology, column, gridRow));
        }
        return vertices;
    }

    /**
     * How far a copy vertex lies toward the top row from the middle row.
     *
     * @param topology   working copy over the grid
     * @param copyVertex vertex to place
     * @return positive above the middle row, negative below it, zero on it
     */
    private float northOfMiddleRow(EmbeddedMeshTopology topology, int copyVertex) {
        return (position(topology, copyVertex).z
                - position(topology, vertex(topology, 0, MIDDLE_ROW)).z)
                * Math.signum(position(topology, vertex(topology, 0, TOP_ROW)).z
                        - position(topology, vertex(topology, 0, MIDDLE_ROW)).z);
    }

    /**
     * Whether a copy vertex lies strictly past the moving node toward the survivor.
     *
     * @param topology   working copy over the grid
     * @param copyVertex vertex to place
     * @return true when it is on the survivor's side of the moving node's column
     */
    private boolean eastOfMovingNode(EmbeddedMeshTopology topology, int copyVertex) {
        float movingX = position(topology, vertex(topology, MOVING_COLUMN, 0)).x;
        float survivingX = position(topology, vertex(topology, SURVIVING_COLUMN, 0)).x;
        return (position(topology, copyVertex).x - movingX) * (survivingX - movingX) > 0f;
    }

    /**
     * The position of a copy vertex.
     *
     * @param topology   working copy over the grid
     * @param copyVertex vertex to read
     * @return a fresh vector holding its position
     */
    private Vector3f position(EmbeddedMeshTopology topology, int copyVertex) {
        return topology.copy.vertexPosition(copyVertex, new Vector3f());
    }

    /**
     * The copy vertex at a grid position.
     *
     * @param topology working copy over the grid
     * @param column   grid column
     * @param gridRow  grid row
     * @return the copy vertex there
     */
    private int vertex(EmbeddedMeshTopology topology, int column, int gridRow) {
        return topology.copyVertexForSourceVertexId(GridMeshNode.vertexId(ROWS, column, gridRow));
    }
}
