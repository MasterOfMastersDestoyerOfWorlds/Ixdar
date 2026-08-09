package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * A zero-arc collapse must leave the Euler characteristic alone, including when the arc it collapses
 * has become a loop.
 *
 * <p>Two zero arcs running between the same pair of nodes bound a degenerate bigon. Collapsing the
 * first merges one node into the other, which turns the second arc into a loop (same start and end
 * node) and shrinks the bigon to nothing. Collapsing that loop then removes an arc but — because
 * {@code mergeNodeInto} is a no-op when the two node ids coincide — no node, so the count only
 * balances if the emptied patch is retired with it.
 *
 * <p>LCBK19 covers this: Appendix A.3 notes the arc <em>"turns into a loop (same start and end
 * point), which can be contracted to a point"</em>, and §6.1 states that a zero-patch without any
 * non-zero arc, <em>"one that is supposed to be embedded onto a single point rather than a curve,
 * is already handled by the zero-arc collapse"</em>. Handling it means the collapse disposes of the
 * degenerate patch too; otherwise the surface gains a face it no longer has, which is what tears the
 * sphere's contraction after the re-route refinement lets it get this far.
 */
class ZeroArcLoopCollapseTest {

    private static final int COLUMNS = 5;
    private static final int ROWS = 3;

    @Test
    void collapsingALoopZeroArcKeepsTheComplexEulerNeutral() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);
        EmbeddedTMesh tmesh = new EmbeddedTMesh(topology);

        int startNode = tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 1, 1), false, false);
        int endNode = tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 3, 1), false, false);

        int straightArc = tmesh.addArc(EmbeddedTMesh.NONE, startNode, endNode, 0, false,
                List.of(vertex(topology, 1, 1), vertex(topology, 2, 1), vertex(topology, 3, 1)));
        int detourArc = tmesh.addArc(EmbeddedTMesh.NONE, startNode, endNode, 0, false,
                List.of(vertex(topology, 1, 1), vertex(topology, 2, 2), vertex(topology, 3, 2),
                        vertex(topology, 3, 1)));
        tmesh.addPatch(EmbeddedTMesh.NONE,
                List.of(List.of(straightArc), List.of(), List.of(detourArc), List.of()), startNode);

        ZeroArcCollapseOperator operator = new ZeroArcCollapseOperator(tmesh);
        operator.collapse(straightArc);

        assertTrue(tmesh.arcs.get(detourArc).isLoop(),
                "collapsing one of two parallel zero arcs turns the other into a loop");

        int eulerBefore = eulerCharacteristic(tmesh);
        operator.collapse(detourArc);

        assertEquals(eulerBefore, eulerCharacteristic(tmesh),
                "collapsing a loop zero arc must retire the degenerate patch it bounded, so that"
                        + " losing an arc without losing a node still balances");
    }

    /**
     * The T-mesh's Euler characteristic over its live cells.
     *
     * @param tmesh embedded T-mesh to measure
     * @return live nodes minus live arcs plus live patches
     */
    private int eulerCharacteristic(EmbeddedTMesh tmesh) {
        int liveNodes = (int) tmesh.nodes.stream().filter(node -> node.alive).count();
        int liveArcs = (int) tmesh.arcs.stream().filter(arc -> arc.alive).count();
        int livePatches = (int) tmesh.patches.stream().filter(patch -> patch.alive).count();
        return liveNodes - liveArcs + livePatches;
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
