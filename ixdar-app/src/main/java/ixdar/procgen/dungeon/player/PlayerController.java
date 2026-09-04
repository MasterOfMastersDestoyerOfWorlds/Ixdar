package ixdar.procgen.dungeon.player;

import java.util.Set;

import org.joml.Vector3f;

import ixdar.platform.input.Keys;
import ixdar.procgen.dungeon.physics.CapsuleMover;
import ixdar.procgen.dungeon.physics.CapsuleShape;
import ixdar.procgen.dungeon.values.CellType;

/**
 * Walkable first-person character driven by WASD, Space, and camera yaw. Each {@link #update}
 * integrates one physics step: horizontal input relative to yaw, gravity or jump vertically,
 * then {@link CapsuleMover#moveAndSlide}.
 *
 * <p>Camera orientation is read-only here; mouse-look belongs to the active mouse handler.
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

    private final CellType[] cells;
    private final int gridW;
    private final int gridH;
    private final int gridD;
    private final float cellSize;
    private final float halfHeight;
    private final float radius;
    private final float gravity;
    private final float jumpSpeed;
    private final float moveSpeed;

    private Vector3f position;
    private Vector3f velocity;
    private boolean grounded;
    private float facingYawDegrees;

    /**
     * Builds a controller positioned at {@code spawnCenter}, at rest and not grounded.
     *
     * @param cells       static obstacle grid the capsule collides against, indexed
     *                    {@code x + gridW * (z + gridD * y)}
     * @param gridW       grid width in cells (X)
     * @param gridH       grid height in floors (Y)
     * @param gridD       grid depth in cells (Z)
     * @param cellSize    world units per grid cell (matches {@code GridToMesh3D}'s cellSize)
     * @param spawnCenter initial capsule-center position in world units
     * @param halfHeight  capsule body half-height (cylinder portion)
     * @param radius      capsule radius (sphere caps and cylinder)
     * @param gravity     downward acceleration in world-units / sec²
     * @param jumpSpeed   instantaneous upward velocity applied on jump (world-units / sec)
     * @param moveSpeed   horizontal walk speed (world-units / sec)
     */
    public PlayerController(CellType[] cells, int gridW, int gridH, int gridD,
                            float cellSize, Vector3f spawnCenter,
                            float halfHeight, float radius,
                            float gravity, float jumpSpeed, float moveSpeed) {
        this.cells = cells;
        this.gridW = gridW;
        this.gridH = gridH;
        this.gridD = gridD;
        this.cellSize = cellSize;
        this.halfHeight = halfHeight;
        this.radius = radius;
        this.gravity = gravity;
        this.jumpSpeed = jumpSpeed;
        this.moveSpeed = moveSpeed;
        this.position = spawnCenter;
        this.velocity = new Vector3f(0f, 0f, 0f);
        this.grounded = false;
        this.facingYawDegrees = NUM_0;
    }

    /**
     * Current capsule-center position in world units.
     *
     * @return live position vector (updated each {@link #update} call)
     */
    public Vector3f position() { return position; }
    /**
     * Current capsule velocity in world-units / sec.
     *
     * @return live velocity vector (updated each {@link #update} call)
     */
    public Vector3f velocity() { return velocity; }
    /**
     * Whether the player is standing on a solid floor as of the last {@link #update}.
     *
     * @return {@code true} if the previous tick blocked downward motion (floor contact)
     */
    public boolean grounded() { return grounded; }

    /**
     * Eye position used to drive the camera: top of the cylinder body, just below the head sphere.
     *
     * @return position {@code (centerX, centerY + halfHeight, centerZ)}
     */
    public Vector3f cameraEyePosition() {
        return new Vector3f(position.x(), position.y() + halfHeight, position.z());
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

        velocity = new Vector3f(horizX, vy, horizZ);

        // Integrate.
        Vector3f delta = velocity.mul(dt);
        CapsuleShape capsule = new CapsuleShape(
                position.x(), position.y(), position.z(), halfHeight, radius);
        Vector3f newPos = CapsuleMover.moveAndSlide(capsule, delta, cells, gridW, gridH, gridD, cellSize);

        // Grounded detection: we wanted to fall (delta.y < 0) but actually moved up relative to
        // the requested motion -> the floor pushed us. Threshold accounts for sub-step rounding.
        Vector3f actualMotion = newPos.sub(position);
        boolean wasFalling = delta.y() < NUM_0;
        boolean blockedDownward = actualMotion.y() > delta.y() + NUM_1e_4;
        boolean newlyGrounded = wasFalling && blockedDownward;
        if (newlyGrounded) {
            // Zero downward velocity so gravity doesn't accumulate while standing still.
            velocity = new Vector3f(velocity.x(), NUM_0, velocity.z());
        }
        grounded = newlyGrounded;
        position = newPos;
    }

    /**
     * Reposition the player at a new spawn point and reset velocity / grounded state.
     *
     * @param newCenter new capsule-center position in world units
     */
    public void teleport(Vector3f newCenter) {
        this.position = newCenter;
        this.velocity = new Vector3f(0f, 0f, 0f);
        this.grounded = false;
    }

    /**
     * Capsule body half-height (cylinder portion only — excludes the radius caps).
     *
     * @return half-height in world units
     */
    public float halfHeight() { return halfHeight; }
    /**
     * Capsule radius (sphere caps and cylinder).
     *
     * @return radius in world units
     */
    public float radius() { return radius; }

    /**
     * Yaw of the player's body in degrees, in the same convention as {@code Camera3D.yaw}:
     * 0° faces +X, 90° faces +Z. Updated only on movement (Dark Souls-style: idle keeps last facing).
     *
     * @return current body yaw in degrees, wrapped to {@code (-180, 180]}
     */
    public float facingYawDegrees() { return facingYawDegrees; }

    private static float wrapAngle180(float deg) {
        float a = deg % NUM_360;
        if (a > NUM_180) a -= NUM_360;
        if (a < -NUM_180) a += NUM_360;
        return a;
    }
}
