package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Gate refinement must not split speculatively: every midpoint it mints has to
 * be one the route then stands on.
 *
 * <p>
 * Two claimed rows form a channel whose every rung is a gate — an unclaimed
 * edge with both endpoints claimed. Threading the channel needs one minted
 * midpoint per rung crossed, so the splits are stepping stones rather than
 * waste, and their count must equal the number of minted vertices on the
 * resulting path.
 *
 * <p>
 * Refinement that split a passage the route then ignored would grow the working
 * mesh permanently for nothing, and the working mesh never shrinks again.
 *
 * <p>
 * See also: LCBK19 Section 6.1
 */
class GatePassageMinimalityTest {

    private static final int COLUMNS = 40;
    private static final int ROWS = 5;
    private static final int LOWER_ROW = 1;
    private static final int UPPER_ROW = 2;
    private static final int LOWER_ARC = 7;
    private static final int UPPER_ARC = 8;
    private static final int ROUTED_ARC = 9;

    @Test
    void everyGateSplitIsSteppedOnByTheRoute() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);
        for (int column = 0; column < COLUMNS; column++) {
            topology.ownerArcByCopyVertex[vertex(topology, column, LOWER_ROW)] = LOWER_ARC;
            topology.ownerArcByCopyVertex[vertex(topology, column, UPPER_ROW)] = UPPER_ARC;
        }
        for (int column = 0; column < COLUMNS - 1; column++) {
            topology.ownerArcByCopyEdge[topology.edgeBetween(
                    vertex(topology, column, LOWER_ROW),
                    vertex(topology, column + 1, LOWER_ROW))] = LOWER_ARC;
            topology.ownerArcByCopyEdge[topology.edgeBetween(
                    vertex(topology, column, UPPER_ROW),
                    vertex(topology, column + 1, UPPER_ROW))] = UPPER_ARC;
        }

        int originalVertexCount = COLUMNS * ROWS;
        int startVertex = vertex(topology, 0, LOWER_ROW);
        int targetVertex = vertex(topology, COLUMNS - 1, UPPER_ROW);

        ActiveIdSet corridor = unclaimedVertices(topology);
        corridor.add(startVertex);
        corridor.add(targetVertex);
        List<Integer> routed = new ArrayList<>();
        ArcRerouter rerouter = new ArcRerouter(topology);
        boolean reached = rerouter.tryRoute(ROUTED_ARC, routed, startVertex, targetVertex, corridor,
                EmbeddedMeshTopology.UNCLAIMED);

        assertTrue(reached, "the channel is a threadable passage, so the route must be found");
        int mintedOnRoute = 0;
        for (int routedVertex : routed) {
            if (routedVertex >= originalVertexCount) {
                mintedOnRoute++;
            }
        }
        assertTrue(mintedOnRoute > 0, "threading the channel has to stand on minted midpoints,"
                + " or the fixture is not exercising gate refinement at all");
        assertEquals(mintedOnRoute, rerouter.refinedEdgeSplitCount,
                "every gate split must be a midpoint the route stands on: a split the route ignores"
                        + " grows the working mesh permanently for nothing");
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
