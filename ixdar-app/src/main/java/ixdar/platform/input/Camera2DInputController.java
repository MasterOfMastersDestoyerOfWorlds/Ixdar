package ixdar.platform.input;

import java.util.Set;

import ixdar.graphics.cameras.Camera;

/**
 * Maps WASD movement and {@code +}/{@code -} zoom keys onto a {@link Camera}. Shared base for
 * scene-specific {@link KeyGuy}s that want standard 2D camera controls.
 */
public class Camera2DInputController {
    /**
     * Apply the currently held movement / zoom keys to {@code camera}. Movement keys translate
     * to {@link Camera.Direction} calls; opposing zoom keys cancel each other.
     *
     * @param camera target camera (no-op if null)
     * @param pressedKeys live set of pressed key codes (no-op if null)
     * @param shiftMod speed multiplier (typically 1 or 2; from {@link SceneInputFrameUpdater})
     * @param zoomDeltaSeconds frame delta in seconds, scaled by the camera's zoom rate
     */
    public static void apply(Camera camera, Set<Integer> pressedKeys, float shiftMod, double zoomDeltaSeconds) {
        if (camera == null || pressedKeys == null) {
            return;
        }
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
        if (KeyActions.ZoomIn.keyPressed(pressedKeys) && !KeyActions.ZoomOut.keyPressed(pressedKeys)) {
            camera.onScroll(true, zoomDeltaSeconds);
        }
        if (KeyActions.ZoomOut.keyPressed(pressedKeys) && !KeyActions.ZoomIn.keyPressed(pressedKeys)) {
            camera.onScroll(false, zoomDeltaSeconds);
        }
    }
}
