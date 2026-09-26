package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.GridMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * Two arcs dragged onto one survivor down a channel one triangle wide: the
 * first takes the channel, and the second still routes, because the pivot
 * transit and the edge splits open it a lane of its own.
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

        int pivotVertex = vertex(topology, 3, 1);
        int survivorVertex = vertex(topology, 5, 1);
        tmesh.setPath(arcA, List.of(survivorVertex));

        ZeroArcCollapseOperator collapseOperator = new NetworkContraction(tmesh).collapseArc;
        collapseOperator.dragArcEndOntoVertex(arcB1, pivotVertex, survivorVertex);
        collapseOperator.dragArcEndOntoVertex(arcB2, pivotVertex, survivorVertex);

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
