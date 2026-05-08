package ixdar.platform.input;

import java.util.Set;

import ixdar.graphics.cameras.Camera;

public class Camera2DInputController {
    /**
     * TODO: document {@code apply}.
     *
     * @param camera TODO: describe
     * @param pressedKeys TODO: describe
     * @param shiftMod TODO: describe
     * @param zoomDeltaSeconds TODO: describe
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
