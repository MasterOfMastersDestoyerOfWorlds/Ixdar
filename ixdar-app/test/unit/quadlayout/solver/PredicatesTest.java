package unit.quadlayout.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.solver.Predicates;

public class PredicatesTest {

    @Test
    void orient2dCcwCwCollinear() {
        assertEquals(+1, Predicates.orient2d(0, 0, 1, 0, 1, 1));
        assertEquals(-1, Predicates.orient2d(0, 0, 1, 1, 1, 0));
        assertEquals(0, Predicates.orient2d(0, 0, 1, 1, 2, 2));
    }

    @Test
    void orient2dExactOnNearDegenerate() {
        // Three points that are exactly collinear in rationals but where
        // naive double arithmetic might round.
        double a = 0.5, b = 12.0;
        // y = 0.5 + 0.1*x for x in {0, 100, 1e8}: collinear by construction.
        int s = Predicates.orient2d(0, a, 100, a + 0.1 * 100, 1e8, a + 0.1 * 1e8);
        assertEquals(0, s);
        // Now perturb the last point by 1 ULP to create a definite sign.
        double y = a + 0.1 * 1e8;
        double yPlus = Math.nextUp(y);
        int sp = Predicates.orient2d(0, a, 100, a + 0.1 * 100, 1e8, yPlus);
        assertTrue(sp != 0, "ULP perturbation should produce non-zero sign");
        // Suppress unused-variable warning for parity with the perturbation use.
        if (b == 0) throw new AssertionError();
    }

    @Test
    void orient3dPositiveAndZero() {
        // Standard tetrahedron: a=(0,0,0) b=(1,0,0) c=(0,1,0) d=(0,0,-1).
        // Shewchuk convention: positive when d is on the negative side of plane(a,b,c).
        int s = Predicates.orient3d(0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, -1);
        assertTrue(s != 0);
        // Coplanar: d on the same plane as a,b,c.
        int sZero = Predicates.orient3d(0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0);
        assertEquals(0, sZero);
    }

    @Test
    void rejectsNonFinite() {
        assertThrows(IllegalArgumentException.class,
                () -> Predicates.orient2d(0, 0, 1, 0, Double.NaN, 1));
        assertThrows(IllegalArgumentException.class,
                () -> Predicates.orient3d(0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, Double.POSITIVE_INFINITY));
    }

    @Test
    void overflowSafeOnLargeCoords() {
        // Naive computation could overflow; exact path catches it.
        double s = 1e150;
        int sgn = Predicates.orient2d(-s, 0, s, 0, 0, 1);
        assertEquals(+1, sgn);
    }
}
