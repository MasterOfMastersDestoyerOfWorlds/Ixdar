package ixdar.platform.input;

import java.util.Set;

import ixdar.graphics.cameras.Camera;

/**
 * Provides standard 2D camera keyboard controls. Used as the default key handler
 * for scenes that need pan/zoom via keyboard.
 */
public class Camera2DInputController implements InputHandler.KeyHandler {

    private final Camera camera;

    public Camera2DInputController(Camera camera) {
        this.camera = camera;
    }

    @Override
    public boolean onKeyPress(int key, int mods, boolean repeated) {
        if (camera == null) return false;

        Set<Integer> pressedKeys = Set.of(key);
        boolean handled = false;

        if (KeyActions.ZoomIn.keyPressed(pressedKeys)) {
            camera.onScroll(true, 0.016);
            handled = true;
        }
        if (KeyActions.ZoomOut.keyPressed(pressedKeys)) {
            camera.onScroll(false, 0.016);
            handled = true;
        }
        if (KeyActions.ControlMask.keyPressed(pressedKeys)) {
            if (KeyActions.MoveUp.keyPressed(pressedKeys)) {
                camera.move(Camera.Direction.FORWARD);
                handled = true;
            }
            if (KeyActions.MoveDown.keyPressed(pressedKeys)) {
                camera.move(Camera.Direction.BACKWARD);
                handled = true;
            }
            if (KeyActions.MoveLeft.keyPressed(pressedKeys)) {
                camera.move(Camera.Direction.LEFT);
                handled = true;
            }
            if (KeyActions.MoveRight.keyPressed(pressedKeys)) {
                camera.move(Camera.Direction.RIGHT);
                handled = true;
            }
        }

        return handled;
    }
}
