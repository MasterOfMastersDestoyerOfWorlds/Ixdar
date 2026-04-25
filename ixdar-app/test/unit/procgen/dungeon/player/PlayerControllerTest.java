package unit.procgen.dungeon.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ixdar.platform.input.Keys;
import ixdar.procgen.dungeon.physics.Vec3f;
import ixdar.procgen.dungeon.player.PlayerController;
import ixdar.procgen.dungeon.values.CellType;
import ixdar.procgen.dungeon.values.TileGridValue3D;

public class PlayerControllerTest {

    /** 3x3x3 grid centered at origin (cellSize=1) with all-walkable interior. */
    private static TileGridValue3D openWorld() {
        CellType[] cells = new CellType[27];
        for (int i = 0; i < 27; i++) cells[i] = CellType.ROOM;
        return new TileGridValue3D(3, 3, 3, cells);
    }

    /** A small open arena with a floor underneath: row of HALLWAY at y=0, EMPTY above. */
    private static TileGridValue3D arenaWithFloor() {
        // 3x3x3, y=0 is solid floor (HALLWAY), y=1 and y=2 are EMPTY (sky above).
        // We want the player ON the floor, so y=0 is walkable (player walks INSIDE non-empty
        // cells per the hollow-room model). Let's make y=0 HALLWAY, y=1 ROOM (walkable),
        // y=2 EMPTY (sky/no-ceiling). Player stands at the boundary y=0->y=1 (capsule center
        // around y=0.5 + halfHeight).
        CellType[] cells = new CellType[27];
        for (int i = 0; i < 27; i++) cells[i] = CellType.EMPTY;
        for (int gz = 0; gz < 3; gz++) {
            for (int gx = 0; gx < 3; gx++) {
                cells[gx + 3 * (gz + 3 * 0)] = CellType.HALLWAY; // floor row
                cells[gx + 3 * (gz + 3 * 1)] = CellType.ROOM;    // walkable above
            }
        }
        return new TileGridValue3D(3, 3, 3, cells);
    }

    private static PlayerController spawn(TileGridValue3D grid, Vec3f at) {
        return new PlayerController(grid, 1.0f, at, 0.3f, 0.2f,
                PlayerController.DEFAULT_GRAVITY, 4.0f, 3.0f);
    }

    @Test
    public void noInputAndOpenSpaceFallsUnderGravity() {
        PlayerController p = spawn(openWorld(), new Vec3f(0f, 0f, 0f));
        Set<Integer> keys = new HashSet<>();
        Vec3f start = p.position();
        p.update(0.05f, keys, -90f);
        assertTrue(p.position().y() < start.y(), "gravity should pull player down with no input");
        assertFalse(p.grounded(), "player in open space should not be grounded");
    }

    @Test
    public void wKeyMovesForwardRelativeToCameraYaw() {
        // yaw=0 -> forward = (1, 0, 0). Pressing W should advance +X.
        PlayerController p = spawn(openWorld(), new Vec3f(0f, 0f, 0f));
        Set<Integer> keys = Set.of(Keys.W);
        Vec3f start = p.position();
        p.update(0.05f, keys, 0f);
        assertTrue(p.position().x() > start.x() + 0.01f, "W should move +X when yaw=0");
        assertEquals(0f, p.position().z(), 1e-3f, "W should not change Z when yaw=0");
    }

    @Test
    public void wKeyAtYawMinus90MovesAlongMinusZ() {
        // yaw=-90 (Camera3D default) -> forward = (cos(-90°), 0, sin(-90°)) = (0, 0, -1).
        PlayerController p = spawn(openWorld(), new Vec3f(0f, 0f, 0f));
        Set<Integer> keys = Set.of(Keys.W);
        Vec3f start = p.position();
        p.update(0.05f, keys, -90f);
        assertTrue(p.position().z() < start.z() - 0.01f, "W at yaw=-90 should move -Z");
        assertEquals(0f, p.position().x(), 1e-3f);
    }

    @Test
    public void dKeyMovesRightRelativeToCameraYaw() {
        // yaw=0, forward=(+X). Player's right = cross(forward, +Y) = (+Z).
        // D should advance +Z, not -Z (regression test for the L/R flip bug).
        PlayerController p = spawn(openWorld(), new Vec3f(0f, 0f, 0f));
        Set<Integer> keys = Set.of(Keys.D);
        Vec3f start = p.position();
        p.update(0.05f, keys, 0f);
        assertTrue(p.position().z() > start.z() + 0.01f,
                "D at yaw=0 should move +Z (player's right)");
        assertEquals(0f, p.position().x(), 1e-3f, "D should not change X when yaw=0");
    }

    @Test
    public void aKeyMovesLeftRelativeToCameraYaw() {
        PlayerController p = spawn(openWorld(), new Vec3f(0f, 0f, 0f));
        Set<Integer> keys = Set.of(Keys.A);
        Vec3f start = p.position();
        p.update(0.05f, keys, 0f);
        assertTrue(p.position().z() < start.z() - 0.01f,
                "A at yaw=0 should move -Z (player's left)");
        assertEquals(0f, p.position().x(), 1e-3f);
    }

    @Test
    public void diagonalMovementIsNotFasterThanCardinal() {
        // W+D shouldn't overshoot a single cardinal direction.
        Set<Integer> singleKey = Set.of(Keys.W);
        Set<Integer> diagonalKeys = Set.of(Keys.W, Keys.D);
        PlayerController p1 = spawn(openWorld(), Vec3f.ZERO);
        PlayerController p2 = spawn(openWorld(), Vec3f.ZERO);
        p1.update(0.05f, singleKey, 0f);
        p2.update(0.05f, diagonalKeys, 0f);
        // Horizontal speed should be the same (just different direction).
        float speed1 = (float) Math.hypot(p1.position().x(), p1.position().z());
        float speed2 = (float) Math.hypot(p2.position().x(), p2.position().z());
        assertEquals(speed1, speed2, 1e-3f, "diagonal should not be faster than cardinal");
    }

    @Test
    public void groundedAfterFallingOntoFloor() {
        // Spawn just above the floor cell (y=0 in grid -> world y in [-1.5, -0.5]).
        // Capsule with radius 0.2 + halfHeight 0.3, total bottom = center.y - 0.3 - 0.2 = -0.5.
        // To rest exactly on the floor's top (which is at world y = -0.5 since grid y=0 cell
        // spans world [-1.5, -0.5]) the capsule center should be at y=0, but in our hollow
        // model the floor's "ceiling" is at the top of the y=0 cell (-0.5). Wait — actually
        // the y=0 cell IS HALLWAY, so the player walks INSIDE it. Cell y=0 spans world y
        // [-1.5, -0.5]. Inside that cell the player rests on its floor at y=-1.5 + halfHeight
        // + radius = -1.0.
        // But the obstacle is the y=-1 cell beneath (out of grid -> obstacle wall) and the
        // capsule must sit above it. The CapsuleMover treats out-of-grid cells as solid; so
        // the floor of the world is at world y = grid bottom = -1.5.
        // Drop the player from y=0 (above expected resting position) and let gravity pull.
        TileGridValue3D world = arenaWithFloor();
        PlayerController p = new PlayerController(world, 1.0f,
                new Vec3f(0f, 0.5f, 0f),  // start 0.5 unit above resting
                0.3f, 0.2f, 9.8f, 4.0f, 3.0f);
        Set<Integer> nokeys = new HashSet<>();
        for (int frame = 0; frame < 30; frame++) {
            p.update(1f / 60f, nokeys, 0f);
        }
        assertTrue(p.grounded(), "player should be grounded after falling onto floor");
        assertEquals(0f, p.velocity().y(), 1e-3f, "vertical velocity should zero on contact");
    }

    @Test
    public void jumpRequiresGrounded() {
        // In open world (no floor) the player can't jump.
        PlayerController p = spawn(openWorld(), new Vec3f(0f, 0f, 0f));
        Set<Integer> keys = Set.of(Keys.SPACE);
        p.update(0.05f, keys, 0f);
        assertTrue(p.velocity().y() <= 0f, "no jump when not grounded");
    }

    @Test
    public void jumpProducesPositiveVerticalVelocityWhenGrounded() {
        TileGridValue3D world = arenaWithFloor();
        PlayerController p = new PlayerController(world, 1.0f,
                new Vec3f(0f, 0.5f, 0f), 0.3f, 0.2f, 9.8f, 4.0f, 3.0f);
        Set<Integer> nokeys = new HashSet<>();
        // Land on floor first.
        for (int frame = 0; frame < 30; frame++) {
            p.update(1f / 60f, nokeys, 0f);
        }
        assertTrue(p.grounded());
        // Now jump.
        Set<Integer> jumpKey = Set.of(Keys.SPACE);
        p.update(1f / 60f, jumpKey, 0f);
        assertTrue(p.velocity().y() > 0f, "jump should produce upward velocity");
        assertFalse(p.grounded(), "player should be airborne after jump");
    }

    @Test
    public void deterministicForFixedInputs() {
        TileGridValue3D world = arenaWithFloor();
        PlayerController a = new PlayerController(world, 1.0f, new Vec3f(0f, 0.5f, 0f),
                0.3f, 0.2f, 9.8f, 4.0f, 3.0f);
        PlayerController b = new PlayerController(world, 1.0f, new Vec3f(0f, 0.5f, 0f),
                0.3f, 0.2f, 9.8f, 4.0f, 3.0f);
        Set<Integer> keysW = Set.of(Keys.W);
        for (int frame = 0; frame < 60; frame++) {
            a.update(1f / 60f, keysW, 0f);
            b.update(1f / 60f, keysW, 0f);
        }
        assertEquals(a.position().x(), b.position().x(), 0f);
        assertEquals(a.position().y(), b.position().y(), 0f);
        assertEquals(a.position().z(), b.position().z(), 0f);
    }

    @Test
    public void teleportResetsVelocityAndGrounded() {
        TileGridValue3D world = arenaWithFloor();
        PlayerController p = new PlayerController(world, 1.0f, new Vec3f(0f, 0.5f, 0f),
                0.3f, 0.2f, 9.8f, 4.0f, 3.0f);
        for (int frame = 0; frame < 30; frame++) {
            p.update(1f / 60f, new HashSet<>(), 0f);
        }
        assertTrue(p.grounded());
        p.teleport(new Vec3f(1f, 5f, 1f));
        assertFalse(p.grounded(), "teleport clears grounded");
        assertEquals(0f, p.velocity().y(), 0f, "teleport zeroes velocity");
    }
}
