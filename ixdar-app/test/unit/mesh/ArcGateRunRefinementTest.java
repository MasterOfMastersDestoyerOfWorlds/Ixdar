package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * A re-route whose only passage is a channel of gates longer than any fixed split allowance,
 * each rung having both endpoints claimed, must still be found by refinement.
 *
 * <p>See also: LCBK19 Section 6.1
 */
class ArcGateRunRefinementTest {

    private static final int COLUMNS = 140;
    private static final int ROWS = 5;
    private static final int LOWER_ROW = 1;
    private static final int UPPER_ROW = 2;
    private static final int LOWER_ARC = 7;
    private static final int UPPER_ARC = 8;
    private static final int ROUTED_ARC = 9;

    /**
     * A gate run at least this long outruns any single-pass split allowance a
     * refinement might cap at.
     */
    private static final int LONG_GATE_RUN = 128;

    @Test
    void reRouteThreadsAGateRunLongerThanAnyFixedSplitAllowance() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);
        for (int column = 0; column < COLUMNS; column++) {
            topology.ownerArcByCopyVertex[vertex(topology, column, LOWER_ROW)] = LOWER_ARC;
            topology.ownerArcByCopyVertex[vertex(topology, column, UPPER_ROW)] = UPPER_ARC;
        }
        for (int column = 0; column < COLUMNS - 1; column++) {
            topology.ownerArcByCopyEdge[topology.copy.edgeBetween(
                    vertex(topology, column, LOWER_ROW),
                    vertex(topology, column + 1, LOWER_ROW))] = LOWER_ARC;
            topology.ownerArcByCopyEdge[topology.copy.edgeBetween(
                    vertex(topology, column, UPPER_ROW),
                    vertex(topology, column + 1, UPPER_ROW))] = UPPER_ARC;
        }

        int startVertex = vertex(topology, 0, LOWER_ROW);
        int targetVertex = vertex(topology, COLUMNS - 1, UPPER_ROW);

        assertTrue(gateRunLength(topology) > LONG_GATE_RUN,
                "the channel must need more splits than any fixed allowance, or the test proves"
                        + " nothing about the allowance");

        ActiveIdSet corridor = unclaimedVertices(topology);
        corridor.add(startVertex);
        corridor.add(targetVertex);
        List<Integer> routed = new ArrayList<>();
        boolean reached = new ArcRerouter(topology).tryRoute(ROUTED_ARC, routed, startVertex,
                targetVertex, corridor, EmbeddedMeshTopology.UNCLAIMED);

        assertTrue(reached, "the channel is a threadable passage, so the re-route must find it");
        assertFalse(routed.isEmpty(), "a reached route has vertices");
        for (int index = 1; index < routed.size(); index++) {
            assertNotEquals(EmbeddedMeshTopology.UNCLAIMED,
                    topology.copy.edgeBetween(routed.get(index - 1), routed.get(index)),
                    "consecutive routed vertices must share a copy edge");
        }
    }

    /**
     * The number of rungs across the channel — unclaimed edges joining the two
     * claimed rows, each of which the vertex search can cross only once refinement
     * splits it.
     *
     * @param topology working copy carrying the claim arrays
     * @return the count of gate edges between the two claimed rows
     */
    private int gateRunLength(EmbeddedMeshTopology topology) {
        int gates = 0;
        for (int column = 0; column < COLUMNS; column++) {
            int lower = vertex(topology, column, LOWER_ROW);
            for (int index = 0; index < topology.copy.vertexEdgeCount(lower); index++) {
                int edgeId = topology.copy.vertexEdgeAt(lower, index);
                if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                    continue;
                }
                int other = topology.copy.edgeOtherVertex(edgeId, lower);
                if (topology.ownerArcByCopyVertex[other] == UPPER_ARC) {
                    gates++;
                }
            }
        }
        return gates;
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
