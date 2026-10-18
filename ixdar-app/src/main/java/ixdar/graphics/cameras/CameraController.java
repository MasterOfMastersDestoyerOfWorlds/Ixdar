package ixdar.graphics.cameras;

import java.util.Set;

import ixdar.platform.input.KeyActions;

/**
 * How a scene's camera answers the pointer and keys the panes and the active tool leave it: a 2D
 * pan, an orbit, a fly-through or a third-person follow. Positions are window pixels, top-down.
 */
public interface CameraController {

    int SCROLL_TICKS_PER_UNIT = 4;

    float SCROLL_SPEED_SCALE = 100f;

    /**
     * A left press that no pane and no tool took, the start of a camera drag.
     *
     * @param x    window x of the press
     * @param y    window y of the press
     * @param mods modifier-key bitmask of the press, as {@code KeyNames} spells the bits
     */
    default void pointerPressed(float x, float y, int mods) {
    }

    /**
     * The cursor moved with that press held, past the click threshold.
     *
     * @param lastX window x of the previous cursor sample
     * @param lastY window y of the previous cursor sample
     * @param x     window x of the cursor
     * @param y     window y of the cursor
     */
    default void pointerDragged(float lastX, float lastY, float x, float y) {
    }

    /**
     * The cursor moved with no press held.
     *
     * @param lastX window x of the previous cursor sample
     * @param lastY window y of the previous cursor sample
     * @param x     window x of the cursor
     * @param y     window y of the cursor
     */
    default void pointerMoved(float lastX, float lastY, float x, float y) {
    }

    /**
     * Whether the wheel is the camera's, so {@link #wheelScrolled} gets it; otherwise the UI panes
     * under the cursor do.
     *
     * @return false unless the controller zooms with the wheel
     */
    default boolean usesWheel() {
        return false;
    }

    /**
     * The wheel deltas summed since the last frame, whole and unrounded; only called while
     * {@link #usesWheel} holds.
     *
     * @param delta summed vertical delta, 1.0 per wheel notch, positive away from the user
     */
    default void wheelScrolled(double delta) {
    }

    /**
     * One frame of the held keys, while the scene's terminal does not own them.
     *
     * @param pressedKeys key codes held down
     * @param shiftMod    speed multiplier, 2 while the double-speed key is held, else 1
     */
    default void keysHeld(Set<Integer> pressedKeys, float shiftMod) {
    }

    /**
     * Move {@code camera} one frame along every held movement key, then zoom it by the held zoom
     * keys; opposing keys cancel.
     *
     * @param camera           the camera to move
     * @param pressedKeys      key codes held down
     * @param shiftMod         speed multiplier
     * @param zoomDeltaSeconds frame delta in seconds, scaled by the camera's zoom rate
     */
    static void moveWithKeys(Camera camera, Set<Integer> pressedKeys, float shiftMod, double zoomDeltaSeconds) {
        camera.setShiftMod(shiftMod);
        if (KeyActions.MoveUp.keyPressed(pressedKeys)) {
            camera.move(Camera.Direction.FORWARD);
        }
        if (KeyActions.MoveLeft.keyPressed(pressedKeys)) {
            camera.move(Camera.Direction.LEFT);
        }
        if (KeyActions.MoveDown.keyPressed(pressedKeys)) {
            camera.move(Camera.Direction.BACKWARD);
        }
        if (KeyActions.MoveRight.keyPressed(pressedKeys)) {
            camera.move(Camera.Direction.RIGHT);
        }
        zoomWithKeys(camera, pressedKeys, zoomDeltaSeconds);
    }

    /**
     * Zoom {@code camera} by the held zoom keys alone, leaving its position; opposing keys cancel.
     *
     * @param camera           the camera to zoom
     * @param pressedKeys      key codes held down
     * @param zoomDeltaSeconds frame delta in seconds, scaled by the camera's zoom rate
     */
    static void zoomWithKeys(Camera camera, Set<Integer> pressedKeys, double zoomDeltaSeconds) {
        if (KeyActions.ZoomIn.keyPressed(pressedKeys) && !KeyActions.ZoomOut.keyPressed(pressedKeys)) {
            camera.onScroll(true, zoomDeltaSeconds);
        }
        if (KeyActions.ZoomOut.keyPressed(pressedKeys) && !KeyActions.ZoomIn.keyPressed(pressedKeys)) {
            camera.onScroll(false, zoomDeltaSeconds);
        }
    }
}
