package ixdar.platform.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.ACTION_REPEAT;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.Clock;
import ixdar.gui.ui.tools.RoutePlanningTool;
import ixdar.platform.Platforms;
import ixdar.scenes.trade.TradeScene;

/**
 * Keyboard input handler for the trade game scene. Extends KeyGuy but removes
 * MainScene-specific behavior.
 */
public class TradeKeyGuy extends KeyGuy {

    private TradeScene tradeScene;

    /**
     * @param tradeScene owning trade scene (used for tool dispatch and menu return)
     * @param camera camera the controller drives
     * @param canvas owning canvas
     */
    public TradeKeyGuy(TradeScene tradeScene, Camera camera, Canvas3D canvas) {
        super(camera, canvas);
        this.tradeScene = tradeScene;
    }

    /**
     * Trade-specific key handler: maintains {@link KeyGuy#pressedKeys}, forwards key presses
     * first to {@link RoutePlanningTool}, and routes ESC to {@link TradeScene#returnToMenu()}.
     *
     * @param window platform window handle
     * @param key key code
     * @param scancode raw scancode (GLFW; 0 on web)
     * @param action {@code ACTION_PRESS} / {@code ACTION_REPEAT} / {@code ACTION_RELEASE}
     * @param mods modifier-key bitmask
     */
    @Override
    public void keyCallback(long window, int key, int scancode, int action, int mods) {
        Platforms.init(canvas.platform.getPlatformID());
        if (!active)
            return;
        recordAbstractAction("trade_key", "key", key, "mods", mods, "action", action);

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
        System.out.println("[TradeKeyGuy] Key pressed: " + key + " mods: " + mods);

        // Forward key to active tool first
        if (tradeScene.activeTool instanceof RoutePlanningTool) {
            RoutePlanningTool rpt = (RoutePlanningTool) tradeScene.activeTool;
            if (rpt.onKeyPress(key)) {
                System.out.println("[TradeKeyGuy] Key handled by RoutePlanningTool");
                return; // Tool handled this key
            }
        }

        // ESC to return to menu (only if tool didn't handle it)
        if (KeyActions.Back.keyPressed(pressedKeys)) {
            tradeScene.returnToMenu();
        }
    }

    /**
     * Char-event handler: records the codepoint for automation but performs no other
     * action — the trade scene has no terminal.
     *
     * @param window platform window handle
     * @param codepoint Unicode code point
     */
    @Override
    public void charCallback(long window, int codepoint) {
        Platforms.init(canvas.platform.getPlatformID());
        recordAbstractAction("trade_char", "codepoint", codepoint);
        // No terminal in trade scene, so no character input handling needed
    }

    /**
     * Per-frame: forward held movement keys to the camera via
     * {@link Camera2DInputController#apply}.
     *
     * @param SHIFT_MOD speed multiplier
     */
    @Override
    public void paintUpdate(float SHIFT_MOD) {
        if (!active)
            return;
        super.apply(camera, pressedKeys, SHIFT_MOD, Clock.deltaTime());
    }
}
