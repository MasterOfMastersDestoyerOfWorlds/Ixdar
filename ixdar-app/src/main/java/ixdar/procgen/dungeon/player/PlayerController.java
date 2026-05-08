package ixdar.procgen.dungeon.player;

import java.util.Set;

import ixdar.platform.input.Keys;
import ixdar.procgen.dungeon.physics.CapsuleMover;
import ixdar.procgen.dungeon.physics.CapsuleShape;
import ixdar.procgen.dungeon.physics.Vec3f;
import ixdar.procgen.dungeon.values.TileGridValue3D;

/**
 * Walkable first-person character driven by WASD + Space + camera yaw. One {@link #update} call
 * per frame integrates one physics step:
 *
 * <ol>
 *   <li>Compute desired horizontal velocity from WASD relative to the camera yaw — Y component
 *       stays zero so looking up doesn't make the player fly.</li>
 *   <li>Add gravity to vertical velocity. If the player jumped this frame and was grounded,
 *       set vertical velocity to {@code jumpSpeed} instead.</li>
 *   <li>Integrate position via {@link CapsuleMover#moveAndSlide} so collisions against EMPTY /
 *       out-of-grid cells are resolved.</li>
 *   <li>Detect grounded by comparing desired vs actual vertical motion: if we wanted to fall
 *       but the floor pushed us back up, we're standing on something.</li>
 * </ol>
 *
 * <p>The camera's orientation is read each frame via {@code cameraYawDegrees}; the controller
 * does NOT modify camera yaw or pitch (mouse-look is owned by the existing
 * {@code FlyCamMouseTrap}).
 */
public class PlayerController {
    public static final float NUM_0 = 0f;
    public static final float NUM_1e_6 = 1e-6f;
    public static final float NUM_1 = 1f;
    public static final float NUM_1e_4 = 1e-4f;
    public static final float NUM_360 = 360f;
    public static final float NUM_180 = 180f;

    /** Default gravity in world-units / sec². Suitable for unit-scale dungeons. */
    public static final float DEFAULT_GRAVITY = 9.8f;

    /** Maximum angular speed when rotating to face the movement direction (Dark Souls feel). */
    private static final float TURN_RATE_DEG_PER_SEC = 720f;

    private final TileGridValue3D grid;
    private final float cellSize;
    private final float halfHeight;
    private final float radius;
    private final float gravity;
    private final float jumpSpeed;
    private final float moveSpeed;

    private Vec3f position;
    private Vec3f velocity;
    private boolean grounded;
    private float facingYawDegrees;

    /**
     * TODO: document {@code PlayerController}.
     *
     * @param grid TODO: describe
     * @param cellSize TODO: describe
     * @param spawnCenter TODO: describe
     * @param halfHeight TODO: describe
     * @param radius TODO: describe
     * @param gravity TODO: describe
     * @param jumpSpeed TODO: describe
     * @param moveSpeed TODO: describe
     */
    public PlayerController(TileGridValue3D grid, float cellSize, Vec3f spawnCenter,
                            float halfHeight, float radius,
                            float gravity, float jumpSpeed, float moveSpeed) {
        this.grid = grid;
        this.cellSize = cellSize;
        this.halfHeight = halfHeight;
        this.radius = radius;
        this.gravity = gravity;
        this.jumpSpeed = jumpSpeed;
        this.moveSpeed = moveSpeed;
        this.position = spawnCenter;
        this.velocity = Vec3f.ZERO;
        this.grounded = false;
        this.facingYawDegrees = NUM_0;
    }

    /**
     * TODO: document {@code position}.
     *
     * @return TODO: describe
     */
    public Vec3f position() { return position; }
    /**
     * TODO: document {@code velocity}.
     *
     * @return TODO: describe
     */
    public Vec3f velocity() { return velocity; }
    /**
     * TODO: document {@code grounded}.
     *
     * @return TODO: describe
     */
    public boolean grounded() { return grounded; }

    /**
     * Eye position used to drive the camera: top of the cylinder body, just below the head sphere.
     *
     * @return TODO: describe
     */
    public Vec3f cameraEyePosition() {
        return new Vec3f(position.x(), position.y() + halfHeight, position.z());
    }

    /**
     * Integrate one frame.
     *
     * @param dt               seconds since the last frame
     * @param pressedKeys      currently-held GLFW key codes
     * @param cameraYawDegrees camera's yaw in degrees (matches Camera3D.yaw)
     */
    public void update(float dt, Set<Integer> pressedKeys, float cameraYawDegrees) {
        // Horizontal direction relative to camera yaw. yaw=-90 (Camera3D default looking -Z)
        // gives forward = (0, 0, -1). yaw 0 => forward = (1, 0, 0). The right vector is
        // cross(forward, worldUp=+Y) which matches Camera3D.updateCameraVectors:
        //   forward = (cos yaw, 0, sin yaw)
        //   right   = (-sin yaw, 0, cos yaw)
        // The previous formula had right's sign flipped, so D moved the player to the left.
        double yawRad = Math.toRadians(cameraYawDegrees);
        float fwdX = (float) Math.cos(yawRad);
        float fwdZ = (float) Math.sin(yawRad);
        float rightX = (float) -Math.sin(yawRad);
        float rightZ = (float) Math.cos(yawRad);

        float horizX = NUM_0, horizZ = NUM_0;
        if (pressedKeys.contains(Keys.W)) { horizX += fwdX;   horizZ += fwdZ; }
        if (pressedKeys.contains(Keys.S)) { horizX -= fwdX;   horizZ -= fwdZ; }
        if (pressedKeys.contains(Keys.D)) { horizX += rightX; horizZ += rightZ; }
        if (pressedKeys.contains(Keys.A)) { horizX -= rightX; horizZ -= rightZ; }
        // Normalize so diagonal movement isn't faster.
        float horizLen = (float) Math.sqrt(horizX * horizX + horizZ * horizZ);
        if (horizLen > NUM_1e_6) {
            float invLen = NUM_1 / horizLen;
            float dirX = horizX * invLen;
            float dirZ = horizZ * invLen;
            horizX = dirX * moveSpeed;
            horizZ = dirZ * moveSpeed;
            // Dark Souls turning: rotate facing toward the movement direction at a bounded rate.
            float targetYaw = (float) Math.toDegrees(Math.atan2(dirZ, dirX));
            float diff = wrapAngle180(targetYaw - facingYawDegrees);
            float maxStep = TURN_RATE_DEG_PER_SEC * dt;
            float clamped = Math.max(-maxStep, Math.min(maxStep, diff));
            facingYawDegrees = wrapAngle180(facingYawDegrees + clamped);
        }

        // Vertical velocity: gravity each frame, jump on press while grounded.
        float vy = velocity.y() - gravity * dt;
        if (grounded && pressedKeys.contains(Keys.SPACE)) {
            vy = jumpSpeed;
        }

        velocity = new Vec3f(horizX, vy, horizZ);

        // Integrate.
        Vec3f delta = velocity.scale(dt);
        CapsuleShape capsule = new CapsuleShape(
                position.x(), position.y(), position.z(), halfHeight, radius);
        Vec3f newPos = CapsuleMover.moveAndSlide(capsule, delta, grid, cellSize);

        // Grounded detection: we wanted to fall (delta.y < 0) but actually moved up relative to
        // the requested motion -> the floor pushed us. Threshold accounts for sub-step rounding.
        Vec3f actualMotion = newPos.sub(position);
        boolean wasFalling = delta.y() < NUM_0;
        boolean blockedDownward = actualMotion.y() > delta.y() + NUM_1e_4;
        boolean newlyGrounded = wasFalling && blockedDownward;
        if (newlyGrounded) {
            // Zero downward velocity so gravity doesn't accumulate while standing still.
            velocity = new Vec3f(velocity.x(), NUM_0, velocity.z());
        }
        grounded = newlyGrounded;
        position = newPos;
    }

    /**
     * Reposition the player at a new spawn point and reset velocity / grounded state.
     *
     * @param newCenter TODO: describe
     */
    public void teleport(Vec3f newCenter) {
        this.position = newCenter;
        this.velocity = Vec3f.ZERO;
        this.grounded = false;
    }

    /**
     * TODO: document {@code halfHeight}.
     *
     * @return TODO: describe
     */
    public float halfHeight() { return halfHeight; }
    /**
     * TODO: document {@code radius}.
     *
     * @return TODO: describe
     */
    public float radius() { return radius; }

    /**
     * Yaw of the player's body in degrees, in the same convention as {@code Camera3D.yaw}:
     * 0° faces +X, 90° faces +Z. Updated only on movement (Dark Souls-style: idle keeps last facing).
     *
     * @return TODO: describe
     */
    public float facingYawDegrees() { return facingYawDegrees; }

    private static float wrapAngle180(float deg) {
        float a = deg % NUM_360;
        if (a > NUM_180) a -= NUM_360;
        if (a < -NUM_180) a += NUM_360;
        return a;
    }
}
