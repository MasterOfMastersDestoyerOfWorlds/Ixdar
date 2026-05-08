package ixdar.procgen.dungeon.camera;

import org.joml.Vector3f;

import ixdar.graphics.cameras.Camera3D;
import ixdar.procgen.dungeon.player.PlayerController;
import ixdar.procgen.dungeon.values.TileGridValue3D;

/**
 * Orbits the camera around a pivot point above the player's body. Mouse deltas drive
 * azimuth (Y-rotation) and elevation; scroll wheel drives the desired distance. Each frame,
 * {@link #update} writes back into the shared {@link Camera3D}'s position / yaw / pitch and
 * sweeps the camera in toward the pivot using {@link CameraGridSweep} so it doesn't clip walls.
 *
 * <p>Convention: matches {@code Camera3D.yaw} (atan2-based) so the existing
 * {@code PlayerController.update(camera.yaw)} contract continues to work — WASD movement is
 * relative to the on-screen horizontal direction without any extra plumbing.
 */
public final class ThirdPersonCamera {
    public static final double NUM_90_0 = 90.0;
    public static final double NUM_15_0 = 15.0;

    /** Mouse sensitivity in radians per pixel. Matches Camera3D.mouseMove (0.1°/px) for parity. */
    private static final float SENSITIVITY = (float) Math.toRadians(0.1);
    private static final float MIN_ELEVATION = (float) Math.toRadians(-80.0);
    private static final float MAX_ELEVATION = (float) Math.toRadians(80.0);
    private static final float ZOOM_BASE = 0.9f;
    private static final float CAMERA_RADIUS_FRAC = 0.15f;
    private static final float CAMERA_PADDING_FRAC = 0.05f;
    private static final float DEFAULT_DISTANCE_CELLS = 2.5f;
    private static final float MIN_DISTANCE_CELLS = 0.6f;
    private static final float MAX_DISTANCE_CELLS = 8.0f;

    private float azimuth;     // radians; same convention as Camera3D.yaw (atan2(z, x) in radians)
    private float elevation;   // radians; positive = look down at player from above
    private float desiredDistance;

    /**
     * Construct an orbit camera with a default azimuth of -90 degrees, elevation of 15 degrees,
     * and a unit-cell distance. Call {@link #enterFromCurrentCamera} before first {@link #update}
     * to align with the existing camera state.
     */
    public ThirdPersonCamera() {
        this.azimuth = (float) Math.toRadians(-NUM_90_0);
        this.elevation = (float) Math.toRadians(NUM_15_0);
        this.desiredDistance = 1.0f;
    }

    /**
     * Initialize orbit so the toggle from first- to third-person doesn't jolt the view.
     *
     * @param camera   first-person camera whose yaw/pitch (degrees) seeds the orbit angles
     * @param cellSize world-space size of a grid cell, used to scale the default distance
     */
    public void enterFromCurrentCamera(Camera3D camera, float cellSize) {
        this.azimuth = (float) Math.toRadians(camera.yaw);
        this.elevation = (float) Math.toRadians(camera.pitch);
        this.elevation = clamp(this.elevation, MIN_ELEVATION, MAX_ELEVATION);
        this.desiredDistance = DEFAULT_DISTANCE_CELLS * cellSize;
    }

    /**
     * Add mouse motion to the orbit angles: {@code dxPixels} drives azimuth, {@code dyPixels}
     * drives elevation (clamped to roughly +-80 degrees).
     *
     * @param dxPixels horizontal mouse delta in pixels
     * @param dyPixels vertical mouse delta in pixels (positive = mouse moved down)
     */
    public void applyMouseDelta(float dxPixels, float dyPixels) {
        azimuth += dxPixels * SENSITIVITY;
        elevation = clamp(elevation - dyPixels * SENSITIVITY, MIN_ELEVATION, MAX_ELEVATION);
    }

    /**
     * Multiply the desired distance by {@code ZOOM_BASE^wheelTicks}, clamped to the configured
     * min/max distance (in cells, scaled by {@code cellSize}).
     *
     * @param wheelTicks scroll-wheel delta (positive = zoom in)
     * @param cellSize   world-space size of a grid cell, used to scale the clamp range
     */
    public void applyZoom(int wheelTicks, float cellSize) {
        if (wheelTicks == 0) return;
        desiredDistance *= (float) Math.pow(ZOOM_BASE, wheelTicks);
        desiredDistance = clamp(desiredDistance,
                MIN_DISTANCE_CELLS * cellSize, MAX_DISTANCE_CELLS * cellSize);
    }

    /**
     * Compute pivot, sweep camera against grid, write camera.position / yaw / pitch.
     * Call BEFORE {@code player.update(...)} so the controller reads the up-to-date yaw.
     *
     * @param player   player whose head position acts as the orbit pivot
     * @param grid     3D tile grid for the {@link CameraGridSweep} obstruction test
     * @param cellSize world-space size of a grid cell
     * @param camera   shared {@link Camera3D} whose position and orientation are written
     */
    public void update(PlayerController player, TileGridValue3D grid, float cellSize, Camera3D camera) {
        Vector3f playerPos = player.position();
        Vector3f pivot = new Vector3f(playerPos.x(), playerPos.y() + player.halfHeight(), playerPos.z());

        float cosE = (float) Math.cos(elevation);
        float sinE = (float) Math.sin(elevation);
        float cosA = (float) Math.cos(azimuth);
        float sinA = (float) Math.sin(azimuth);
        // Camera sits OPPOSITE of the look direction. Look-from-camera-toward-pivot has the
        // forward vector (cosA*cosE, sinE_down, sinA*cosE) — same as Camera3D's front so yaw==azimuth.
        // So camera offset from pivot = -forward * distance. Pitch is just elevation.
        float fx = cosA * cosE;
        float fy = sinE;
        float fz = sinA * cosE;
        Vector3f desired = new Vector3f(pivot.x() - fx * desiredDistance,
                                  pivot.y() - fy * desiredDistance,
                                  pivot.z() - fz * desiredDistance);

        float radius = CAMERA_RADIUS_FRAC * cellSize;
        float padding = CAMERA_PADDING_FRAC * cellSize;
        Vector3f cam = CameraGridSweep.sweep(pivot, desired, radius, grid, cellSize, padding);

        camera.position.set(cam.x(), cam.y(), cam.z());
        camera.setOrientation((float) Math.toDegrees(azimuth), (float) Math.toDegrees(elevation));
    }

    /**
     * Current orbit azimuth (around Y) in degrees.
     *
     * @return azimuth in degrees
     */
    public float azimuthDegrees() { return (float) Math.toDegrees(azimuth); }
    /**
     * Current orbit elevation (pitch) in degrees.
     *
     * @return elevation in degrees
     */
    public float elevationDegrees() { return (float) Math.toDegrees(elevation); }
    /**
     * Currently requested camera-to-pivot distance in world units, before grid sweeping.
     *
     * @return desired distance in world units
     */
    public float desiredDistance() { return desiredDistance; }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
