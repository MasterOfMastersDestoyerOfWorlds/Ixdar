package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ArcRerouter;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;

/**
 * Minimal reproduction of the zero-arc-collapse pivot wall on a hand-built 5×3 grid — no cross
 * field, no quantization, no carve. A vertical line of arcs through the centre vertex n0 (up to
 * the top edge, down to the bottom edge) fully separates the grid's left half from its right half,
 * because a horizontal crossing would have to pass through one of the centre column's three
 * vertices, all of which are claimed as nodes.
 *
 * <pre>
 *   row 0   .    .    top   .    .
 *   row 1   .    m -- n0 -- n1   .
 *   row 2   .    .   bottom .    .
 *         col 0  1    2     3    4
 * </pre>
 *
 * <p>The zero arc a = n0→n1 is collapsed: n0 is dragged onto n1, pulling incident arc b = m→n0
 * with it, so b must re-embed from m to n1. m is in the left region and n1 in the right, and the
 * two regions touch only at the pivot n0. The collapse first frees the channel (arc a's edges),
 * then the re-route follows the arc to its new home by transiting the pivot: b becomes m→n0→n1.
 * This is what "pulling its incident arcs with it" means — the arc follows the collapsing node
 * through where the zero arc was, rather than searching for a way to n1 that never passes the
 * pivot (which the wall makes impossible).
 */
class PivotRerouteTest {

    private static final int COLUMNS = 5;
    private static final int ROWS = 3;

    @Test
    void draggingAnArcFollowsTheCollapsingNodeThroughTheChannel() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);
        EmbeddedTMesh tmesh = new EmbeddedTMesh(topology);

        int mNode = tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 1, 1), false, false);
        int pivotNode = tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 2, 1), false, false);
        int survivorNode = tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 3, 1), false, false);
        int topNode = tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 2, 0), false, false);
        int bottomNode = tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 2, 2), false, false);

        int arcB = tmesh.addArc(EmbeddedTMesh.NONE, mNode, pivotNode, 1, false,
                List.of(vertex(topology, 1, 1), vertex(topology, 2, 1)));
        int arcA = tmesh.addArc(EmbeddedTMesh.NONE, pivotNode, survivorNode, 0, false,
                List.of(vertex(topology, 2, 1), vertex(topology, 3, 1)));
        tmesh.addArc(EmbeddedTMesh.NONE, pivotNode, topNode, 1, false,
                List.of(vertex(topology, 2, 1), vertex(topology, 2, 0)));
        tmesh.addArc(EmbeddedTMesh.NONE, pivotNode, bottomNode, 1, false,
                List.of(vertex(topology, 2, 1), vertex(topology, 2, 2)));

        ArcRerouter rerouter = new ArcRerouter(topology);
        int pivotVertex = vertex(topology, 2, 1);
        int survivorVertex = vertex(topology, 3, 1);
        List<Integer> channel = List.copyOf(tmesh.arcs.get(arcA).path.copyVertexPath);

        java.util.Set<Integer> region = tmesh.arcSideRegionVertices(
                tmesh.arcs.get(arcB).path.copyVertexPath, channel);
        region.addAll(channel);
        region.add(survivorVertex);
        region.add(pivotVertex);
        tmesh.setPath(arcA, List.of(survivorVertex));
        tmesh.dragArcEndOntoVertex(arcB, pivotVertex, survivorVertex, rerouter, channel, region);

        List<Integer> path = tmesh.arcs.get(arcB).path.copyVertexPath;
        assertEquals(vertex(topology, 1, 1), path.get(0), "b still starts at m");
        assertEquals(survivorVertex, path.get(path.size() - 1), "b now ends at the survivor");
        for (int index = 1; index < path.size(); index++) {
            assertNotEquals(EmbeddedMeshTopology.UNCLAIMED,
                    topology.edgeBetween(path.get(index - 1), path.get(index)),
                    "consecutive path vertices must share a copy edge");
        }
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
