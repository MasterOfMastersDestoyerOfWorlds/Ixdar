package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * Collapsing a zero arc that is <em>already</em> a loop must still leave the Euler characteristic
 * alone, including when the patch it bounds keeps an arc afterwards.
 *
 * <p>An ordinary zero-arc collapse pays for the arc it removes with the node it merges away. A loop
 * has only one node, so {@code mergeNodeInto} is a no-op and there is no node to pay with — the arc
 * must be paid for with a patch instead. {@link ZeroArcLoopCollapseTest} covers the case where that
 * patch is left completely empty and is retired on those grounds. This covers the case that is not
 * empty: two zero loops at one node bound a bigon between them, so removing one leaves the other
 * behind and the patch survives a test for emptiness while no longer being a patch of anything.
 *
 * <p>LCBK19 §6.1 puts this case squarely on this operator: a zero-patch <em>"without any non-zero
 * arc, one that is supposed to be embedded onto a single point rather than a curve, is already
 * handled by the zero-arc collapse"</em> — a bigon of two zero loops is exactly such a patch, and
 * operator (3) will not take it, since that operator is defined for a bigon of two <em>non-zero</em>
 * arcs. The collapse therefore has to dispose of the degenerate patch along with the arc, which is
 * the identification of opposite sides that [Myles et al. 2014] describes.
 *
 * <p>This is the configuration that stops the fertility contraction, reported verbatim as
 * {@code arc 2572 [already a loop at node 572 leftPatch=1099 rightPatch=1098
 * leftSides=2572Lz/-/2573Lz/- ...]}.
 */
class ZeroLoopBigonCollapseTest {

    private static final int COLUMNS = 5;
    private static final int ROWS = 3;

    @Test
    void collapsingOneOfTwoZeroLoopsBoundingABigonKeepsTheComplexEulerNeutral() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);
        EmbeddedTMesh tmesh = new EmbeddedTMesh(topology);

        int node = tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 1, 1), false, false);

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

        new ZeroArcCollapseOperator(tmesh).collapse(upperLoop);

        assertEquals(eulerBefore, eulerCharacteristic(tmesh),
                "collapsing a zero loop removes an arc but merges no node, so it must retire the"
                        + " degenerate patch it bounded — leaving the patch alive because it still"
                        + " holds the opposite loop gains the surface a face it no longer has");
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
