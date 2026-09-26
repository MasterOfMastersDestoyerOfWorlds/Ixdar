package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.ActiveIdSet;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.primitives.GridMeshNode;
import ixdar.geometry.mesh.quadlayout.embedding.ArcRerouter;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * A re-route through a claimed wall whose sole free crossing edge has both endpoints claimed
 * must succeed by refining that gate, however long the wall is.
 *
 * <p>See also: LCBK19 Section 6.1; {@link ArcRerouter}
 */
class ArcCorridorThreadTest {

    private static final int COLUMNS = 11;
    private static final int ROWS = 61;
    private static final int WALL_COLUMN = 4;
    private static final int MIDDLE_ROW = 30;
    private static final int GAP_ROW = 30;
    private static final int CLAIM_MARKER = 7;

    @Test
    void reRouteThreadsAThreadableGateInsteadOfBurningItsBudgetElsewhere() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);
        for (int row = 0; row < ROWS; row++) {
            topology.ownerArcByCopyVertex[vertex(topology, WALL_COLUMN, row)] = CLAIM_MARKER;
        }
        for (int row = 0; row < ROWS - 1; row++) {
            if (row == GAP_ROW) {
                continue;
            }
            topology.ownerArcByCopyEdge[topology.copy.edgeBetween(vertex(topology, WALL_COLUMN, row),
                    vertex(topology, WALL_COLUMN, row + 1))] = CLAIM_MARKER;
        }
        topology.ownerArcByCopyVertex[vertex(topology, WALL_COLUMN - 1, GAP_ROW)] = CLAIM_MARKER;
        topology.ownerArcByCopyVertex[vertex(topology, WALL_COLUMN + 1, GAP_ROW + 1)] = CLAIM_MARKER;

        int startVertex = vertex(topology, 0, MIDDLE_ROW);
        int targetVertex = vertex(topology, COLUMNS - 1, MIDDLE_ROW);

        assertTrue(faceThreadable(topology, startVertex, targetVertex),
                "the band is face-threadable — no claimed edge separates the two halves");

        ActiveIdSet corridor = unclaimedVertices(topology);
        List<Integer> routed = new ArrayList<>();
        boolean reached = new ArcRerouter(topology).tryRoute(CLAIM_MARKER, routed, startVertex,
                targetVertex, corridor, EmbeddedMeshTopology.UNCLAIMED);

        assertTrue(reached, "a threadable region must be re-routable with refinement");
        assertFalse(routed.isEmpty(), "a reached route has vertices");
        for (int index = 1; index < routed.size(); index++) {
            assertNotEquals(EmbeddedMeshTopology.UNCLAIMED,
                    topology.copy.edgeBetween(routed.get(index - 1), routed.get(index)),
                    "consecutive routed vertices must share a copy edge");
        }
    }

    /**
     * Whether a face walk crossing only unclaimed edges connects two vertices — the
     * same arrangement-face test the failure diagnostic uses, computed here without
     * touching the class under test.
     *
     * @param topology     working copy carrying the claim arrays
     * @param startVertex  walk source vertex
     * @param targetVertex walk target vertex
     * @return true when a claimed-edge-free face path connects them
     */
    private boolean faceThreadable(EmbeddedMeshTopology topology, int startVertex, int targetVertex) {
        HalfEdgeMesh copy = topology.copy;
        Set<Integer> targetFaces = new HashSet<>();
        for (int index = 0; index < copy.vertexFaceCount(targetVertex); index++) {
            targetFaces.add(copy.vertexFaceAt(targetVertex, index));
        }
        Set<Integer> visited = new HashSet<>();
        Deque<Integer> frontier = new ArrayDeque<>();
        for (int index = 0; index < copy.vertexFaceCount(startVertex); index++) {
            int face = copy.vertexFaceAt(startVertex, index);
            if (visited.add(face)) {
                frontier.add(face);
            }
        }
        while (!frontier.isEmpty()) {
            int face = frontier.poll();
            if (targetFaces.contains(face)) {
                return true;
            }
            for (int corner = 0; corner < 3; corner++) {
                int edgeId = copy.faceEdgeAt(face, corner);
                if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                    continue;
                }
                int neighborFace = copy.faceAcrossEdge(face, edgeId);
                if (neighborFace != EmbeddedMeshTopology.UNCLAIMED && visited.add(neighborFace)) {
                    frontier.add(neighborFace);
                }
            }
        }
        return false;
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
