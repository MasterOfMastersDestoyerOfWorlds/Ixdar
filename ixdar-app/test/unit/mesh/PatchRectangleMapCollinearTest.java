package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.gridmap.PatchRectangleMap;

/**
 * The configuration behind patch 40's exactly-degenerate triangles: an interior
 * vertex whose whole neighbourhood lies on one side of the rectangle. Every
 * convex combination of points sharing a coordinate shares it too, so no choice
 * of Tutte weights can lift the vertex off that side's line.
 */
class PatchRectangleMapCollinearTest {

    /** Side of the square rectangle the region is mapped onto. */
    private static final double EXTENT = 2.0;

    /** Dense index of the interior vertex fanned against the right side. */
    private static final int PINNED_INTERIOR = 8;

    /**
     * An interior vertex fanned onto a single side collapses onto that side, and
     * the map reports it rather than blaming the fold on numerics.
     */
    @Test
    void interiorVertexFannedOntoOneSideCollapsesOntoIt() {
        PatchRectangleMap map = rightSideFanRegion().build();

        assertEquals(EXTENT, map.rectangleU[PINNED_INTERIOR], Math.ulp(EXTENT),
                "an interior vertex whose neighbours all sit on the right side is a convex"
                        + " combination of points at u=" + EXTENT + ", so it lands there too");
        assertEquals(1, map.collinearNeighbourhoodCount(),
                "the map should name the vertex its neighbourhood pins onto a side");
        assertTrue(map.rectangleU[PINNED_INTERIOR] <= EXTENT,
                "the collapsed vertex may not be pushed outside the rectangle");
    }

    /**
     * A square region whose right side carries a three-vertex fan around one
     * interior vertex, closed by a chord along that side.
     *
     * <p>
     * Boundary loop, counter-clockwise from the origin: {@code 0..7}. Dense
     * {@link #PINNED_INTERIOR} is the interior vertex, adjacent only to the three
     * right-side vertices {@code 2, 3, 4}.
     *
     * @return the unsolved map
     */
    private PatchRectangleMap rightSideFanRegion() {
        Vector3f[] positions = {
            new Vector3f(0f, 0f, 0f),
            new Vector3f(1f, 0f, 0f),
            new Vector3f(2f, 0f, 0f),
            new Vector3f(2f, 1f, 0f),
            new Vector3f(2f, 2f, 0f),
            new Vector3f(1f, 2f, 0f),
            new Vector3f(0f, 2f, 0f),
            new Vector3f(0f, 1f, 0f),
            new Vector3f(1.9f, 1f, 0f),
        };
        int[][] triangles = {
            { 2, 3, PINNED_INTERIOR },
            { 3, 4, PINNED_INTERIOR },
            { 2, PINNED_INTERIOR, 4 },
            { 0, 1, 2 },
            { 0, 2, 4 },
            { 0, 4, 5 },
            { 0, 5, 6 },
            { 0, 6, 7 },
        };
        int[] boundaryLoop = { 0, 1, 2, 3, 4, 5, 6, 7 };
        int[][] sideBreakLoopIndex = { { 0, 2 }, { 2, 4 }, { 4, 6 }, { 6, 0 } };
        int[][] sideBreakOffset = { { 0, 2 }, { 0, 2 }, { 0, 2 }, { 0, 2 } };
        return new PatchRectangleMap(positions, triangles, boundaryLoop, sideBreakLoopIndex,
                sideBreakOffset, EXTENT, EXTENT, null);
    }
}
