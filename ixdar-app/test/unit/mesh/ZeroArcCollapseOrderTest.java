package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.nodes.primitives.GridMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * The zero-arc collapse order on hand-placed grid arrangements: the shortest
 * collapsible zero arc in working-copy edges goes first, measured afresh at every
 * pick.
 */
class ZeroArcCollapseOrderTest {

    private static final int COLUMNS = 10;
    private static final int ROWS = 5;
    private static final int MIDDLE_ROW = 2;
    private static final int TOP_ROW = 4;
    private static final int CRITICAL_COLUMN = 1;
    private static final int NEAR_COLUMN = 2;
    private static final int MIDDLE_COLUMN = 4;
    private static final int FAR_COLUMN = 8;
    private static final int SHORT_START_COLUMN = 6;
    private static final int LONG_END_COLUMN = 4;

    /**
     * A chain of three zero arcs growing longer away from a critical node, its far
     * arc the most crowded: every node folds onto the critical one and each hanging
     * arc is re-routed once.
     */
    @Test
    void aChainCollapsesOntoItsCriticalEndShortestFirst() {
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(
                GridMeshNode.triangulated(COLUMNS, ROWS));
        ArcNetwork tmesh = new ArcNetwork(topology);
        int criticalVertex = vertex(topology, CRITICAL_COLUMN, MIDDLE_ROW);
        int critical = tmesh.addNode(ArcNetwork.NONE, criticalVertex, true, false);
        int near = tmesh.addNode(ArcNetwork.NONE, vertex(topology, NEAR_COLUMN, MIDDLE_ROW), false,
                false);
        int middle = tmesh.addNode(ArcNetwork.NONE, vertex(topology, MIDDLE_COLUMN, MIDDLE_ROW),
                false, false);
        int far = tmesh.addNode(ArcNetwork.NONE, vertex(topology, FAR_COLUMN, MIDDLE_ROW), false,
                false);
        tmesh.addArc(ArcNetwork.NONE, critical, near, 0, false,
                row(topology, CRITICAL_COLUMN, NEAR_COLUMN));
        tmesh.addArc(ArcNetwork.NONE, near, middle, 0, false,
                row(topology, NEAR_COLUMN, MIDDLE_COLUMN));
        tmesh.addArc(ArcNetwork.NONE, middle, far, 0, false,
                row(topology, MIDDLE_COLUMN, FAR_COLUMN));
        addColumnArc(tmesh, topology, critical, CRITICAL_COLUMN, TOP_ROW);
        addColumnArc(tmesh, topology, critical, CRITICAL_COLUMN, 0);
        int[] hangingArcs = {
            addColumnArc(tmesh, topology, middle, MIDDLE_COLUMN, TOP_ROW),
            addColumnArc(tmesh, topology, middle, MIDDLE_COLUMN, 0),
            addColumnArc(tmesh, topology, far, FAR_COLUMN, TOP_ROW),
            addColumnArc(tmesh, topology, far, FAR_COLUMN, 0),
        };
        int[] dragsByArc = new int[tmesh.arcs.size()];

        ZeroArcCollapseOperator collapse = new NetworkContraction(tmesh).collapseArc;
        for (int arcId = collapse.shortestZeroArc(); arcId != ArcNetwork.NONE;
                arcId = collapse.shortestZeroArc()) {
            assertEquals(shortestLiveZeroArcLength(tmesh),
                    tmesh.arcs.get(arcId).path.copyEdgePath.size(),
                    "pick " + collapse.collapsedCount + " is the shortest live zero arc");
            collapse.beginCollapse(arcId);
            assertEquals(critical, collapse.survivingNodeId, "the chain folds onto the critical node");
            while (collapse.dragNextArc()) {
                dragsByArc[collapse.lastDraggedArcId]++;
            }
            collapse.finishCollapse();
        }

        assertEquals(3, collapse.collapsedCount, "all three zero arcs collapse");
        assertEquals(criticalVertex, tmesh.nodes.get(critical).copyVertex,
                "the critical node never moves");
        for (int nodeId : new int[] { near, middle, far }) {
            assertFalse(tmesh.nodes.get(nodeId).alive, "node " + nodeId + " is merged away");
        }
        for (int hangingArc : hangingArcs) {
            assertEquals(1, dragsByArc[hangingArc], "arc " + hangingArc + " is re-routed once");
        }
    }

    /**
     * Two collapsible zero arcs: the three-edge one's moving node carries more arcs,
     * which the old most-contended order preferred, but the one-edge arc is chosen.
     */
    @Test
    void theShorterOfTwoZeroArcsIsChosen() {
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(
                GridMeshNode.triangulated(COLUMNS, ROWS));
        ArcNetwork tmesh = new ArcNetwork(topology);
        int longStart = tmesh.addNode(ArcNetwork.NONE, vertex(topology, CRITICAL_COLUMN, MIDDLE_ROW),
                false, false);
        int longEnd = tmesh.addNode(ArcNetwork.NONE, vertex(topology, LONG_END_COLUMN, MIDDLE_ROW),
                false, false);
        tmesh.addArc(ArcNetwork.NONE, longStart, longEnd, 0, false,
                row(topology, CRITICAL_COLUMN, LONG_END_COLUMN));
        addColumnArc(tmesh, topology, longStart, CRITICAL_COLUMN, TOP_ROW);
        addColumnArc(tmesh, topology, longEnd, LONG_END_COLUMN, TOP_ROW);
        int shortStart = tmesh.addNode(ArcNetwork.NONE,
                vertex(topology, SHORT_START_COLUMN, MIDDLE_ROW), false, false);
        int shortEnd = tmesh.addNode(ArcNetwork.NONE,
                vertex(topology, SHORT_START_COLUMN + 1, MIDDLE_ROW), false, false);
        int shortArc = tmesh.addArc(ArcNetwork.NONE, shortStart, shortEnd, 0, false,
                row(topology, SHORT_START_COLUMN, SHORT_START_COLUMN + 1));

        ZeroArcCollapseOperator collapse = new NetworkContraction(tmesh).collapseArc;

        assertEquals(shortArc, collapse.shortestZeroArc(),
                "the one-edge zero arc goes before the three-edge one");
    }

    /**
     * The shorter of two zero arcs is re-embedded along a detour, as a drag of one
     * of its nodes would, and the next pick re-ranks it behind the other.
     */
    @Test
    void aZeroArcThatLengthenedIsReRanked() {
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(
                GridMeshNode.triangulated(COLUMNS, ROWS));
        ArcNetwork tmesh = new ArcNetwork(topology);
        int firstStart = tmesh.addNode(ArcNetwork.NONE,
                vertex(topology, CRITICAL_COLUMN, MIDDLE_ROW), false, false);
        int firstEnd = tmesh.addNode(ArcNetwork.NONE,
                vertex(topology, CRITICAL_COLUMN + 2, MIDDLE_ROW), false, false);
        int firstArc = tmesh.addArc(ArcNetwork.NONE, firstStart, firstEnd, 0, false,
                row(topology, CRITICAL_COLUMN, CRITICAL_COLUMN + 2));
        int secondStart = tmesh.addNode(ArcNetwork.NONE,
                vertex(topology, SHORT_START_COLUMN - 1, MIDDLE_ROW), false, false);
        int secondEnd = tmesh.addNode(ArcNetwork.NONE, vertex(topology, FAR_COLUMN, MIDDLE_ROW),
                false, false);
        int secondArc = tmesh.addArc(ArcNetwork.NONE, secondStart, secondEnd, 0, false,
                row(topology, SHORT_START_COLUMN - 1, FAR_COLUMN));
        ZeroArcCollapseOperator collapse = new NetworkContraction(tmesh).collapseArc;
        assertEquals(firstArc, collapse.shortestZeroArc(), "two edges go before three");

        int detourRow = MIDDLE_ROW + 1;
        tmesh.setPath(firstArc, List.of(vertex(topology, CRITICAL_COLUMN, MIDDLE_ROW),
                vertex(topology, CRITICAL_COLUMN, detourRow),
                vertex(topology, CRITICAL_COLUMN + 1, detourRow),
                vertex(topology, CRITICAL_COLUMN + 2, detourRow),
                vertex(topology, CRITICAL_COLUMN + 2, MIDDLE_ROW)));
        assertTrue(tmesh.arcs.get(firstArc).path.copyEdgePath.size()
                > tmesh.arcs.get(secondArc).path.copyEdgePath.size(),
                "the detour is longer than the other arc");

        assertEquals(secondArc, collapse.shortestZeroArc(),
                "the lengthened arc is re-ranked behind the three-edge one");
    }

    /**
     * The fewest working-copy edges on any live zero arc.
     *
     * @param tmesh network to scan
     * @return that length, or {@link Integer#MAX_VALUE} when no zero arc lives
     */
    private int shortestLiveZeroArcLength(ArcNetwork tmesh) {
        int shortest = Integer.MAX_VALUE;
        for (EmbeddedArc arc : tmesh.arcs) {
            if (arc.alive && arc.quantizedLength == 0) {
                shortest = Math.min(shortest, arc.path.copyEdgePath.size());
            }
        }
        return shortest;
    }

    /**
     * Adds an arc from a middle-row node straight along its grid column to a new
     * node.
     *
     * @param tmesh    network receiving the arc
     * @param topology working copy over the grid
     * @param fromNode node the arc leaves
     * @param column   grid column the arc runs along
     * @param toRow    row of the new far node
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
     * The copy vertices of the middle row between two columns, inclusive.
     *
     * @param topology   working copy over the grid
     * @param fromColumn first column
     * @param toColumn   last column
     * @return the vertices in column order
     */
    private List<Integer> row(EmbeddedMeshTopology topology, int fromColumn, int toColumn) {
        List<Integer> vertices = new ArrayList<>();
        for (int column = fromColumn; column <= toColumn; column++) {
            vertices.add(vertex(topology, column, MIDDLE_ROW));
        }
        return vertices;
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
