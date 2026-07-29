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
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ArcRerouter;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedMeshTopology;

/**
 * Reproduces the operator-(1) re-route failure that stalled the sphere at arc 18: a region the face
 * flood says is threadable, which the vertex search could not enter.
 *
 * <p>The obstruction is not a wall. A face path to the survivor exists that crosses no claimed arc
 * <em>edge</em>, so the region is threadable and LCBK19's <em>"resolved by refinement with a few edge
 * splits"</em> should open it. What blocks the vertex search is a crossing edge whose <em>both</em>
 * endpoints are claimed — it can stand on neither end.
 *
 * <p>The wall's length is the only variable here, and the outcome must not depend on it. A policy
 * that refines a bounded number of edges chosen without regard to where the route runs succeeds on a
 * short wall and fails on a long one from identical geometry; {@link ArcRerouter} instead names the
 * blocking edge from the corridor it is about to walk, so length is irrelevant.
 *
 * <p>The wall is a single claimed column whose vertical edges are all claimed except one, so the sole
 * crossing is that free edge; both of its endpoints and both opposite face corners are claimed, which
 * makes it a gate the search can only pass once refinement splits it.
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
            topology.ownerArcByCopyEdge[topology.edgeBetween(vertex(topology, WALL_COLUMN, row),
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
                    topology.edgeBetween(routed.get(index - 1), routed.get(index)),
                    "consecutive routed vertices must share a copy edge");
        }
    }

    /**
     * Whether a face walk crossing only unclaimed edges connects two vertices — the same
     * arrangement-face test the failure diagnostic uses, computed here without touching the class
     * under test.
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
                int halfEdge = copy.edgeHalfEdge(edgeId);
                int neighborFace = copy.halfEdgeFace(halfEdge) == face
                        ? copy.halfEdgeFace(copy.halfEdgeTwin(halfEdge))
                        : copy.halfEdgeFace(halfEdge);
                if (neighborFace != EmbeddedMeshTopology.UNCLAIMED && visited.add(neighborFace)) {
                    frontier.add(neighborFace);
                }
            }
        }
        return false;
    }

    /**
     * Every copy vertex owned by neither a node nor an arc — the corridor a re-route is allowed.
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
