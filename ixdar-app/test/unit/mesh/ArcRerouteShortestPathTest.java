package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ArcRerouter;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;

/**
 * A dragged arc keeps its lane: LCBK19 operator (1) <em>pulls</em> an incident arc along with the
 * moving node, it does not redraw the arc between its endpoints.
 *
 * <p>The paper spells the mechanism out for the border case — the dragged arc is <em>"re-embedded
 * onto the joint edge paths of b and a"</em>, i.e. its own path extended along the collapsed one.
 * The <em>"Dijkstra's shortest path algorithm between the respective two vertices"</em> sentence
 * describes how a <em>leg</em> is routed, and reading it as licence to re-route the whole arc from
 * scratch breaks the layout: at the moment of a drag the short continuation is blocked by the
 * claims of the collapse, so the search returns a path around the <em>other</em> side. Such a path
 * joins the right nodes, crosses and touches nothing, is simple and fully claimed — every local
 * check passes — yet the arc no longer separates the two patches the T-mesh records it as
 * separating, and the layout tears.
 *
 * <p>This pins the behaviour that prevents that. The arc below runs the long way around three sides
 * of a grid. Its node is dragged one step. The re-embedded arc must still run along that lane —
 * reaching the top rows — rather than reappearing as a fresh short path across the bottom. Pulling
 * the path taut within a tube around itself may shave its exact corners, since that preserves the
 * side it runs on; jumping to the other side is what tears the layout.
 */
class ArcRerouteShortestPathTest {

    private static final int COLUMNS = 7;
    private static final int ROWS = 6;
    private static final int DETOUR_ROW = 4;
    private static final int PIVOT_COLUMN = 4;
    private static final int SURVIVOR_COLUMN = 5;

    @Test
    void dragRetainsTheArcsLaneRatherThanRedrawingIt() {
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
        tmesh.dragArcEndOntoVertex(detourArc, pivotVertex, survivorVertex,
                new ArcRerouter(topology), channel);

        List<Integer> routed = tmesh.arcs.get(detourArc).path.copyVertexPath;
        assertEquals(vertex(topology, 0, 0), routed.get(0), "the arc still starts at its far node");
        assertEquals(survivorVertex, routed.get(routed.size() - 1),
                "the arc now ends at the survivor");
        boolean keptExcursion = false;
        for (int column = 0; column <= PIVOT_COLUMN; column++) {
            keptExcursion |= routed.contains(vertex(topology, column, DETOUR_ROW))
                    || routed.contains(vertex(topology, column, DETOUR_ROW - 1));
        }
        assertTrue(keptExcursion,
                "the dragged arc must keep its up-and-over lane — reaching the top rows, not cutting"
                        + " straight across the bottom, which would reappear on the wrong side and"
                        + " tear the layout. Taut-straightening may shave the exact corners, not the"
                        + " excursion itself.");
        for (int index = 1; index < routed.size(); index++) {
            assertTrue(topology.edgeBetween(routed.get(index - 1), routed.get(index))
                    != EmbeddedMeshTopology.UNCLAIMED,
                    "consecutive routed vertices must share a copy edge");
        }
    }

    /**
     * The long way round: up the left edge, across the top, and back down to the pivot.
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
