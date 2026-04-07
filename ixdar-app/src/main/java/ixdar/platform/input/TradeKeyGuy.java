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
 * Keyboard input handler for the trade game scene.
 * 
 * Uses the new InputHandler abstraction with scene-specific key bindings
 * configured via composition rather than inheritance.
 */
public class TradeKeyGuy extends InputHandler {

    private TradeScene tradeScene;

    public TradeKeyGuy(TradeScene tradeScene, Camera camera, Canvas3D canvas) {
        super(new Builder("trade-scene")
            .camera(camera)
            .canvas(canvas)
            .keyHandler(createTradeSceneKeyHandler()));
        this.tradeScene = tradeScene;
    }

    private InputHandler.KeyHandler createTradeSceneKeyHandler() {
        return new InputHandler.KeyHandler() {
            @Override
            public boolean onKeyPress(int key, int mods, boolean repeated) {
                if (!active) {
                    return false;
                }
                recordAbstractAction("trade_key", "key", key, "mods", mods, "action", repeated ? "repeat" : "press");

                // Forward key to active tool first
                if (tradeScene.activeTool instanceof RoutePlanningTool) {
                    RoutePlanningTool rpt = (RoutePlanningTool) tradeScene.activeTool;
                    if (rpt.onKeyPress(key)) {
                        System.out.println("[TradeKeyGuy] Key handled by RoutePlanningTool");
                        return true; // Tool handled this key
                    }
                }

                // ESC to return to menu (only if tool didn't handle it)
                if (KeyActions.Back.keyPressed(java.util.Set.of(key))) {
                    tradeScene.returnToMenu();
                    return true;
                }

                return false;
            }

            @Override
            public void onKeyRelease(int key, int mods) {
                if (!active) {
                    return;
                }
                pressedKeys.remove(key);
            }

            @Override
            public void onCharInput(int codepoint) {
                Platforms.init(canvas.platform.getPlatformID());
                recordAbstractAction("trade_char", "codepoint", codepoint);
                // No terminal in trade scene, so no character input handling needed
            }

            @Override
            public void onUpdate(float shiftMod, double deltaTime) {
                // Camera movement handled by Camera2DInputController (parent)
            }
        };
    }

    // Expose keyCallback as public for backward compatibility
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

    public void charCallback(long window, int codepoint) {
        Platforms.init(canvas.platform.getPlatformID());
        recordAbstractAction("trade_char", "codepoint", codepoint);
        // No terminal in trade scene, so no character input handling needed
    }

    public void paintUpdate(float SHIFT_MOD) {
        if (!active)
            return;
        super.paintUpdate(SHIFT_MOD);
    }
}
