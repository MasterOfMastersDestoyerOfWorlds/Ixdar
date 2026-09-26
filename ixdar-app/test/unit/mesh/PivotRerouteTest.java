package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.GridMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * The pivot wall on a hand-built 5×3 grid: arcs through the centre vertex n0
 * leave b = m→n0 no way to n1 but the pivot itself, so b becomes m→n0→n1 —
 * LCBK19's "pulling its incident arcs with it".
 */
class PivotRerouteTest {

    private static final int COLUMNS = 5;
    private static final int ROWS = 3;

    @Test
    void draggingAnArcFollowsTheCollapsingNodeThroughTheChannel() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);
        ArcNetwork tmesh = new ArcNetwork(topology);

        int mNode = tmesh.addNode(ArcNetwork.NONE, vertex(topology, 1, 1), false, false);
        int pivotNode = tmesh.addNode(ArcNetwork.NONE, vertex(topology, 2, 1), false, false);
        int survivorNode = tmesh.addNode(ArcNetwork.NONE, vertex(topology, 3, 1), false, false);
        int topNode = tmesh.addNode(ArcNetwork.NONE, vertex(topology, 2, 0), false, false);
        int bottomNode = tmesh.addNode(ArcNetwork.NONE, vertex(topology, 2, 2), false, false);

        int arcB = tmesh.addArc(ArcNetwork.NONE, mNode, pivotNode, 1, false,
                List.of(vertex(topology, 1, 1), vertex(topology, 2, 1)));
        int arcA = tmesh.addArc(ArcNetwork.NONE, pivotNode, survivorNode, 0, false,
                List.of(vertex(topology, 2, 1), vertex(topology, 3, 1)));
        tmesh.addArc(ArcNetwork.NONE, pivotNode, topNode, 1, false,
                List.of(vertex(topology, 2, 1), vertex(topology, 2, 0)));
        tmesh.addArc(ArcNetwork.NONE, pivotNode, bottomNode, 1, false,
                List.of(vertex(topology, 2, 1), vertex(topology, 2, 2)));

        int pivotVertex = vertex(topology, 2, 1);
        int survivorVertex = vertex(topology, 3, 1);

        tmesh.setPath(arcA, List.of(survivorVertex));
        ZeroArcCollapseOperator collapseOperator = new NetworkContraction(tmesh).collapseArc;
        collapseOperator.dragArcEndOntoVertex(arcB, pivotVertex, survivorVertex);

        List<Integer> path = tmesh.arcs.get(arcB).path.copyVertexPath;
        assertEquals(vertex(topology, 1, 1), path.get(0), "b still starts at m");
        assertEquals(survivorVertex, path.get(path.size() - 1), "b now ends at the survivor");
        for (int index = 1; index < path.size(); index++) {
            assertNotEquals(EmbeddedMeshTopology.UNCLAIMED,
                    topology.copy.edgeBetween(path.get(index - 1), path.get(index)),
                    "consecutive path vertices must share a copy edge");
        }
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
