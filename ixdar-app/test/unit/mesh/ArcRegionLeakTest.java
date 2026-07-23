package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;

/**
 * The region an arc may be re-routed over must be exactly its own two patches. When a neighbouring
 * arc has been lifted — as happens while a collapse drags the moving node's several incident arcs one
 * by one — the free flood that computes the region must not escape through that gap into a third
 * patch. If it does, a shortest re-route can leave the two patches and the layout tears.
 *
 * <p>Four spokes meet at a centre node, cutting a grid into four quadrant patches. Lifting the north
 * spoke merges the two north quadrants in the copy mesh. The east spoke still borders only the
 * north-east and south-east patches, so its region must contain nothing from the north-west patch —
 * yet the flood, walling only the collapse channel, leaks through the lifted north spoke into it.
 */
class ArcRegionLeakTest {

    private static final int SIDE = 5;
    private static final int CENTRE = 2;
    private static final int LOW = 0;
    private static final int HIGH = 4;
    private static final int NORTHWEST_INTERIOR_COLUMN = 1;
    private static final int NORTHWEST_INTERIOR_ROW = 3;

    @Test
    void anArcsRegionDoesNotLeakThroughALiftedNeighbourIntoAThirdPatch() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);
        EmbeddedTMesh tmesh = new EmbeddedTMesh(topology);

        int centre = node(tmesh, topology, CENTRE, CENTRE);
        int north = node(tmesh, topology, CENTRE, HIGH);
        int south = node(tmesh, topology, CENTRE, LOW);
        int east = node(tmesh, topology, HIGH, CENTRE);
        int west = node(tmesh, topology, LOW, CENTRE);
        int northeast = node(tmesh, topology, HIGH, HIGH);
        int northwest = node(tmesh, topology, LOW, HIGH);
        int southeast = node(tmesh, topology, HIGH, LOW);
        int southwest = node(tmesh, topology, LOW, LOW);

        int spokeN = column(tmesh, topology, centre, north, CENTRE, CENTRE, HIGH);
        int spokeS = column(tmesh, topology, centre, south, CENTRE, CENTRE, LOW);
        int spokeE = row(tmesh, topology, centre, east, CENTRE, CENTRE, HIGH);
        int spokeW = row(tmesh, topology, centre, west, CENTRE, CENTRE, LOW);
        int northToNortheast = row(tmesh, topology, north, northeast, HIGH, CENTRE, HIGH);
        int northToNorthwest = row(tmesh, topology, north, northwest, HIGH, CENTRE, LOW);
        int southToSoutheast = row(tmesh, topology, south, southeast, LOW, CENTRE, HIGH);
        int southToSouthwest = row(tmesh, topology, south, southwest, LOW, CENTRE, LOW);
        int eastToNortheast = column(tmesh, topology, east, northeast, HIGH, CENTRE, HIGH);
        int eastToSoutheast = column(tmesh, topology, east, southeast, HIGH, CENTRE, LOW);
        int westToNorthwest = column(tmesh, topology, west, northwest, LOW, CENTRE, HIGH);
        int westToSouthwest = column(tmesh, topology, west, southwest, LOW, CENTRE, LOW);

        tmesh.addPatch(EmbeddedTMesh.NONE, List.of(List.of(spokeN), List.of(northToNortheast),
                List.of(eastToNortheast), List.of(spokeE)), centre);
        tmesh.addPatch(EmbeddedTMesh.NONE, List.of(List.of(spokeW), List.of(westToNorthwest),
                List.of(northToNorthwest), List.of(spokeN)), centre);
        tmesh.addPatch(EmbeddedTMesh.NONE, List.of(List.of(spokeE), List.of(eastToSoutheast),
                List.of(southToSoutheast), List.of(spokeS)), centre);
        tmesh.addPatch(EmbeddedTMesh.NONE, List.of(List.of(spokeS), List.of(southToSouthwest),
                List.of(westToSouthwest), List.of(spokeW)), centre);

        int northwestInterior = vertex(topology, NORTHWEST_INTERIOR_COLUMN, NORTHWEST_INTERIOR_ROW);
        List<Integer> noChannel = new ArrayList<>();
        List<Integer> spokeEPath = tmesh.arcs.get(spokeE).path.copyVertexPath;

        assertFalse(tmesh.arcSideRegionVertices(spokeEPath, noChannel).contains(northwestInterior),
                "on the intact mesh the east arc's region is its own two patches only — this is the"
                        + " region the collapse pre-computes, before any drag lifts a neighbour");

        liftArc(topology, tmesh.arcs.get(spokeN).path.copyVertexPath);

        assertTrue(tmesh.arcSideRegionVertices(spokeEPath, noChannel).contains(northwestInterior),
                "once a neighbour spoke is lifted the flood leaks through the gap into the north-west"
                        + " patch — which is exactly why the region must be taken before dragging,"
                        + " not during");
    }

    /**
     * Unclaims an arc's edges and interior vertices, as a drag does before re-routing it.
     *
     * @param topology working copy carrying the claim arrays
     * @param path     the arc's copy-vertex path
     */
    private void liftArc(EmbeddedMeshTopology topology, List<Integer> path) {
        for (int index = 1; index < path.size(); index++) {
            topology.ownerArcByCopyEdge[topology.edgeBetween(path.get(index - 1), path.get(index))] =
                    EmbeddedMeshTopology.UNCLAIMED;
        }
        for (int index = 1; index < path.size() - 1; index++) {
            topology.ownerArcByCopyVertex[path.get(index)] = EmbeddedMeshTopology.UNCLAIMED;
        }
    }

    /**
     * Registers a node at a grid position.
     *
     * @param tmesh    T-mesh to add to
     * @param topology working copy over the grid
     * @param column   grid column
     * @param row      grid row
     * @return the node id
     */
    private int node(EmbeddedTMesh tmesh, EmbeddedMeshTopology topology, int column, int row) {
        return tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, column, row), false, false);
    }

    /**
     * Adds a vertical arc between two nodes at a fixed column.
     *
     * @param tmesh    T-mesh to add to
     * @param topology working copy over the grid
     * @param from     start node
     * @param to       end node
     * @param col      grid column the arc runs up
     * @param fromRow  start row
     * @param toRow    end row
     * @return the arc id
     */
    private int column(EmbeddedTMesh tmesh, EmbeddedMeshTopology topology, int from, int to, int col,
            int fromRow, int toRow) {
        List<Integer> path = new ArrayList<>();
        int step = Integer.signum(toRow - fromRow);
        for (int row = fromRow; row != toRow + step; row += step) {
            path.add(vertex(topology, col, row));
        }
        return tmesh.addArc(EmbeddedTMesh.NONE, from, to, 1, false, path);
    }

    /**
     * Adds a horizontal arc between two nodes at a fixed row.
     *
     * @param tmesh    T-mesh to add to
     * @param topology working copy over the grid
     * @param from     start node
     * @param to       end node
     * @param row      grid row the arc runs along
     * @param fromCol  start column
     * @param toCol    end column
     * @return the arc id
     */
    private int row(EmbeddedTMesh tmesh, EmbeddedMeshTopology topology, int from, int to, int row,
            int fromCol, int toCol) {
        List<Integer> path = new ArrayList<>();
        int step = Integer.signum(toCol - fromCol);
        for (int col = fromCol; col != toCol + step; col += step) {
            path.add(vertex(topology, col, row));
        }
        return tmesh.addArc(EmbeddedTMesh.NONE, from, to, 1, false, path);
    }

    /**
     * Builds a {@link #SIDE}×{@link #SIDE} triangulated grid on the z = 0 plane.
     *
     * @return the grid as a half-edge mesh
     */
    private HalfEdgeMesh buildGrid() {
        float[] positions = new float[SIDE * SIDE * 3];
        for (int row = 0; row < SIDE; row++) {
            for (int column = 0; column < SIDE; column++) {
                int base = (row * SIDE + column) * 3;
                positions[base] = column;
                positions[base + 1] = row;
                positions[base + 2] = 0f;
            }
        }
        int[] faces = new int[(SIDE - 1) * (SIDE - 1) * 2 * 3];
        int cursor = 0;
        for (int row = 0; row < SIDE - 1; row++) {
            for (int column = 0; column < SIDE - 1; column++) {
                int lowerLeft = row * SIDE + column;
                int lowerRight = lowerLeft + 1;
                int upperLeft = lowerLeft + SIDE;
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
        return topology.copyVertexForSourceVertexId(row * SIDE + column);
    }
}
