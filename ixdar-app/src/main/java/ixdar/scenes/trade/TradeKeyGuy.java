package ixdar.scenes.trade;

import static ixdar.platform.input.Keys.ACTION_PRESS;
import static ixdar.platform.input.Keys.ACTION_RELEASE;
import static ixdar.platform.input.Keys.ACTION_REPEAT;

import ixdar.graphics.cameras.Camera;
import ixdar.platform.Clock;
import ixdar.platform.Platforms;
import ixdar.platform.input.KeyGuy;

/**
 * Keyboard input handler for the trade game scene. Extends KeyGuy but removes
 * MainScene-specific behavior.
 */
public class TradeKeyGuy extends KeyGuy {

    /**
     * Build a trade-scene key controller bound to the given camera.
     *
     * @param tradeScene owning trade scene, whose control rows the keys fire
     * @param camera camera the controller drives
     */
    public TradeKeyGuy(TradeScene tradeScene, Camera camera) {
        super(camera, tradeScene);
    }

    /**
     * Trade-specific key handler: maintains {@link KeyGuy#pressedKeys} and fires the scene's
     * control rows, the active tool's first, on a press and on each repeat.
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
            handleSceneKeys(key, mods);
            break;
        case ACTION_RELEASE:
            pressedKeys.remove(key);
            break;
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
