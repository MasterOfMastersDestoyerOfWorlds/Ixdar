package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ArcRerouter;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;

/**
 * The real multi-arc pivot bottleneck, isolated on a hand-built 9×3 grid — the case the sphere and
 * fertility contractions stall on. Two arcs b1 and b2 are incident to the collapsing node n0, and
 * the channel from n0 to n1 runs through an interior node mid, in a strip walled one triangle wide
 * (nodes above and below mid):
 *
 * <pre>
 *   row 0   .    .   wall  wall  wall  .    .    .    .
 *   row 1   .   m1 -- n0 -- mid -- n1   .    .    .    .
 *   row 2   .  m2(diag)  wall  wall  .    .    .    .    .
 *   col 0    1    2    3    4    5    6    7    8
 * </pre>
 *
 * <p>Collapsing the zero arc a = n0→mid→n1 drags both b1 and b2 onto n1. The first, b1, follows the
 * pivot through the freed channel, taking n0→mid→n1 and claiming mid. The second, b2, then finds
 * n0's direct way into the channel — the edge n0→mid — consumed and the strip one triangle wide,
 * yet the two-phase drag (pivot transit) plus the edge-split refinement still opens a lane, so b2
 * routes too. Even this one-wide-channel competition is handled; both arcs reach the survivor. It
 * is a regression guard that the pivot re-route survives a consumed narrow channel.
 */
class MultiArcPivotTest {

    private static final int COLUMNS = 9;
    private static final int ROWS = 3;

    @Test
    void bothArcsThroughAOneWideChannelReachTheSurvivor() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);
        EmbeddedTMesh tmesh = new EmbeddedTMesh(topology);

        int m1 = tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 2, 1), false, false);
        int m2 = tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 2, 0), false, false);
        int pivot = tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 3, 1), false, false);
        int survivor = tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 5, 1), false, false);
        tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 3, 0), false, false);
        tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 3, 2), false, false);
        tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 4, 0), false, false);
        tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 4, 2), false, false);

        int arcB1 = tmesh.addArc(EmbeddedTMesh.NONE, m1, pivot, 1, false,
                List.of(vertex(topology, 2, 1), vertex(topology, 3, 1)));
        int arcB2 = tmesh.addArc(EmbeddedTMesh.NONE, m2, pivot, 1, false,
                List.of(vertex(topology, 2, 0), vertex(topology, 3, 1)));
        int arcA = tmesh.addArc(EmbeddedTMesh.NONE, pivot, survivor, 0, false,
                List.of(vertex(topology, 3, 1), vertex(topology, 4, 1), vertex(topology, 5, 1)));

        ArcRerouter rerouter = new ArcRerouter(topology);
        int pivotVertex = vertex(topology, 3, 1);
        int survivorVertex = vertex(topology, 5, 1);
        List<Integer> channel = List.copyOf(tmesh.arcs.get(arcA).path.copyVertexPath);
        tmesh.setPath(arcA, List.of(survivorVertex));

        tmesh.collapseArc.dragArcEndOntoVertex(arcB1, pivotVertex, survivorVertex, rerouter, channel);
        tmesh.collapseArc.dragArcEndOntoVertex(arcB2, pivotVertex, survivorVertex, rerouter, channel);

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
    private int lastVertexOf(EmbeddedTMesh tmesh, int arcId) {
        List<Integer> path = tmesh.arcs.get(arcId).path.copyVertexPath;
        return path.get(path.size() - 1);
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
