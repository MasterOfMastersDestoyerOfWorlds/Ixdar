package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * A zero-arc collapse must leave the Euler characteristic alone, including when it disposes of a
 * whole degenerate bigon at once.
 *
 * <p>Two zero arcs running between the same pair of nodes bound a degenerate bigon. Collapsing the
 * first merges one node into the other; the second arc's far node is then the survivor, so the drag
 * embeds it onto a point and the collapse retires it and the bigon together — one node, two arcs
 * and one patch gone in a single operator, which balances.
 *
 * <p>LCBK19 covers this: §6.1 states that a zero-patch without any non-zero arc, <em>"one that is
 * supposed to be embedded onto a single point rather than a curve, is already handled by the
 * zero-arc collapse"</em>. Handling it means the collapse disposes of the degenerate patch too;
 * otherwise the surface keeps a face it no longer has.
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
        int eulerBefore = eulerCharacteristic(tmesh);
        operator.collapse(straightArc);

        assertFalse(tmesh.arcs.get(detourArc).alive,
                "the parallel arc's far node is the survivor, so it is embedded onto a point"
                        + " and retired with the bigon");

        assertEquals(eulerBefore, eulerCharacteristic(tmesh),
                "one collapse disposes of one node, two arcs and the bigon patch, which balances");
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
