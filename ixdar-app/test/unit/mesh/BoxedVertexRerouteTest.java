package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ArcRerouter;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedMeshTopology;

/**
 * A re-route whose target shares a triangle with the source must still succeed when the direct edge
 * between them belongs to another arc and the third corner is claimed too — the "boxed vertex" the
 * blind fallback used to force through, and the case that strands the fertility contraction once the
 * fallback is removed.
 *
 * <p>The source and target lie on the diagonal of one grid cell. That diagonal is owned by a foreign
 * arc, so the search may not walk it; both off-diagonal corners are claimed, so the search may not
 * stand on them. No free-vertex path exists and no <em>both-endpoints-claimed</em> gate lies on the
 * one shared face, so the targeted gate refinement finds nothing to split. The re-router must instead
 * split one arc-free edge of that shared face — minting a midpoint adjacent to both ends — to thread
 * the hop. This is still a split on the committed face, not a blind one.
 *
 * <p>See also: LCBK19 Section 6.1
 */
class BoxedVertexRerouteTest {

    private static final int COLUMNS = 3;
    private static final int ROWS = 2;
    private static final int FOREIGN_ARC = 99;

    @Test
    void aTargetBehindAnArcClaimedFaceEdgeStillRoutes() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);

        int source = vertex(topology, 0, 0);
        int target = vertex(topology, 1, 1);
        int lowerRight = vertex(topology, 1, 0);
        int upperLeft = vertex(topology, 0, 1);

        topology.ownerArcByCopyEdge[topology.edgeBetween(source, target)] = FOREIGN_ARC;
        topology.ownerArcByCopyVertex[lowerRight] = FOREIGN_ARC;
        topology.ownerArcByCopyVertex[upperLeft] = FOREIGN_ARC;

        ArcRerouter rerouter = new ArcRerouter(topology);
        List<Integer> routed = new ArrayList<>();
        routed.add(source);

        boolean reached = rerouter.tryRoute(EmbeddedMeshTopology.UNCLAIMED, routed, source, target,
                rerouter.freshCorridor(), EmbeddedMeshTopology.UNCLAIMED,
                ArcRerouter.REFINE_ROUND_CAP);

        assertTrue(reached, "a target one arc-claimed diagonal away, with both other corners claimed,"
                + " must still be reached by splitting an arc-free edge of the shared face");
        assertTrue(routed.get(routed.size() - 1) == target, "the routed path must end at the target");
    }

    /**
     * Builds a {@link #COLUMNS}×{@link #ROWS} triangulated grid on the z = 0 plane, whose lower-left
     * cell splits along the source-to-target diagonal.
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
