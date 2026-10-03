package ixdar.platform.input;

import static ixdar.platform.input.Keys.ACTION_PRESS;

import java.util.List;

import ixdar.canvas.Canvas3D;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.Clock;
import ixdar.platform.Toggle;
import ixdar.scenes.model.ControlHint;

/**
 * Shared key handler for orbit/mesh scenes, binding {@code Ctrl+R} to
 * {@link OrbitMouseTrap#resetTarget()} and dispatching each scene's {@link ControlHint} actions
 * by their key code.
 */
public class OrbitCameraKeyGuy extends KeyGuy {

    public static final int MOD_SHIFT = 0x0001;

    public static final int MOD_CONTROL = 0x0002;

    public final OrbitMouseTrap orbitMouse;

    /** Scene controls dispatched on key press, or {@code null} when the scene binds none. */
    public final List<ControlHint> controls;

    /**
     * Wire the key handler to the orbit controller it recentres, with no scene controls.
     *
     * @param orbitMouse orbit controller whose centre {@code Ctrl+R} resets
     * @param camera     camera driven by this handler
     * @param canvas     owning canvas
     */
    public OrbitCameraKeyGuy(OrbitMouseTrap orbitMouse, Camera camera, Canvas3D canvas) {
        this(orbitMouse, camera, canvas, null);
    }

    /**
     * Wire the key handler to the orbit controller and the scene's controls, firing a control's
     * action when its {@link ControlHint#keyCode} is pressed.
     *
     * @param orbitMouse orbit controller whose centre {@code Ctrl+R} resets
     * @param camera     camera driven by this handler
     * @param canvas     owning canvas
     * @param controls   scene controls to dispatch, or {@code null} for none
     */
    public OrbitCameraKeyGuy(OrbitMouseTrap orbitMouse, Camera camera, Canvas3D canvas,
            List<ControlHint> controls) {
        super(camera, canvas);
        this.orbitMouse = orbitMouse;
        this.controls = controls;
    }

    /**
     * Handle {@code Ctrl+R} as an orbit-centre reset; otherwise dispatch to
     * {@link #handleSceneKeys(int, int)}. {@code Ctrl+R} never falls through to a
     * scene's plain-{@code R} binding.
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
            if (key == Keys.R && (mods & MOD_CONTROL) != 0) {
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

    /**
     * Fire the first control whose {@link ControlHint#keyCode} matches {@code key}. Scenes bind
     * keys by adding controls, not by overriding this.
     *
     * @param key  pressed key code
     * @param mods modifier-key bitmask
     */
    public void handleSceneKeys(int key, int mods) {
        if (controls == null) {
            return;
        }
        for (ControlHint hint : controls) {
            boolean modifierHeld = (!hint.controlHeld || (mods & MOD_CONTROL) != 0)
                    && (!hint.shiftHeld || (mods & MOD_SHIFT) != 0);
            if (hint.keyCode == key && hint.keyCode != ControlHint.NO_KEY && hint.action != null
                    && modifierHeld) {
                hint.action.perform();
                markKeyConsumed();
                return;
            }
        }
    }
}
