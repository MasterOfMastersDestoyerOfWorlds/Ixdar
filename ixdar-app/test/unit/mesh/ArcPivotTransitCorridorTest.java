package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ArcRerouter;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedMeshTopology;

/**
 * Reproduces the sphere's arc-376 stall: the re-route refinement aims at a corridor from the arc's
 * body straight to the survivor, but no such corridor exists — the passage runs <em>through</em> the
 * collapsing node.
 *
 * <p>The collapsing node is a cut vertex. Claimed arc edges radiating from it divide its fan into
 * sectors that meet only at the vertex itself and never across an edge, so a face walk cannot get
 * from one sector to another. The arc's body lies in one sector and the survivor in another. The
 * sphere's failure diagnostic shows exactly this shape, and it looks like a violation of
 * transitivity until the cut vertex is accounted for:
 *
 * <pre>
 *   body  -&gt; pivot  : faceCorridor=15,  bothClaimed=0     (already walkable)
 *   pivot -&gt; target : faceCorridor=150, bothClaimed=116   (needs refinement)
 *   body  -&gt; target : none                                (sealed)
 * </pre>
 *
 * <p>A <em>vertex</em> path may still pass through the node, because the arc being dragged is
 * incident to it — that is what {@code passThrough} permits, and it is LCBK19's <em>"pulling its
 * incident arcs with it"</em>. So the passage is two legs, body→pivot then pivot→target, and the
 * blocking gates all live on the second leg. Asking for a single body→target corridor finds nothing,
 * so the targeted refinement does nothing and the search falls back to splitting arbitrary edges,
 * which cannot open the gates.
 *
 * <p>The fixture below builds that shape in the small: a sealing wall of claimed edges that no face
 * walk may cross, with the pivot sitting on it so its fan spans both sides, and a second gated wall
 * between the pivot and the target whose crossings have both endpoints claimed and so need
 * refinement. Routing start→target while allowed to transit the pivot must succeed.
 */
class ArcPivotTransitCorridorTest {

    private static final int COLUMNS = 30;
    private static final int ROWS = 61;
    private static final int SEAL_COLUMN = 15;
    private static final int GATE_COLUMN = 22;
    private static final int MIDDLE_ROW = 30;
    private static final int GAP_ROW = 30;
    private static final int CLAIM_MARKER = 7;

    @Test
    void reRouteTransitsThePivotAndRefinesTheFarLeg() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);

        for (int row = 0; row < ROWS; row++) {
            topology.ownerArcByCopyVertex[vertex(topology, SEAL_COLUMN, row)] = CLAIM_MARKER;
            topology.ownerArcByCopyVertex[vertex(topology, GATE_COLUMN, row)] = CLAIM_MARKER;
        }
        for (int row = 0; row < ROWS - 1; row++) {
            topology.ownerArcByCopyEdge[topology.edgeBetween(vertex(topology, SEAL_COLUMN, row),
                    vertex(topology, SEAL_COLUMN, row + 1))] = CLAIM_MARKER;
            if (row != GAP_ROW) {
                topology.ownerArcByCopyEdge[topology.edgeBetween(vertex(topology, GATE_COLUMN, row),
                        vertex(topology, GATE_COLUMN, row + 1))] = CLAIM_MARKER;
            }
        }

        int startVertex = vertex(topology, 0, MIDDLE_ROW);
        int pivotVertex = vertex(topology, SEAL_COLUMN, MIDDLE_ROW);
        int targetVertex = vertex(topology, COLUMNS - 1, MIDDLE_ROW);

        Set<Integer> corridor = unclaimedVertices(topology);
        corridor.add(pivotVertex);
        corridor.add(targetVertex);
        List<Integer> routed = new ArrayList<>();
        boolean reached = new ArcRerouter(topology).tryRoute(CLAIM_MARKER, routed, startVertex,
                targetVertex, corridor, pivotVertex, ArcRerouter.REFINE_ROUND_CAP);

        assertTrue(reached, "a route that transits the collapsing node must be found: the blocking"
                + " gates lie on the pivot-to-target leg, not on any body-to-target corridor");
        assertTrue(routed.contains(pivotVertex),
                "the route reaches the target by passing through the collapsing node");
    }

    /**
     * The near leg of the transit must be walkable too, even when the caller's corridor never
     * admitted it.
     *
     * <p>{@link EmbeddedTMesh#dragArcEndOntoVertex} builds its corridor from the arc's own old path,
     * the vacated channel and the channel's unclaimed component — it does not include the region the
     * arc's body sits in. On the sphere that is what stops arc 429: the pivot-to-target leg is fully
     * admissible and its gates are split, but the body-to-pivot leg needs no splits at all and still
     * fails, because 18 free vertices along it are outside the corridor and the search may not stand
     * on them. Refining a passage is not enough; the search has to be allowed to walk the passage
     * that was refined.
     */
    @Test
    void reRouteAdmitsThePassageItRefinedIntoTheCorridor() {
        HalfEdgeMesh grid = buildGrid();
        EmbeddedMeshTopology topology = new EmbeddedMeshTopology(grid);

        for (int row = 0; row < ROWS; row++) {
            topology.ownerArcByCopyVertex[vertex(topology, SEAL_COLUMN, row)] = CLAIM_MARKER;
            topology.ownerArcByCopyVertex[vertex(topology, GATE_COLUMN, row)] = CLAIM_MARKER;
        }
        for (int row = 0; row < ROWS - 1; row++) {
            topology.ownerArcByCopyEdge[topology.edgeBetween(vertex(topology, SEAL_COLUMN, row),
                    vertex(topology, SEAL_COLUMN, row + 1))] = CLAIM_MARKER;
            if (row != GAP_ROW) {
                topology.ownerArcByCopyEdge[topology.edgeBetween(vertex(topology, GATE_COLUMN, row),
                        vertex(topology, GATE_COLUMN, row + 1))] = CLAIM_MARKER;
            }
        }

        int startVertex = vertex(topology, 0, MIDDLE_ROW);
        int pivotVertex = vertex(topology, SEAL_COLUMN, MIDDLE_ROW);
        int targetVertex = vertex(topology, COLUMNS - 1, MIDDLE_ROW);

        Set<Integer> corridor = new HashSet<>();
        for (int row = 0; row < ROWS; row++) {
            for (int column = SEAL_COLUMN + 1; column < COLUMNS; column++) {
                int copyVertex = vertex(topology, column, row);
                if (topology.ownerArcByCopyVertex[copyVertex] == EmbeddedMeshTopology.UNCLAIMED) {
                    corridor.add(copyVertex);
                }
            }
        }
        corridor.add(startVertex);
        corridor.add(pivotVertex);
        corridor.add(targetVertex);

        List<Integer> routed = new ArrayList<>();
        boolean reached = new ArcRerouter(topology).tryRoute(CLAIM_MARKER, routed, startVertex,
                targetVertex, corridor, pivotVertex, ArcRerouter.REFINE_ROUND_CAP);

        assertTrue(reached, "the near leg of the transit must be admitted to the corridor: it needs"
                + " no splits, but the search may not stand on it unless the passage is admitted");
    }

    /**
     * Every copy vertex owned by neither a node nor an arc — the corridor a re-route is allowed.
     *
     * @param topology working copy carrying the claim arrays
     * @return the unclaimed copy vertices
     */
    private Set<Integer> unclaimedVertices(EmbeddedMeshTopology topology) {
        Set<Integer> unclaimed = new HashSet<>();
        for (int vertex = 0; vertex < topology.ownerArcByCopyVertex.length; vertex++) {
            if (topology.ownerArcByCopyVertex[vertex] == EmbeddedMeshTopology.UNCLAIMED
                    && topology.ownerNodeByCopyVertex[vertex] == EmbeddedMeshTopology.UNCLAIMED) {
                unclaimed.add(vertex);
            }
        }
        return unclaimed;
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
