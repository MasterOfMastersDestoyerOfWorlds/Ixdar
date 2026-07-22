package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;

/**
 * A zero loop must stay collapsible when its node is <em>critical</em>.
 *
 * <p>{@code movingEndpoint} asks {@code isCollapsibleFrom} once per endpoint, but a loop's two
 * endpoints are the same node — so a critical node answers no twice and the loop is never offered.
 * A loop needs no endpoint to move: both ends already sit on one point, and its collapse is purely
 * combinatorial. Criticality constrains where a node may be embedded, not whether a loop at it may
 * be retired.
 *
 * <p>This is what strands the fertility contraction: at its fixed point all 213 surviving zero arcs
 * are loops, and all 213 sit on critical nodes, because {@code mergeNodeInto} unions criticality and
 * the surviving nodes have absorbed nearly every prescribed one.
 *
 * <p>See also: LCBK19 Section 6.1
 */
class ZeroLoopCriticalNodeCollapseTest {

    private static final int COLUMNS = 5;
    private static final int ROWS = 3;

    @Test
    void aZeroLoopOnACriticalNodeIsStillOfferedAndCollapsesEulerNeutrally() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);
        EmbeddedTMesh tmesh = new EmbeddedTMesh(topology);

        int node = tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 1, 1), true, false);

        int upperLoop = tmesh.addArc(EmbeddedTMesh.NONE, node, node, 0, false,
                List.of(vertex(topology, 1, 1), vertex(topology, 2, 1), vertex(topology, 2, 2),
                        vertex(topology, 1, 2), vertex(topology, 1, 1)));
        int lowerLoop = tmesh.addArc(EmbeddedTMesh.NONE, node, node, 0, false,
                List.of(vertex(topology, 1, 1), vertex(topology, 1, 0), vertex(topology, 0, 0),
                        vertex(topology, 0, 1), vertex(topology, 1, 1)));

        tmesh.addPatch(EmbeddedTMesh.NONE,
                List.of(List.of(upperLoop), List.of(), List.of(lowerLoop), List.of()), node);
        tmesh.addPatch(EmbeddedTMesh.NONE,
                List.of(List.of(upperLoop), List.of(), List.of(lowerLoop), List.of()), node);

        int eulerBefore = eulerCharacteristic(tmesh);
        ZeroArcCollapseOperator collapse = new ZeroArcCollapseOperator(tmesh);

        int offered = collapse.nextCollapsibleArc();
        assertNotEquals(EmbeddedTMesh.NONE, offered,
                "a zero loop needs no endpoint to move, so a critical node must not withhold it —"
                        + " withholding it strands every late-forming loop, since collapses union"
                        + " criticality onto the nodes that survive");

        collapse.collapse(offered);

        assertEquals(eulerBefore, eulerCharacteristic(tmesh),
                "collapsing a zero loop removes an arc but merges no node, so it must be paid for"
                        + " with the patch it pinches");
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
