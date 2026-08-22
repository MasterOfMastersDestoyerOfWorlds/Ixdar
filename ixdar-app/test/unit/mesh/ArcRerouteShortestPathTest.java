package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.GridMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArcRerouter;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * A dragged arc re-embeds by the paper's method: LCBK19 §6.1 routes the pulled
 * arc with Dijkstra's shortest path between its two vertices, restricted to not
 * cross or touch other arcs.
 *
 * <p>
 * An earlier "keep the old excursion" reading was disproven against the real
 * target: on fertility shortest-path yields ~8000x fewer folded triangles than
 * keeping the long lane, and leaves the regions untorn. So the short
 * continuation is correct, not a tear.
 *
 * <p>
 * This pins that the drag produces a valid embedding. The arc below runs the
 * long way around three sides of a grid; its node is dragged one step. The
 * re-embedded arc must be re-anchored from its far node to the survivor, be
 * simple, lie on claimed copy edges, and be shorter than the old detour — the
 * reroute straightens it.
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
        EmbeddedTMesh tmesh = new EmbeddedTMesh(topology);

        int farNode = tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 0, 0), false, false);
        int pivotNode = tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, PIVOT_COLUMN, 0),
                false, false);
        int survivorNode = tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, SURVIVOR_COLUMN, 0),
                false, false);

        int detourArc = tmesh.addArc(EmbeddedTMesh.NONE, farNode, pivotNode, 1, false,
                detourPath(topology));
        int collapsingArc = tmesh.addArc(EmbeddedTMesh.NONE, pivotNode, survivorNode, 0, false,
                List.of(vertex(topology, PIVOT_COLUMN, 0), vertex(topology, SURVIVOR_COLUMN, 0)));

        int pivotVertex = vertex(topology, PIVOT_COLUMN, 0);
        int survivorVertex = vertex(topology, SURVIVOR_COLUMN, 0);
        List<Integer> channel = List.copyOf(tmesh.arcs.get(collapsingArc).path.copyVertexPath);

        tmesh.setPath(collapsingArc, List.of(survivorVertex));
        tmesh.collapseArc.dragArcEndOntoVertex(detourArc, pivotVertex, survivorVertex,
                new ArcRerouter(topology), channel, true, false);

        List<Integer> routed = tmesh.arcs.get(detourArc).path.copyVertexPath;
        assertEquals(vertex(topology, 0, 0), routed.get(0), "the arc still starts at its far node");
        assertEquals(survivorVertex, routed.get(routed.size() - 1),
                "the arc now ends at the survivor");
        assertTrue(routed.size() < detourPath(topology).size(),
                "shortest-path reroute straightens the arc, so it is shorter than the old detour");
        assertEquals(routed.size(), new HashSet<>(routed).size(), "the routed path is simple");
        for (int index = 1; index < routed.size(); index++) {
            assertTrue(topology.edgeBetween(routed.get(index - 1), routed.get(index)) != EmbeddedMeshTopology.UNCLAIMED,
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
