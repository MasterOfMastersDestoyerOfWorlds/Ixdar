package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.GridMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * A dragged arc re-embeds by LCBK19 §6.1's method: Dijkstra's shortest path
 * between its two vertices, crossing and touching no other arc. The arc below
 * runs the long way round a grid, so the drag straightens it.
 */
class ArcRerouteShortestPathTest {

    private static final int COLUMNS = 7;
    private static final int ROWS = 6;
    private static final int DETOUR_ROW = 4;
    private static final int PIVOT_COLUMN = 4;
    private static final int SURVIVOR_COLUMN = 5;

    @Test
    void dragReRoutesTheArcByShortestPathAndStraightensIt() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);
        ArcNetwork tmesh = new ArcNetwork(topology);

        int farNode = tmesh.addNode(ArcNetwork.NONE, vertex(topology, 0, 0), false, false);
        int pivotNode = tmesh.addNode(ArcNetwork.NONE, vertex(topology, PIVOT_COLUMN, 0),
                false, false);
        int survivorNode = tmesh.addNode(ArcNetwork.NONE, vertex(topology, SURVIVOR_COLUMN, 0),
                false, false);

        int detourArc = tmesh.addArc(ArcNetwork.NONE, farNode, pivotNode, 1, false,
                detourPath(topology));
        int collapsingArc = tmesh.addArc(ArcNetwork.NONE, pivotNode, survivorNode, 0, false,
                List.of(vertex(topology, PIVOT_COLUMN, 0), vertex(topology, SURVIVOR_COLUMN, 0)));

        int pivotVertex = vertex(topology, PIVOT_COLUMN, 0);
        int survivorVertex = vertex(topology, SURVIVOR_COLUMN, 0);
        tmesh.setPath(collapsingArc, List.of(survivorVertex));
        ZeroArcCollapseOperator collapseOperator = new NetworkContraction(tmesh).collapseArc;
        collapseOperator.dragArcEndOntoVertex(detourArc, pivotVertex, survivorVertex);

        List<Integer> routed = tmesh.arcs.get(detourArc).path.copyVertexPath;
        assertEquals(vertex(topology, 0, 0), routed.get(0), "the arc still starts at its far node");
        assertEquals(survivorVertex, routed.get(routed.size() - 1),
                "the arc now ends at the survivor");
        assertTrue(routed.size() < detourPath(topology).size(),
                "shortest-path reroute straightens the arc, so it is shorter than the old detour");
        assertEquals(routed.size(), new HashSet<>(routed).size(), "the routed path is simple");
        for (int index = 1; index < routed.size(); index++) {
            assertTrue(topology.copy.edgeBetween(routed.get(index - 1), routed.get(index)) != EmbeddedMeshTopology.UNCLAIMED,
                    "consecutive routed vertices must share a copy edge");
        }
    }

    /**
     * The long way round: up the left edge, across the top, and back down to the
     * pivot.
     *
     * @param topology working copy over the grid
     * @return the detour's copy vertices in walking order
     */
    private List<Integer> detourPath(EmbeddedMeshTopology topology) {
        List<Integer> path = new ArrayList<>();
        for (int row = 0; row <= DETOUR_ROW; row++) {
            path.add(vertex(topology, 0, row));
        }
        for (int column = 1; column <= PIVOT_COLUMN; column++) {
            path.add(vertex(topology, column, DETOUR_ROW));
        }
        for (int row = DETOUR_ROW - 1; row >= 0; row--) {
            path.add(vertex(topology, PIVOT_COLUMN, row));
        }
        return path;
    }

    /**
     * Builds a {@link #COLUMNS}×{@link #ROWS} triangulated grid via the shared
     * {@link Grids} fixture, so the tests run on {@code mesh_grid}'s real output.
     *
     * @return the grid as a half-edge mesh
     */
    private HalfEdgeMesh buildGrid() {
        return GridMeshNode.triangulated(COLUMNS, ROWS);
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
        return topology.copyVertexForSourceVertexId(GridMeshNode.vertexId(ROWS, column, row));
    }
}
