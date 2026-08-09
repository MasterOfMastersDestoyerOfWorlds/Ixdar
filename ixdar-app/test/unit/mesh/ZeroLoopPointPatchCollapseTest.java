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
 * Collapsing a zero loop must retire the patch it pinches even when that patch keeps more than one
 * arc afterwards.
 *
 * <p>{@link ZeroLoopBigonCollapseTest} covers the patch left holding a single arc. This is the
 * general shape, and it is the one the fertility contraction stops on: a patch whose whole boundary
 * is zero arcs — here an arc into the loop's node, the loop itself, and an arc back out — so that
 * removing the loop still leaves two arcs behind. LCBK19 §6.1 classifies patches by their
 * <em>non-zero</em> arcs, not by how many arcs remain: a zero-patch <em>"without any non-zero arc,
 * one that is supposed to be embedded onto a single point rather than a curve, is already handled by
 * the zero-arc collapse"</em>. Such a patch is destined for a point whatever its arc count, so the
 * collapse owes it a retirement, and without one the surface gains a face it no longer has.
 *
 * <p>Figure 9(e)-(g) shows this is not a malformed intermediate state to be avoided: the zero-patch
 * there passes through plainly non-rectangular shapes, and the figure marks the merged corners with
 * red double-corner arcs as it goes.
 *
 * <p>The pinched patch's boundary cycle below is the one fertility reported verbatim —
 * {@code leftSides=3604z/2834Lz/-/3605z} — an arc in, the loop, nothing, an arc back out.
 *
 * <p>The far patch is a surviving quad with two non-zero arcs, as fertility's is
 * ({@code rightSides=2599/2834Lz/2829/2835z}), so neither patch is emptied and neither is left
 * holding a single arc — the two shapes the collapse already knew how to retire.
 *
 * <p>The loop's two patch ids are assigned by hand because {@link EmbeddedTMesh#addPatch} cannot
 * express them. It decides which side of an arc a patch lies on from the direction the boundary walk
 * traverses it, and a loop is traversed forwards from both sides, so both patches claim
 * {@code leftPatchId} and the second overwrites the first. Fertility never trips over this: its
 * loops are ordinary two-node arcs when their patches are built and only become loops later, when a
 * collapse merges their endpoints, by which time both sides are recorded. Building a loop as a loop
 * is what this fixture does, so it has to restore what the walk cannot see.
 */
class ZeroLoopPointPatchCollapseTest {

    private static final int COLUMNS = 8;
    private static final int ROWS = 8;

    @Test
    void collapsingAZeroLoopRetiresThePointPatchEvenWhenTwoArcsRemain() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);
        EmbeddedTMesh tmesh = new EmbeddedTMesh(topology);

        int loopNode = tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 4, 4), false, false);
        int farNode = tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 1, 4), false, false);
        int quadNode = tmesh.addNode(EmbeddedTMesh.NONE, vertex(topology, 5, 4), false, false);

        int loop = tmesh.addArc(EmbeddedTMesh.NONE, loopNode, loopNode, 0, false,
                List.of(vertex(topology, 4, 4), vertex(topology, 4, 5), vertex(topology, 4, 6),
                        vertex(topology, 5, 6), vertex(topology, 6, 6), vertex(topology, 6, 5),
                        vertex(topology, 6, 4), vertex(topology, 6, 3), vertex(topology, 6, 2),
                        vertex(topology, 5, 2), vertex(topology, 4, 2), vertex(topology, 4, 3),
                        vertex(topology, 4, 4)));
        int inbound = tmesh.addArc(EmbeddedTMesh.NONE, farNode, loopNode, 0, false,
                List.of(vertex(topology, 1, 4), vertex(topology, 2, 4), vertex(topology, 3, 4),
                        vertex(topology, 4, 4)));
        int outbound = tmesh.addArc(EmbeddedTMesh.NONE, loopNode, farNode, 0, false,
                List.of(vertex(topology, 4, 4), vertex(topology, 3, 3), vertex(topology, 2, 3),
                        vertex(topology, 1, 3), vertex(topology, 1, 4)));

        int quadOut = tmesh.addArc(EmbeddedTMesh.NONE, loopNode, quadNode, 2, false,
                List.of(vertex(topology, 4, 4), vertex(topology, 5, 4)));
        int quadBack = tmesh.addArc(EmbeddedTMesh.NONE, quadNode, loopNode, 2, false,
                List.of(vertex(topology, 5, 4), vertex(topology, 5, 5), vertex(topology, 4, 4)));

        int pinchedPatch = tmesh.addPatch(EmbeddedTMesh.NONE,
                List.of(List.of(inbound), List.of(loop), List.of(), List.of(outbound)), farNode);
        int farPatch = tmesh.addPatch(EmbeddedTMesh.NONE,
                List.of(List.of(loop), List.of(quadOut), List.of(), List.of(quadBack)), loopNode);

        tmesh.arcs.get(loop).leftPatchId = pinchedPatch;
        tmesh.arcs.get(loop).rightPatchId = farPatch;

        int eulerBefore = eulerCharacteristic(tmesh);

        new ZeroArcCollapseOperator(tmesh).collapse(loop);

        assertEquals(eulerBefore, eulerCharacteristic(tmesh),
                "a patch whose every arc is zero is destined for a single point, so collapsing the"
                        + " loop on its boundary must retire it — counting the arcs left behind is"
                        + " the wrong test, the paper classifies by non-zero arcs");
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
                int upperLeft = lowerLeft + COLUMNS;
                faces[cursor++] = lowerLeft;
                faces[cursor++] = lowerLeft + 1;
                faces[cursor++] = upperLeft + 1;
                faces[cursor++] = lowerLeft;
                faces[cursor++] = upperLeft + 1;
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
