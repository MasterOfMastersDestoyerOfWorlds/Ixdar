package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.ConformingLoopSnap;
import ixdar.geometry.mesh.data.paths.TracedSurfacePath;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;

/**
 * A closed trace on a triangle grid whose snapped vertices step out and straight back (A, B, A)
 * marks a simple edge loop, without the dead-end spur the doubled edge would leave.
 */
class ConformingLoopSnapTest {

    private static final int GRID_SIDE = 5;

    private static final int[][] CENTRE_SQUARE_LOOP_COLUMN_ROWS = { { 1, 1 }, { 2, 1 }, { 3, 1 },
        { 3, 2 }, { 3, 3 }, { 2, 3 }, { 1, 3 }, { 1, 2 } };

    private static final int[] SPUR_BASE = { 2, 1 };

    private static final int[] SPUR_TIP = { 2, 0 };

    @Test
    void stepOutAndBackLeavesNoSpurOnATriangleMesh() {
        // A flat GRID_SIDE square grid of unit cells, each split into two triangles.
        float[] gridPositions = new float[3 * GRID_SIDE * GRID_SIDE];
        for (int row = 0; row < GRID_SIDE; row++) {
            for (int column = 0; column < GRID_SIDE; column++) {
                gridPositions[3 * (row * GRID_SIDE + column)] = column;
                gridPositions[3 * (row * GRID_SIDE + column) + 1] = row;
            }
        }
        int cells = GRID_SIDE - 1;
        int[] triangles = new int[3 * 2 * cells * cells];
        int cursor = 0;
        for (int row = 0; row < cells; row++) {
            for (int column = 0; column < cells; column++) {
                int corner = row * GRID_SIDE + column;
                int[] pair = { corner, corner + 1, corner + 1 + GRID_SIDE, corner,
                    corner + 1 + GRID_SIDE, corner + GRID_SIDE };
                System.arraycopy(pair, 0, triangles, cursor, pair.length);
                cursor += pair.length;
            }
        }
        MeshTopology grid = HalfEdgeMeshEngine.buildFromIndexedMesh(gridPositions, triangles);

        int[][] travel = new int[CENTRE_SQUARE_LOOP_COLUMN_ROWS.length + 2][];
        cursor = 0;
        for (int[] cell : CENTRE_SQUARE_LOOP_COLUMN_ROWS) {
            travel[cursor++] = cell;
            if (Arrays.equals(cell, SPUR_BASE)) {
                travel[cursor++] = SPUR_TIP;
                travel[cursor++] = SPUR_BASE;
            }
        }
        int[] vertexIds = new int[travel.length];
        int[] edgeIds = new int[travel.length];
        double[] fractions = new double[travel.length];
        double[] positions = new double[3 * travel.length];
        Arrays.fill(edgeIds, MeshTopology.NONE);
        Arrays.fill(fractions, -1.0);
        for (int point = 0; point < travel.length; point++) {
            vertexIds[point] = gridVertexId(grid, travel[point]);
            positions[3 * point] = travel[point][0];
            positions[3 * point + 1] = travel[point][1];
        }
        TracedSurfacePath path = new TracedSurfacePath(positions, vertexIds, edgeIds, fractions,
                travel.length, true);

        boolean[] marks = new ConformingLoopSnap().snap(grid, path);

        int[] degreeByVertexId = new int[GRID_SIDE * GRID_SIDE];
        int marked = 0;
        for (int edgeId = 0; edgeId < marks.length; edgeId++) {
            if (marks[edgeId]) {
                marked++;
                int halfEdge = grid.edgeHalfEdge(edgeId);
                degreeByVertexId[grid.halfEdgeVertex(halfEdge)]++;
                degreeByVertexId[grid.halfEdgeEndVertex(halfEdge)]++;
            }
        }
        assertEquals(CENTRE_SQUARE_LOOP_COLUMN_ROWS.length, marked);
        assertFalse(marks[grid.edgeBetween(gridVertexId(grid, SPUR_BASE),
                gridVertexId(grid, SPUR_TIP))]);
        for (int degree : degreeByVertexId) {
            assertTrue(degree == 0 || degree == 2, Arrays.toString(degreeByVertexId));
        }
    }

    private static int gridVertexId(MeshTopology grid, int[] cell) {
        return grid.vertexIdAt(cell[1] * GRID_SIDE + cell[0]);
    }
}
