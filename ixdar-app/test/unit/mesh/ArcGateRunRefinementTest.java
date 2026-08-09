package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.ActiveIdSet;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ArcRerouter;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * A re-route whose only passage is a long run of gates must still be found. This is the wall that
 * stops the sphere and fertility contractions: two arcs running adjacent leave a channel between
 * them whose every rung is an unclaimed edge with <em>both</em> endpoints claimed, so the vertex
 * search can stand on neither end and must be let through by refinement.
 *
 * <p>LCBK19 §6.1 says such a blockage is <em>"easily resolved by refinement with a few edge
 * splits"</em>, and the number of splits it needs is not a matter of judgement: the face flood that
 * finds the passage also names every edge on it, and the ones needing a split are exactly those with
 * both endpoints claimed. Splitting them in passage order is enough, because consecutive crossed
 * edges share a face and the retriangulation joins each new midpoint to the previous one.
 *
 * <p>The channel below is deliberately longer than any fixed split allowance. That is the whole
 * point: a re-route must succeed because the passage is open, not because the passage happened to be
 * short enough. A capped refinement gives up here with the passage still visibly threadable, which
 * is what the sphere's {@code bothClaimed=189} and fertility's {@code bothClaimed=107} diagnostics
 * were reporting.
 */
class ArcGateRunRefinementTest {

    private static final int COLUMNS = 140;
    private static final int ROWS = 5;
    private static final int LOWER_ROW = 1;
    private static final int UPPER_ROW = 2;
    private static final int LOWER_ARC = 7;
    private static final int UPPER_ARC = 8;
    private static final int ROUTED_ARC = 9;

    /** A gate run at least this long outruns any single-pass split allowance a refinement might cap at. */
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
            topology.ownerArcByCopyEdge[topology.edgeBetween(
                    vertex(topology, column, LOWER_ROW),
                    vertex(topology, column + 1, LOWER_ROW))] = LOWER_ARC;
            topology.ownerArcByCopyEdge[topology.edgeBetween(
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
                    topology.edgeBetween(routed.get(index - 1), routed.get(index)),
                    "consecutive routed vertices must share a copy edge");
        }
    }

    /**
     * The number of rungs across the channel — unclaimed edges joining the two claimed rows, each of
     * which the vertex search can cross only once refinement splits it.
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
                int other = topology.otherEndpoint(edgeId, lower);
                if (topology.ownerArcByCopyVertex[other] == UPPER_ARC) {
                    gates++;
                }
            }
        }
        return gates;
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
