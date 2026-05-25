package ixdar.geometry.mesh.quadlayout.motorcycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for chart-space predicates used by the motorcycle graph walker.
 */
class ChartWalkerTest {

    @Test
    void orient2dLeftTurnIsPositive() {
        double area = UvPredicates.orient2d(0, 0, 1, 0, 0, 1);
        assertTrue(area > 0.0);
    }

    @Test
    void segmentIntersectionFindsCrossing() {
        double[] hit = UvPredicates.segmentSegmentIntersection(
                0, 0, 2, 0,
                1, -1, 1, 1);
        assertNotNull(hit);
        assertEquals(1.0, hit[2], 1.0e-6);
        assertEquals(0.0, hit[3], 1.0e-6);
    }

    @Test
    void raySegmentFindsForwardHit() {
        double[] hit = UvPredicates.raySegmentIntersection(
                0, 0.5, 1, 0,
                1, 0, 1, 1, UvPredicates.ORIENT_COLLINEAR_EPSILON);
        assertNotNull(hit);
        assertEquals(1.0, hit[0], 1.0e-6);
        assertEquals(1.0, hit[1], 1.0e-6);
        assertEquals(0.5, hit[2], 1.0e-6);
    }
}
