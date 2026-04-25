package unit.procgen.dungeon.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.procgen.dungeon.physics.AabbBox;
import ixdar.procgen.dungeon.physics.CapsuleAabbTest;
import ixdar.procgen.dungeon.physics.CapsuleShape;
import ixdar.procgen.dungeon.physics.Vec3f;

public class CapsuleAabbTestTest {

    private static AabbBox unitBoxAtOrigin() {
        return new AabbBox(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f);
    }

    @Test
    public void capsuleClearlyOutsideDoesNotIntersect() {
        // Box at origin, capsule far away (X=10).
        CapsuleShape c = new CapsuleShape(10f, 0f, 0f, 0.4f, 0.2f);
        AabbBox a = unitBoxAtOrigin();
        assertFalse(CapsuleAabbTest.intersects(c, a));
        assertEquals(Vec3f.ZERO, CapsuleAabbTest.penetration(c, a));
    }

    @Test
    public void capsuleTouchingFlushAtRadiusIsNotIntersecting() {
        // Capsule center at X = 1.0 (radius=0.5), AABB face at X=0.5. Distance = 0.5 = radius.
        // Strict < => NOT intersecting. Coords chosen so float arithmetic is exact (0.5, 1.0).
        CapsuleShape c = new CapsuleShape(1.0f, 0f, 0f, 0.0f, 0.5f);
        AabbBox a = unitBoxAtOrigin();
        assertFalse(CapsuleAabbTest.intersects(c, a));
        assertEquals(Vec3f.ZERO, CapsuleAabbTest.penetration(c, a));
    }

    @Test
    public void capsuleGrazingAabbFaceProducesSmallPenetrationAlongFaceNormal() {
        // Capsule center at X = 0.6 (just inside the radius=0.2 boundary at face X=0.5).
        // Distance from segment to AABB = 0.1, radius = 0.2 -> penetration depth = 0.1.
        CapsuleShape c = new CapsuleShape(0.6f, 0f, 0f, 0.0f, 0.2f);
        AabbBox a = unitBoxAtOrigin();
        assertTrue(CapsuleAabbTest.intersects(c, a));
        Vec3f mtv = CapsuleAabbTest.penetration(c, a);
        assertEquals(0.1f, mtv.x(), 1e-5f, "MTV X should push +X");
        assertEquals(0f, mtv.y(), 1e-5f);
        assertEquals(0f, mtv.z(), 1e-5f);
    }

    @Test
    public void capsuleCenteredOnAabbProducesAxisAlignedMtv() {
        // Capsule axis exactly through AABB center -> P inside AABB. MTV picks the nearest face
        // (smallest distance from P to a face). Cube is 1x1x1, so all faces are equidistant from
        // center; tie-breaker picks -X first.
        CapsuleShape c = new CapsuleShape(0f, 0f, 0f, 0.0f, 0.2f);
        AabbBox a = unitBoxAtOrigin();
        assertTrue(CapsuleAabbTest.intersects(c, a));
        Vec3f mtv = CapsuleAabbTest.penetration(c, a);
        // Distance from P (0,0,0) to nearest face (-X at x=-0.5) = 0.5; push = 0.5 + 0.2 = 0.7
        assertEquals(-0.7f, mtv.x(), 1e-5f);
        assertEquals(0f, mtv.y(), 1e-5f);
        assertEquals(0f, mtv.z(), 1e-5f);
    }

    @Test
    public void cylinderBodyIntersectsTallObstacleAcrossSegment() {
        // Vertical capsule (halfHeight=2). AABB is short (0.1 tall) at the capsule's mid-height.
        // The closest point on segment to AABB Y range = 0 (segment overlaps AABB Y).
        CapsuleShape c = new CapsuleShape(0.6f, 0f, 0f, 2.0f, 0.2f);
        AabbBox a = new AabbBox(-0.5f, -0.05f, -0.5f, 0.5f, 0.05f, 0.5f);
        assertTrue(CapsuleAabbTest.intersects(c, a));
        Vec3f mtv = CapsuleAabbTest.penetration(c, a);
        assertEquals(0.1f, mtv.x(), 1e-5f);
        assertEquals(0f, mtv.y(), 1e-5f, "no Y push when segment overlaps AABB on Y");
    }

    @Test
    public void cornerCollisionGivesDiagonalMtv() {
        // Capsule near AABB corner at (0.5, 0, 0.5). Closest point on AABB = corner; MTV is
        // along the line from corner to capsule center, scaled to radius.
        CapsuleShape c = new CapsuleShape(0.6f, 0f, 0.6f, 0.0f, 0.2f);
        AabbBox a = unitBoxAtOrigin();
        assertTrue(CapsuleAabbTest.intersects(c, a));
        Vec3f mtv = CapsuleAabbTest.penetration(c, a);
        // Distance from (0.6,0,0.6) to corner (0.5,0,0.5) = sqrt(0.01+0+0.01) = ~0.1414
        // Penetration = 0.2 - 0.1414 = ~0.0586
        // Direction: (0.1, 0, 0.1)/0.1414 = (0.707, 0, 0.707), scaled by 0.0586
        // -> mtv.x ~ 0.0414, mtv.z ~ 0.0414
        assertTrue(Math.abs(mtv.x() - mtv.z()) < 1e-5f, "diagonal MTV should be symmetric");
        assertTrue(mtv.x() > 0f && mtv.z() > 0f, "MTV should push +X and +Z away from corner");
        assertEquals(0f, mtv.y(), 1e-5f);
    }

    @Test
    public void invalidShapesAreRejected() {
        try {
            new CapsuleShape(0, 0, 0, -1f, 0.1f);
            assertTrue(false, "negative halfHeight should reject");
        } catch (IllegalArgumentException ok) { }
        try {
            new CapsuleShape(0, 0, 0, 1f, 0f);
            assertTrue(false, "zero radius should reject");
        } catch (IllegalArgumentException ok) { }
        try {
            new AabbBox(1, 0, 0, 0, 1, 1);
            assertTrue(false, "max < min should reject");
        } catch (IllegalArgumentException ok) { }
    }
}
