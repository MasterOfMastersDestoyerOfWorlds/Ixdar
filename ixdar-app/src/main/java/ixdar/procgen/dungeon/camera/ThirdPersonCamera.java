package ixdar.procgen.dungeon.camera;

import org.joml.Vector3f;

import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.cameras.CameraController;
import ixdar.procgen.dungeon.player.PlayerController;
import ixdar.procgen.dungeon.values.CellType;

/**
 * Follows the player from behind a pivot at its head. The orbit lives on the shared
 * {@link Camera3D}: its yaw and pitch are the follow angles, its distance the requested one.
 *
 * <p>Yaw follows {@code Camera3D}'s atan2 convention, which {@code PlayerController.update} expects.
 */
public final class ThirdPersonCamera implements CameraController {

    private static final float SENSITIVITY_DEGREES_PER_PIXEL = 0.1f;
    private static final float MAX_ELEVATION_DEGREES = 80f;
    private static final float ZOOM_BASE = 0.9f;
    private static final float CAMERA_RADIUS_FRAC = 0.15f;
    private static final float CAMERA_PADDING_FRAC = 0.05f;
    private static final float DEFAULT_DISTANCE_CELLS = 2.5f;
    private static final float MIN_DISTANCE_CELLS = 0.6f;
    private static final float MAX_DISTANCE_CELLS = 8.0f;

    /** The camera whose yaw, pitch and distance the follow turns and zooms. */
    public final Camera3D camera;

    /** World-space size of a grid cell, which scales the distance and its bounds. */
    public float cellSize = 1f;

    /**
     * Follow with {@code camera}; call {@link #enterFromCurrentCamera} before the first
     * {@link #update}.
     *
     * @param camera the shared camera the follow writes
     */
    public ThirdPersonCamera(Camera3D camera) {
        this.camera = camera;
    }

    /**
     * Start following from the first-person view without a jolt: keep the yaw, clamp the pitch to
     * the follow's range, and stand the default distance back.
     *
     * @param gridCellSize world-space size of a grid cell
     */
    public void enterFromCurrentCamera(float gridCellSize) {
        cellSize = gridCellSize;
        camera.pitch = clamp(camera.pitch, -MAX_ELEVATION_DEGREES, MAX_ELEVATION_DEGREES);
        camera.distance = DEFAULT_DISTANCE_CELLS * cellSize;
    }

    /**
     * Turn the follow by the cursor travel: across drives the yaw, down lowers the pitch, clamped
     * to +-80 degrees.
     *
     * @param lastX window x of the previous cursor sample
     * @param lastY window y of the previous cursor sample
     * @param x     window x of the cursor
     * @param y     window y of the cursor
     */
    @Override
    public void pointerMoved(float lastX, float lastY, float x, float y) {
        camera.yaw += (x - lastX) * SENSITIVITY_DEGREES_PER_PIXEL;
        camera.pitch = clamp(camera.pitch - (y - lastY) * SENSITIVITY_DEGREES_PER_PIXEL,
                -MAX_ELEVATION_DEGREES, MAX_ELEVATION_DEGREES);
    }

    /**
     * A drag turns the follow as a plain move does, the cursor being captured.
     *
     * @param lastX window x of the previous cursor sample
     * @param lastY window y of the previous cursor sample
     * @param x     window x of the cursor
     * @param y     window y of the cursor
     */
    @Override
    public void pointerDragged(float lastX, float lastY, float x, float y) {
        pointerMoved(lastX, lastY, x, y);
    }

    /**
     * The wheel zooms the follow.
     *
     * @return true
     */
    @Override
    public boolean usesWheel() {
        return true;
    }

    /**
     * Scale the distance by {@code 0.9} to the power of {@link #SCROLL_TICKS_PER_UNIT} times the
     * summed delta, clamped to 0.6 to 8 cells.
     *
     * @param delta summed vertical wheel delta, positive away from the user (zoom in)
     */
    @Override
    public void wheelScrolled(double delta) {
        double wheelTicks = SCROLL_TICKS_PER_UNIT * delta;
        if (wheelTicks == 0) {
            return;
        }
        camera.distance = clamp(camera.distance * (float) Math.pow(ZOOM_BASE, wheelTicks),
                MIN_DISTANCE_CELLS * cellSize, MAX_DISTANCE_CELLS * cellSize);
    }

    /**
     * Place the camera behind the pivot above the player's head, swept in against the grid, and
     * point it along the follow angles. Call before {@code player.update} so it reads the new yaw.
     *
     * @param player player whose head is the pivot
     * @param cells  3D cell grid for the {@link CameraGridSweep} obstruction test
     * @param gridW  grid width in cells (X)
     * @param gridH  grid height in floors (Y)
     * @param gridD  grid depth in cells (Z)
     */
    public void update(PlayerController player, CellType[] cells, int gridW, int gridH, int gridD) {
        Vector3f playerPos = player.position();
        Vector3f pivot = new Vector3f(playerPos.x(), playerPos.y() + player.halfHeight(), playerPos.z());
        double azimuth = Math.toRadians(camera.yaw);
        double elevation = Math.toRadians(camera.pitch);
        float cosE = (float) Math.cos(elevation);
        float sinE = (float) Math.sin(elevation);
        float cosA = (float) Math.cos(azimuth);
        float sinA = (float) Math.sin(azimuth);
        // The camera sits opposite the look direction, Camera3D's front for this yaw and pitch.
        float fx = cosA * cosE;
        float fy = sinE;
        float fz = sinA * cosE;
        Vector3f desired = new Vector3f(pivot.x() - fx * camera.distance,
                pivot.y() - fy * camera.distance,
                pivot.z() - fz * camera.distance);
        float radius = CAMERA_RADIUS_FRAC * cellSize;
        float padding = CAMERA_PADDING_FRAC * cellSize;
        Vector3f swept = CameraGridSweep.sweep(pivot, desired, radius, cells, gridW, gridH, gridD,
                cellSize, padding);
        camera.position.set(swept.x(), swept.y(), swept.z());
        camera.setOrientation(camera.yaw, camera.pitch);
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }
}
