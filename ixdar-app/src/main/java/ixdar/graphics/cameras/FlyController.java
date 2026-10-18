package ixdar.graphics.cameras;

import java.util.Set;
import java.util.function.BooleanSupplier;

import ixdar.platform.Clock;

/**
 * First-person flight for a {@link Camera3D}: a drag looks round and the movement keys fly, or,
 * while the cursor is captured, every cursor move looks and the keys are left to a player.
 */
public class FlyController implements CameraController {

    /** The camera flown. */
    public final Camera3D camera;

    /** Whether the cursor is captured, as for walking a player: moves look, keys do not fly. */
    public final BooleanSupplier capturesCursor;

    /**
     * Fly {@code camera}, capturing the cursor while {@code capturesCursor} holds.
     *
     * @param camera         the camera flown
     * @param capturesCursor whether the cursor is captured right now
     */
    public FlyController(Camera3D camera, BooleanSupplier capturesCursor) {
        this.camera = camera;
        this.capturesCursor = capturesCursor;
    }

    /**
     * Look round by the cursor travel.
     *
     * @param lastX window x of the previous cursor sample
     * @param lastY window y of the previous cursor sample
     * @param x     window x of the cursor
     * @param y     window y of the cursor
     */
    @Override
    public void pointerDragged(float lastX, float lastY, float x, float y) {
        camera.mouseMove(lastX, lastY, x, y);
    }

    /**
     * Look round by the cursor travel while the cursor is captured.
     *
     * @param lastX window x of the previous cursor sample
     * @param lastY window y of the previous cursor sample
     * @param x     window x of the cursor
     * @param y     window y of the cursor
     */
    @Override
    public void pointerMoved(float lastX, float lastY, float x, float y) {
        if (capturesCursor.getAsBoolean()) {
            camera.mouseMove(lastX, lastY, x, y);
        }
    }

    /**
     * Fly along the held movement keys and zoom by the zoom keys, unless the cursor is captured.
     *
     * @param pressedKeys key codes held down
     * @param shiftMod    speed multiplier
     */
    @Override
    public void keysHeld(Set<Integer> pressedKeys, float shiftMod) {
        if (!capturesCursor.getAsBoolean()) {
            CameraController.moveWithKeys(camera, pressedKeys, shiftMod, Clock.deltaTime());
        }
    }
}
