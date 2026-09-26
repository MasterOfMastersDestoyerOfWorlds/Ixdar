package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.ActiveIdSet;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.GridMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArcRerouter;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * A re-route whose passage runs through the collapsing node, a cut vertex on a sealing wall,
 * must transit that pivot and refine the gated far leg.
 *
 * <p>See also: LCBK19 Section 6.1
 */
class ArcPivotTransitCorridorTest {

    private static final int COLUMNS = 30;
    private static final int ROWS = 61;
    private static final int SEAL_COLUMN = 15;
    private static final int GATE_COLUMN = 22;
    private static final int MIDDLE_ROW = 30;
    private static final int GAP_ROW = 30;
    private static final int CLAIM_MARKER = 7;

    @Test
    void reRouteTransitsThePivotAndRefinesTheFarLeg() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);

        for (int row = 0; row < ROWS; row++) {
            topology.ownerArcByCopyVertex[vertex(topology, SEAL_COLUMN, row)] = CLAIM_MARKER;
            topology.ownerArcByCopyVertex[vertex(topology, GATE_COLUMN, row)] = CLAIM_MARKER;
        }
        for (int row = 0; row < ROWS - 1; row++) {
            topology.ownerArcByCopyEdge[topology.copy.edgeBetween(vertex(topology, SEAL_COLUMN, row),
                    vertex(topology, SEAL_COLUMN, row + 1))] = CLAIM_MARKER;
            if (row != GAP_ROW) {
                topology.ownerArcByCopyEdge[topology.copy.edgeBetween(vertex(topology, GATE_COLUMN, row),
                        vertex(topology, GATE_COLUMN, row + 1))] = CLAIM_MARKER;
            }
        }

        int startVertex = vertex(topology, 0, MIDDLE_ROW);
        int pivotVertex = vertex(topology, SEAL_COLUMN, MIDDLE_ROW);
        int targetVertex = vertex(topology, COLUMNS - 1, MIDDLE_ROW);

        ActiveIdSet corridor = unclaimedVertices(topology);
        corridor.add(pivotVertex);
        corridor.add(targetVertex);
        List<Integer> routed = new ArrayList<>();
        boolean reached = new ArcRerouter(topology).tryRoute(CLAIM_MARKER, routed, startVertex,
                targetVertex, corridor, pivotVertex);

        assertTrue(reached, "a route that transits the collapsing node must be found: the blocking"
                + " gates lie on the pivot-to-target leg, not on any body-to-target corridor");
        assertTrue(routed.contains(pivotVertex),
                "the route reaches the target by passing through the collapsing node");
    }

    /**
     * Both legs of a pivot transit route: the free near leg is still walked after the far
     * leg is refined.
     */
    @Test
    void reRouteAdmitsThePassageItRefinedIntoTheCorridor() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);

        for (int row = 0; row < ROWS; row++) {
            topology.ownerArcByCopyVertex[vertex(topology, SEAL_COLUMN, row)] = CLAIM_MARKER;
            topology.ownerArcByCopyVertex[vertex(topology, GATE_COLUMN, row)] = CLAIM_MARKER;
        }
        for (int row = 0; row < ROWS - 1; row++) {
            topology.ownerArcByCopyEdge[topology.copy.edgeBetween(vertex(topology, SEAL_COLUMN, row),
                    vertex(topology, SEAL_COLUMN, row + 1))] = CLAIM_MARKER;
            if (row != GAP_ROW) {
                topology.ownerArcByCopyEdge[topology.copy.edgeBetween(vertex(topology, GATE_COLUMN, row),
                        vertex(topology, GATE_COLUMN, row + 1))] = CLAIM_MARKER;
            }
        }

        int startVertex = vertex(topology, 0, MIDDLE_ROW);
        int pivotVertex = vertex(topology, SEAL_COLUMN, MIDDLE_ROW);
        int targetVertex = vertex(topology, COLUMNS - 1, MIDDLE_ROW);

        ActiveIdSet corridor = new ActiveIdSet(topology.ownerArcByCopyVertex.length);
        for (int row = 0; row < ROWS; row++) {
            for (int column = SEAL_COLUMN + 1; column < COLUMNS; column++) {
                int copyVertex = vertex(topology, column, row);
                if (topology.ownerArcByCopyVertex[copyVertex] == EmbeddedMeshTopology.UNCLAIMED) {
                    corridor.add(copyVertex);
                }
            }
        }
        corridor.add(startVertex);
        corridor.add(pivotVertex);
        corridor.add(targetVertex);

        List<Integer> routed = new ArrayList<>();
        boolean reached = new ArcRerouter(topology).tryRoute(CLAIM_MARKER, routed, startVertex,
                targetVertex, corridor, pivotVertex);

        assertTrue(reached, "the near leg of the transit must be admitted to the corridor: it needs"
                + " no splits, but the search may not stand on it unless the passage is admitted");
    }

    /**
     * Every copy vertex owned by neither a node nor an arc — the corridor a
     * re-route is allowed.
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
