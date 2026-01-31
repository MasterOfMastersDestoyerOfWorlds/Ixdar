package ixdar.platform.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.ACTION_REPEAT;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;
import ixdar.platform.Platforms;
import ixdar.scenes.trade.TradeScene;

/**
 * Keyboard input handler for the trade game scene.
 * Extends KeyGuy but removes MainScene-specific behavior.
 */
public class TradeKeyGuy extends KeyGuy {

    private TradeScene tradeScene;

    public TradeKeyGuy(TradeScene tradeScene, Camera camera, Canvas3D canvas) {
        super(camera, canvas);
        this.tradeScene = tradeScene;
    }

    @Override
    public void keyCallback(long window, int key, int scancode, int action, int mods) {
        Platforms.init(canvas.platform.getPlatformID());
        if (!active) return;

        switch (action) {
            case ACTION_PRESS:
            case ACTION_REPEAT:
                pressedKeys.add(key);
                handleKeyPress(key, mods, action == ACTION_REPEAT);
                break;
            case ACTION_RELEASE:
                pressedKeys.remove(key);
                break;
        }
    }

    private void handleKeyPress(int key, int mods, boolean repeated) {
        // ESC to return to menu
        if (KeyActions.Back.keyPressed(pressedKeys)) {
            tradeScene.returnToMenu();
        }
    }

    @Override
    public void charCallback(long window, int codepoint) {
        Platforms.init(canvas.platform.getPlatformID());
        // No terminal in trade scene, so no character input handling needed
    }

    @Override
    public void paintUpdate(float SHIFT_MOD) {
        if (!active) return;

        camera.setShiftMod(SHIFT_MOD);

        // Camera movement
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

        // Zoom
        if (KeyActions.ZoomIn.keyPressed(pressedKeys) && !KeyActions.ZoomOut.keyPressed(pressedKeys)) {
            camera.onScroll(true, ixdar.graphics.render.Clock.deltaTime());
        }
        if (KeyActions.ZoomOut.keyPressed(pressedKeys) && !KeyActions.ZoomIn.keyPressed(pressedKeys)) {
            camera.onScroll(false, ixdar.graphics.render.Clock.deltaTime());
        }
    }
}
