package ixdar.platform.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;

import ixdar.graphics.cameras.Camera;
import ixdar.scenes.Scene;
import ixdar.platform.Clock;
import ixdar.scenes.main.Toggle;
import ixdar.tools.ControlHint;

/**
 * Shared key handler for orbit/mesh scenes, binding {@code Ctrl+R} to
 * {@link OrbitMouseTrap#resetTarget()} and dispatching each scene's {@link ControlHint} actions
 * by their key code.
 */
public class OrbitCameraKeyGuy extends KeyGuy {

    public static final int MOD_CONTROL = 0x0002;

    public final OrbitMouseTrap orbitMouse;

    /** Whether Ctrl+R resets the orbit centre; a scene that binds Ctrl+R itself clears it. */
    public boolean controlRResetsTarget = true;

    /**
     * Wire the key handler to the orbit controller and the scene, firing one of the scene's
     * controls when its {@link ControlHint#keyCode} is pressed.
     *
     * @param orbitMouse orbit controller whose centre {@code Ctrl+R} resets
     * @param camera     camera driven by this handler
     * @param canvas     owning canvas, whose controls the keys fire
     */
    public OrbitCameraKeyGuy(OrbitMouseTrap orbitMouse, Camera camera, Scene canvas) {
        super(camera, canvas);
        this.orbitMouse = orbitMouse;
    }

    /**
     * Handle {@code Ctrl+R} as an orbit-centre reset while {@link #controlRResetsTarget}
     * holds; otherwise dispatch to
     * {@link #handleSceneKeys(int, int)}. While the terminal owns the key nothing fires but
     * the terminal toggle.
     *
     * @param window   platform window handle
     * @param key      key code
     * @param scancode platform scancode
     * @param action   {@code ACTION_PRESS}, repeat, or release
     * @param mods     modifier-key bitmask
     */
    @Override
    public void keyCallback(long window, int key, int scancode, int action, int mods) {
        if (active && action == ACTION_PRESS) {
            if (controlRResetsTarget && key == Keys.R && (mods & MOD_CONTROL) != 0
                    && !terminalOwnsKey(key)) {
                orbitMouse.resetTarget();
                markKeyConsumed();
            } else {
                handleSceneKeys(key, mods);
            }
        }
        super.keyCallback(window, key, scancode, action, mods);
    }

    /**
     * Per-frame: apply the held zoom keys only. The free-fly WASD movement is skipped because
     * {@link OrbitMouseTrap} alone owns the camera's position and target; moving them here would
     * desync the light and clip planes from the drawn view.
     *
     * @param shiftMod speed multiplier (typically 1 or 2)
     */
    @Override
    public void paintUpdate(float shiftMod) {
        if (!active || Toggle.IsTerminalFocused.value || camera == null) {
            return;
        }
        camera.setShiftMod(shiftMod);
        applyZoom(camera, pressedKeys, Clock.deltaTime());
    }
}
