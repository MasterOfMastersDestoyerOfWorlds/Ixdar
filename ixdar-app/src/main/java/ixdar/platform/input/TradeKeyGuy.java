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
     * TODO: document {@code TradeKeyGuy}.
     *
     * @param tradeScene TODO: describe
     * @param camera TODO: describe
     * @param canvas TODO: describe
     */
    public TradeKeyGuy(TradeScene tradeScene, Camera camera, Canvas3D canvas) {
        super(camera, canvas);
        this.tradeScene = tradeScene;
    }

    /**
     * TODO: document {@code keyCallback}.
     *
     * @param window TODO: describe
     * @param key TODO: describe
     * @param scancode TODO: describe
     * @param action TODO: describe
     * @param mods TODO: describe
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
     * TODO: document {@code charCallback}.
     *
     * @param window TODO: describe
     * @param codepoint TODO: describe
     */
    @Override
    public void charCallback(long window, int codepoint) {
        Platforms.init(canvas.platform.getPlatformID());
        recordAbstractAction("trade_char", "codepoint", codepoint);
        // No terminal in trade scene, so no character input handling needed
    }

    /**
     * TODO: document {@code paintUpdate}.
     *
     * @param SHIFT_MOD TODO: describe
     */
    @Override
    public void paintUpdate(float SHIFT_MOD) {
        if (!active)
            return;
        super.apply(camera, pressedKeys, SHIFT_MOD, Clock.deltaTime());
    }
}
