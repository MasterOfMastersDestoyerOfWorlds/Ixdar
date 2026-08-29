package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.GridMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArcRerouter;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * The real multi-arc pivot bottleneck, isolated on a hand-built 9×3 grid — the
 * case the sphere and fertility contractions stall on. Two arcs b1 and b2 are
 * incident to the collapsing node n0, and the channel from n0 to n1 runs
 * through an interior node mid, in a strip walled one triangle wide (nodes
 * above and below mid):
 *
 * <pre>
 *   row 0   .    .   wall  wall  wall  .    .    .    .
 *   row 1   .   m1 -- n0 -- mid -- n1   .    .    .    .
 *   row 2   .  m2(diag)  wall  wall  .    .    .    .    .
 *   col 0    1    2    3    4    5    6    7    8
 * </pre>
 *
 * <p>
 * Collapsing the zero arc a = n0→mid→n1 drags both b1 and b2 onto n1. The
 * first, b1, follows the pivot through the freed channel, taking n0→mid→n1 and
 * claiming mid. The second, b2, then finds n0's direct way into the channel —
 * the edge n0→mid — consumed and the strip one triangle wide, yet the two-phase
 * drag (pivot transit) plus the edge-split refinement still opens a lane, so b2
 * routes too. Even this one-wide-channel competition is handled; both arcs
 * reach the survivor. It is a regression guard that the pivot re-route survives
 * a consumed narrow channel.
 */
class MultiArcPivotTest {

    private static final int COLUMNS = 9;
    private static final int ROWS = 3;

    @Test
    void bothArcsThroughAOneWideChannelReachTheSurvivor() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);
        ArcNetwork tmesh = new ArcNetwork(topology);

        int m1 = tmesh.addNode(ArcNetwork.NONE, vertex(topology, 2, 1), false, false);
        int m2 = tmesh.addNode(ArcNetwork.NONE, vertex(topology, 2, 0), false, false);
        int pivot = tmesh.addNode(ArcNetwork.NONE, vertex(topology, 3, 1), false, false);
        int survivor = tmesh.addNode(ArcNetwork.NONE, vertex(topology, 5, 1), false, false);
        tmesh.addNode(ArcNetwork.NONE, vertex(topology, 3, 0), false, false);
        tmesh.addNode(ArcNetwork.NONE, vertex(topology, 3, 2), false, false);
        tmesh.addNode(ArcNetwork.NONE, vertex(topology, 4, 0), false, false);
        tmesh.addNode(ArcNetwork.NONE, vertex(topology, 4, 2), false, false);

        int arcB1 = tmesh.addArc(ArcNetwork.NONE, m1, pivot, 1, false,
                List.of(vertex(topology, 2, 1), vertex(topology, 3, 1)));
        int arcB2 = tmesh.addArc(ArcNetwork.NONE, m2, pivot, 1, false,
                List.of(vertex(topology, 2, 0), vertex(topology, 3, 1)));
        int arcA = tmesh.addArc(ArcNetwork.NONE, pivot, survivor, 0, false,
                List.of(vertex(topology, 3, 1), vertex(topology, 4, 1), vertex(topology, 5, 1)));

        ArcRerouter rerouter = new ArcRerouter(topology);
        int pivotVertex = vertex(topology, 3, 1);
        int survivorVertex = vertex(topology, 5, 1);
        List<Integer> channel = List.copyOf(tmesh.arcs.get(arcA).path.copyVertexPath);
        tmesh.setPath(arcA, List.of(survivorVertex));

        ZeroArcCollapseOperator collapseOperator = new NetworkContraction(tmesh).collapseArc;
        collapseOperator.dragArcEndOntoVertex(arcB1, pivotVertex, survivorVertex, rerouter,
                channel, true, false);
        collapseOperator.dragArcEndOntoVertex(arcB2, pivotVertex, survivorVertex, rerouter,
                channel, true, false);

        assertEquals(survivorVertex, lastVertexOf(tmesh, arcB1), "b1 reaches the survivor");
        assertEquals(survivorVertex, lastVertexOf(tmesh, arcB2), "b2 also reaches the survivor");
    }

    /**
     * The last copy vertex of an arc's path.
     *
     * @param tmesh the T-mesh
     * @param arcId arc to read
     * @return its path's final vertex
     */
    private int lastVertexOf(ArcNetwork tmesh, int arcId) {
        List<Integer> path = tmesh.arcs.get(arcId).path.copyVertexPath;
        return path.get(path.size() - 1);
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
